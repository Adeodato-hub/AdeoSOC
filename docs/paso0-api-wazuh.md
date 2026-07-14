# PASO 0 — Petición real a la API del Wazuh Indexer/Dashboard

**Fecha:** 2026-07-05
**Objetivo:** observar la forma EXACTA de la respuesta antes de construir el parser de la app. Nada de esto se ha asumido: es la salida real del laboratorio ARGOS.

## 1. Topología y acceso

- VM `Wazuh v4.14.5 OVA` (Amazon Linux 2023), en ejecución, IP interna `<IP_LAB_NAT_MANAGER>` dentro de la **NAT Network** de VirtualBox (`10.0.2.0/24`), aislada del host por defecto.
- Se añadieron dos reglas de *port-forward* en esa NAT Network para poder probar desde el host:
  - `wazuh-dashboard: 127.0.0.1:10443 → <IP_LAB_NAT_MANAGER>:443`
  - `wazuh-indexer:   127.0.0.1:19200 → <IP_LAB_NAT_MANAGER>:9200`
- **Hallazgo importante:** el puerto 9200 (Wazuh Indexer, API OpenSearch nativa) **rechaza el handshake TLS** desde fuera del guest (conexión aceptada a nivel TCP, pero resetea en el TLS — compatible con un firewall interno que solo permite ese puerto en localhost). El puerto 443 (Dashboard) sí es accesible normalmente.
- Por tanto, la petición real se hizo **a través del Dashboard**, que actúa de proxy autenticado hacia el Indexer (`/api/console/proxy`). Esto es válido para observar la forma de los datos, pero **no** es el mecanismo que debe usar la app en producción (ver «Decisión pendiente» al final).
- **Known issue — causa raíz encontrada (2026-07-10):** el Indexer tiene `network.host: "127.0.0.1"` en su configuración — solo escucha en loopback, dentro de la propia VM. Confirmado con `ss -tlnp` en la VM (el proceso solo aparece bindeado a `127.0.0.1:9200`) y con un `curl` directo a la IP Tailscale de la VM al puerto 9200, que devuelve `000` (ni siquiera hay TCP accept en esa interfaz). Por eso ningún cliente externo llega al 9200 directo — ni el host por el port-forward de la NAT, ni el móvil por Tailscale — solo procesos dentro de la propia VM (como el Dashboard, que sí puede hablar con el Indexer por loopback y hacer de proxy). **No bloquea nada**: la app sigue funcionando por el proxy del Dashboard (443/10443), que es la vía verificada en la sección 9.
  - **Fix futuro — opción A:** cambiar `network.host` en `opensearch.yml` para que el Indexer escuche también en la IP Tailscale de la VM (o en `0.0.0.0`), y reiniciar el servicio. Cuidado: ampliar el bind address puede activar los *bootstrap checks* de producción de OpenSearch (memory lock, `vm.max_map_count`, límites de descriptores de fichero, etc.), que hoy no se disparan porque el nodo solo escucha en loopback — revisar antes de aplicar, no es un cambio trivial de una línea.
  - **Fix futuro — opción B:** añadir el backend role `kibana_user` a `<USUARIO_RO_INDEXER>` para que la app pueda apoyarse en el proxy del Dashboard de forma soportada (en vez de depender de que el 9200 se abra), sin tocar la config de red del Indexer.
  - Ninguna de las dos se aplica todavía — queda pendiente de decisión.

## 2. Petición realizada

```
# 1) Login (backend "basicauth" de OpenSearch Dashboards)
POST https://<host>:10443/auth/login
Content-Type: application/json
osd-xsrf: true
{"username":"admin","password":"admin"}
→ 200 OK, cookie de sesión "security_authentication"

# 2) Consulta de las 3 alertas más recientes, vía proxy del Dashboard al Indexer
POST https://<host>:10443/api/console/proxy?path=wazuh-alerts-*/_search&method=GET
Content-Type: application/json
osd-xsrf: true
Cookie: security_authentication=<de la respuesta anterior>
{"size":3,"sort":[{"timestamp":{"order":"desc"}}]}
→ 200 OK
```

Respuesta completa (formateada) guardada en
[`evidencia/paso0-respuesta-alertas.json`](evidencia/paso0-respuesta-alertas.json).

⚠️ Nota de seguridad: el usuario `admin/admin` es el que trae la OVA por defecto. **Antes de exponer nada a la app hay que cambiarlo** y crear un usuario de solo lectura dedicado (ver decisión pendiente). **Actualización (2026-07-10): ya está creado y verificado — ver sección 9.**

## 3. Forma real de una alerta (`hits.hits[].`)

Estructura fija (siempre presente, en las 3 alertas observadas):

