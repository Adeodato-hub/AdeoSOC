# PASO 1 — Estructura de la app

**Fecha:** 2026-07-05

## 1. Proyecto

- Android Studio, Kotlin + Jetpack Compose, **un solo modulo** (`app`).
- `namespace`/`applicationId`: `es.adeodato.hermes`.
- `minSdk 29`, `targetSdk 36`, `compileSdk 36.1` — mismas versiones de AGP (9.2.1), Kotlin (2.2.10) y Compose BOM (2026.02.01) que el proyecto DNSGuardian/AdeoShield, por consistencia.
- Nuevas dependencias sobre esa base: `androidx.security:security-crypto` (credenciales cifradas), `androidx.navigation:navigation-compose` (las 3 pantallas), `kotlinx-coroutines-android` (polling), `material-icons-extended`.
- Ruta del proyecto: `Documents/GitHubProjects/HERMES/` (junto a `docs/`, donde vive este informe).

## 2. Arquitectura de red (modelo acordado)

`data/network/ArgosAlertsSource.kt` define una interfaz (`ArgosAlertsSource`) con **dos implementaciones intercambiables** que devuelven la misma forma cruda `hits.hits[]` de OpenSearch, así que el parser y la UI no cambian al conmutar entre ellas:

- `IndexerBasicAuthSource` — **el modelo objetivo**: Basic Auth directo contra el Indexer (`POST {url}/wazuh-alerts-*/_search`, header `Authorization: Basic ...`).
- `DashboardProxySource` — **atajo de desarrollo**: login contra `/auth/login` (backend basicauth del Dashboard) + cookie de sesión + `/api/console/proxy`. Solo existe porque hoy el puerto 9200 está bloqueado desde el host (ver `paso0-api-wazuh.md`).

`ArgosAlertsSourceFactory` elige una u otra según un interruptor (`useDashboardProxy`) que se guarda en la configuración — **conmutar no requiere tocar código**, solo el interruptor de la pantalla de Ajustes.

`InsecureTls.kt` añade un `TrustManager` que acepta el certificado autofirmado de la OVA — marcado explícitamente como chapuza de laboratorio con un TODO para sustituirlo por *pinning* antes de salir de la red doméstica.

## 3. Parser defensivo

`data/model/AlertaCruda.kt` implementa exactamente el modelo de datos aprobado en el Paso 0: `fromHit()` descarta (con `Log.w`, sin lanzar excepción) cualquier alerta a la que le falte `timestamp`, `rule.id`, `rule.level` o `rule.description`; todo lo demás es nullable y se lee con `opt*` (nunca `get*`, que lanza si falta la clave). `data/model/Activo.kt` deriva la vista de "Activos" agrupando las alertas por `agent.id` y calculando el semáforo (verde/ámbar/rojo) a partir de la alerta más grave de cada agente.

## 4. Pantallas

- **Ajustes** (`ui/config`): URL base, usuario, contraseña, segundos de refresco e interruptor de "usar proxy del Dashboard"; botones "Probar conexión" (hace una petición real de 3 alertas) y "Guardar". Todo se persiste con `CredentialStore` (`EncryptedSharedPreferences` + Android Keystore, `AES256_GCM`/`AES256_SIV`) — nunca en texto plano.
- **Alertas** (`ui/alerts`): lista con punto de color por severidad, descripción, agente y regla. Refresco manual (icono) + automático según los segundos configurados, mientras la pantalla esté viva.
- **Activos** (`ui/assets`): un dispositivo por agente visto en el lote de alertas, semáforo, IP (si la hay), nivel máximo, nº de alertas y la descripción de la más reciente.
- Navegación inferior de 3 pestañas (`MainActivity.kt`, `androidx.navigation.compose`).

**Fase 1 = solo lectura**: no hay ningún botón ni endpoint que modifique nada en ARGOS (sin `POST`/`PUT`/`DELETE` de escritura, sin acciones de respuesta). El único `POST` que existe es la sintaxis de consulta de OpenSearch (`_search` es de lectura aunque use verbo POST) y el login del atajo de desarrollo.

## 5. Verificación realizada

```
.\gradlew.bat :app:compileDebugKotlin   → BUILD SUCCESSFUL
.\gradlew.bat :app:assembleDebug        → BUILD SUCCESSFUL (2m 20s)
```

APK de depuración generado en:
`app/build/outputs/apk/debug/app-debug.apk`

## 6. Verificación visual (emulador, 2026-07-05)

Se instaló un emulador Android 14 (`google_apis/x86_64`, Pixel 6) en esta misma máquina mediante `cmdline-tools`/`avdmanager`, ya que no había ningún dispositivo disponible. Desde el emulador, la URL del host no es `127.0.0.1` sino el alias especial `10.0.2.2` (así llega al port-forward de VirtualBox montado en el Paso 0).

Flujo probado de principio a fin, con capturas en `docs/evidencia/`:

1. **`paso1-01-ajustes-vacio.png`** — primer arranque, formulario de Ajustes vacío con los valores por defecto.
2. **`paso1-02-probar-conexion-ok.png`** — tras rellenar `https://10.0.2.2:10443` / `admin` / `admin` (proxy del Dashboard activado) y pulsar "Probar conexión": **"OK: 3 alertas recibidas"** — petición real, no simulada.
3. **`paso1-03-alertas-reales.png`** — pestaña Alertas tras guardar y refrescar: lista real de ARGOS (Suricata, PAM, rootcheck, auditd...) con el punto de color correcto según `rule.level` (verde ≤6, rojo en el rootkit de nivel 11 y el auditd de nivel 10).
4. **`paso1-04-activos-reales.png`** — pestaña Activos: dos agentes derivados de las alertas (`wazuh-server` nivel máx. 12, `lubuntu` nivel máx. 10 con su IP `<IP_LAB_NAT>`), ambos correctamente en rojo.

**Nota de UX menor detectada durante la prueba:** justo después de guardar una configuración nueva, las pestañas Alertas/Activos no la recogen al instante si su bucle de refresco automático está a mitad de la espera (hasta `pollSeconds`, 30 s por defecto) — hay que pulsar el icono de refresco manual una vez tras guardar. No bloquea el uso normal (el refresco automático ya recogerá el cambio en su siguiente ciclo) pero queda anotado como posible pulido futuro.

**PASO 1 confirmado funcionando de extremo a extremo.** Pendiente de tu OK para pasar al PASO 2 (notificaciones).