| Campo | Tipo | Ejemplo real | Notas |
|---|---|---|---|
| `_index` | string | `wazuh-alerts-4.x-2026.07.05` | índice particionado por día |
| `_id` | string | `zZUzM58B8ZoPM4T3WxoK` | id interno de OpenSearch (no confundir con `_source.id`) |
| `_source.timestamp` | string ISO8601 | `2026-07-05T16:53:23.371+0000` | **campo a usar para ordenar/mostrar hora** — offset numérico, no `Z` |
| `_source.rule.id` | string | `"100010"` | viene como string, no número |
| `_source.rule.level` | int | `6` | severidad Wazuh (0–15 aprox.) |
| `_source.rule.description` | string | `"Suricata: Posible hostname de Kali Linux..."` | |
| `_source.rule.groups` | array[string] | `["suricata","argos","recon"]` | |
| `_source.agent.id` | string | `"001"` | `"000"` = el propio manager |
| `_source.agent.name` | string | `"lubuntu"` | |
| `_source.decoder.name` | string | `"json"` / `"rootcheck"` / `"pam"` | qué decoder de Wazuh clasificó el log |
| `_source.full_log` | string | (log crudo, a veces JSON-como-string) | puede ser largo; en Suricata es un JSON serializado dentro de un string |
| `_source.location` | string | `"/var/log/suricata/eve.json"`, `"rootcheck"`, `"journald"` | origen del log |

## 4. Campos VARIABLES (no asumir que siempre están)

Confirmado con las 3 alertas de muestra, cada una con forma distinta:

- **`_source.agent.ip`** — presente solo en agentes reales (`lubuntu`, `<IP_LAB_NAT>`); **ausente** cuando `agent.id == "000"` (el propio manager, sin IP de agente).
- **`_source.rule.mitre`** (`{id: [], technique: [], tactic: []}`) — solo aparece en reglas mapeadas a MITRE ATT&CK (2 de las 3 alertas la traían, la de Suricata/DHCP no).
- **`_source.data.srcip` vs `_source.data.src_ip`** — inconsistencia real confirmada: las alertas de Suricata (vía decoder `json`) usan `src_ip` (además anidado también en `data.flow.src_ip`); alertas nativas de Wazuh usarían `srcip` (sin guion bajo) — esto es justo lo que `argos_triage.py` ya contempla (`data.get("srcip") or data.get("src_ip")`), así que el parser de la app **debe repetir esa misma tolerancia**.
- **`_source.data.*`** en general es completamente heterogéneo según el decoder: la alerta PAM trae `srcuser`/`dstuser`/`uid`; la de Suricata trae `app_proto`/`flow`/`alert.signature`/`alert.severity`; la de rootcheck trae solo `title`. **No hay un esquema fijo para `data`.**
- **`_source.predecoder`** — aparece solo en algunas alertas (la de PAM lo trae con `hostname`/`program_name`/`timestamp`), no es un campo general.
- Campos de cumplimiento (`pci_dss`, `hipaa`, `tsc`, `nist_800_53`, `gdpr`, `gpg13`) — aparecen dentro de `rule` solo si la regla los tiene mapeados.

## 5. Consecuencias para el parser de la app (PASO 1)

1. Modelar `Alerta` con **solo los campos fijos como no-nulos**: `index`, `docId`, `timestamp`, `rule.id`, `rule.level`, `rule.description`, `agent.id`, `agent.name`, `decoder.name`.
2. Todo lo demás (`agent.ip`, `rule.mitre`, `rule.groups`, `data.srcip`/`src_ip`, `data.srcuser`) → **nullable/opcional**, con el mismo fallback `srcip ?: src_ip` que ya usa `argos_triage.py`.
3. `rule.level` es lo único fiable para el semáforo de severidad/activos — igual que `severidad_desde_nivel()` en Python (Baja ≤6, Media ≤9, Alta ≤12, Crítica >12). Se puede portar tal cual a Kotlin.
4. `_source.data` debe parsearse como mapa dinámico (`JsonObject` / `Map<String, JsonElement>`), nunca como una data class rígida — su forma cambia según qué generó la alerta (Suricata, PAM, rootcheck, auditd, etc.).

## 6. Modelo de datos confirmado (aprobado 2026-07-05)

Regla del parser: **solo `timestamp`, `rule.id`, `rule.level` y `rule.description` son obligatorios.** Todo lo demás (`rule.groups`, `rule.mitre`, `agent.ip`, `manager`, `data.*`, `full_log`, `location`, `decoder.parent`, `predecoder`) es opcional/nullable. El parser nunca debe lanzar excepción por un campo ausente; `data` se trata como mapa dinámico, nunca como clase fija.

`rule.level` es el único campo que decide el color del semáforo (Baja ≤6, Media ≤9, Alta ≤12, Crítica >12 — igual que `severidad_desde_nivel()` en `argos_triage.py`).

Las tres alertas de muestra (Suricata/DHCP, rootcheck, PAM) están en
[`evidencia/paso0-respuesta-alertas.json`](evidencia/paso0-respuesta-alertas.json)
como referencia de las tres formas reales observadas.

## 7. Decisiones de arquitectura para PASO 1 (aprobadas 2026-07-05)

1. **URL base**: no se hardcodea. Pantalla de Configuración con campo de texto para la URL de ARGOS. En desarrollo: `https://127.0.0.1:10443` (túnel de VirtualBox montado hoy). La URL de red real (LAN/VPN) se decide más adelante, sin bloquear PASO 1.
2. **Red del móvil**: pendiente para más adelante — se pasará la VM a adaptador puente para acceso por LAN, y se valorará Tailscale/VPN para fuera de casa. No bloquea PASO 1.
3. **Credencial**: guardada en almacenamiento seguro de Android (EncryptedSharedPreferences respaldado por Keystore), nunca en texto plano. En desarrollo: `admin/admin`. Pendiente como hardening: crear un usuario de solo lectura (`readall`) en el Indexer y dejar de usar `admin`. **Actualización (2026-07-10): usuario creado y verificado, pendiente de que la app lo use — ver sección 9.**

## 8. Decisión pendiente antes de PASO 1 (bloqueante) — RESUELTA arriba

El acceso de hoy fue un **rodeo de desarrollo** (proxy del Dashboard con sesión de cookie + usuario `admin` por defecto). Eso **no sirve para la app**: una app Android no debería depender de una cookie de sesión de un panel web, ni usar el superusuario `admin`.

Para que la pantalla de Configuración (URL base + credencial) del PASO 1 tenga sentido, hace falta decidir **cómo va a llegar el teléfono a ARGOS de verdad**:

- Acceso solo cuando el móvil esté en la misma red que el host (¿la NAT Network puede exponerse a la LAN de casa, o se monta un adaptador *host-only*/bridged?).
- O acceso remoto vía algo tipo Tailscale/VPN.
- Y crear un **usuario de solo lectura dedicado** en el Indexer (rol `readall` o similar), en vez de usar `admin/admin` — esto también hay que cambiarlo en el propio Wazuh cuanto antes, sea cual sea la decisión de red.

No avanzo a PASO 1 hasta que me confirmes esto, porque define la URL base real y el modelo de credencial que la app va a guardar.

## 9. Usuario de solo lectura creado (2026-07-10)

Sustituye al `admin/admin` de las secciones anteriores, que era solo el default de laboratorio de la OVA y nunca debía llegar a producción.

- **Usuario**: `<USUARIO_RO_INDEXER>`.
- **Rol**: `adeosoc_readonly` — `cluster_permissions: ["cluster_composite_ops_ro"]` + `index_permissions` de solo `read` acotados a `wazuh-alerts-*`, `argos-ai-enrichment` y `argos-shift-summary` (nada de `*`, a diferencia del rol interno `readall`).
- **Creado vía** API REST de OpenSearch Security (`PUT _plugins/_security/api/roles|internalusers|rolesmapping`), a través del proxy del Dashboard — no por `internal_users.yml` + `securityadmin.sh`, porque el acceso SSH a la VM no estaba disponible desde la sesión de trabajo y el puerto 9200/19200 tiene el *known issue* de TLS de la sección 1.
- **Verificado**: lectura 200 en los 3 índices; 403 en un índice fuera de alcance (`.opendistro-security`); 403 en un intento de `DELETE`. Confirmado también que el proxy del Dashboard funciona logueado como `<USUARIO_RO_INDEXER>` (no solo como `admin`).
- **Backend role `kibanauser`**: para que `<USUARIO_RO_INDEXER>` pudiera usar el proxy del Dashboard (`DashboardProxySource`, el mecanismo real que usa la app hoy) hizo falta añadirle el backend role `kibanauser`, aparte del rol `adeosoc_readonly` que ya tenía sobre el Indexer. Sin él, el login al Dashboard funcionaba pero el proxy no.
- **Nota — la UI del Dashboard no persistía los cambios sobre este usuario**: se intentó añadir `kibanauser` y cambiar la contraseña desde Security → Internal users de la interfaz web, y no se guardaba (el campo backend roles seguía en "—" tras guardar, y la contraseña nueva daba 401 en la app). Hubo que aplicar ambos cambios directamente por la API REST de OpenSearch Security (`PUT _plugins/_security/api/internalusers/<USUARIO_RO_INDEXER>`, vía el mismo proxy del Dashboard), verificando después con un `GET` que sí quedaban guardados. Causa no investigada — si vuelve a pasar con otro usuario, ir directo a la API en vez de la UI.
- **Cerrado (2026-07-10)**: la app ya usa `<USUARIO_RO_INDEXER>` / la contraseña fijada por API en el dispositivo real, confirmado funcionando en Alertas/Activos/Resumen por el proxy del Dashboard. `admin/admin` retirado del lado de la app.
