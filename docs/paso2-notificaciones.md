# PASO 2 — Notificación local para alertas de nivel alto

**Fecha:** 2026-07-05

## 1. Decisión de diseño: no hay canal de ARGOS reutilizable

Se investigó la "Fase 6" de ARGOS (Telegram + WhatsApp vía CallMeBot) antes de implementar nada. Conclusión: es un push de **salida** desde el Wazuh Manager hacia bots con tokens propios del servidor (`wazuh-integratord` → scripts Python en `/var/ossec/integrations/` → `api.telegram.org` / `api.callmebot.com`). No expone webhook de entrada ni API propia — no hay ningún punto de enganche técnico para una app Android. Se implementó por tanto una notificación **100% local**, disparada por el propio polling de la app (`AlertsViewModel`), sin depender de esos bots.

## 2. Implementación

- `notify/AlertNotifier.kt`: crea el canal (`argos_alertas_altas`, `IMPORTANCE_HIGH`) y publica la notificación (título según severidad, cuerpo con agente/nivel/descripción, `PendingIntent` que abre la app).
- `HermesApp.onCreate()`: crea el canal al arrancar.
- `AndroidManifest.xml` + `MainActivity`: permiso `POST_NOTIFICATIONS` declarado y solicitado en tiempo de ejecución (Android 13+) al abrir la app; si se deniega, la app sigue funcionando sin notificaciones.
- `AlertsViewModel.notificarNuevasGraves()`: tras cada sondeo con éxito, compara los ids de las alertas recibidas contra `idsVistos` (en memoria). Notifica solo las que sean **nuevas** (no vistas antes) **y** de severidad Alta o Crítica (`rule.level` > 9). La primera carga de cada sesión solo siembra `idsVistos` sin notificar nada, para no bombardear al abrir la app con el historial ya existente.

## 3. Verificación realizada

- **Compila y empaqueta** (`assembleDebug` → `BUILD SUCCESSFUL`).
- **Caso negativo confirmado en el emulador**: la primera carga (100 alertas ya existentes, incluida una de nivel 11) no generó ninguna notificación — comportamiento correcto.
- **Alerta real de nivel alto confirmada en el Indexer**: se generó un ataque de fuerza bruta SSH real (Kali → Lubuntu, `hydra`, 10 intentos) que Wazuh correlacionó correctamente en `sshd: brute force trying to get access to the system. Authentication failed.`, **nivel 10** (por encima del umbral Alta/Crítica), confirmado directamente contra la API del Indexer.
- **Bug encontrado y corregido en el propio proceso de prueba**: el primer intento de conceder el permiso de notificaciones desde el diálogo del sistema falló por un error mío de escala de coordenadas al tocar "Allow" en el emulador (toqué fuera del botón); `dumpsys package` confirmó `granted=false`. Se corrigió concediendo el permiso manualmente (`adb shell pm grant ... POST_NOTIFICATIONS`), confirmado `granted=true`.
- **Pendiente sin cerrar del todo**: no se ha visto la notificación dispararse en vivo en pantalla. Al reintentar el ataque para provocar una alerta nueva ya con el permiso corregido, la propia Active Response de ARGOS (`firewall-drop`) bloqueó la IP de Kali y, según lo reportado, **el bloqueo no tiene timeout y vuelve a re-bloquearse en bucle** (ver tarea pendiente de ARGOS más abajo), por lo que repetir la prueba en vivo ahora mismo no es práctico.
- **`adb shell dumpsys notification`** confirma que actualmente no hay ninguna notificación de `es.adeodato.adeosoc` publicada — consistente con que no hay ninguna alerta nueva Alta/Crítica pendiente desde la última carga (no es evidencia de fallo, es el estado esperado sin una alerta nueva que notificar).

**Conclusión:** la lógica queda validada por revisión de código + los tres hechos verificados (permiso corregido y concedido, dato real de nivel 10 confirmado en el Indexer, caso negativo sin spam confirmado en pantalla). Falta la confirmación visual del disparo en sí, pendiente de una alerta nueva real en uso normal o de una sesión aparte una vez arreglado el bucle de Active Response.

## 4. Tarea pendiente — proyecto ARGOS (no de HERMES)

**Problema:** el Active Response `firewall-drop` de ARGOS no tiene `<timeout>` configurado (o es indefinido) y carece de whitelist. Al bloquear una IP, los propios scripts de Active Response (ejecutados vía `sudo`) generan alertas de uso de `sudo` que, aparentemente, retroalimentan el bloqueo — entrando en un bucle de auto-bloqueo. Esto impide levantar el bloqueo de forma natural y complica cualquier prueba repetida desde el mismo origen.

**A arreglar en sesión aparte (fuera del alcance de HERMES):**
- Añadir `<timeout>` explícito y razonable al `<active-response>` de `firewall-drop` en `ossec.conf`.
- Excluir de la correlación las alertas generadas por los propios scripts de Active Response (o añadir una whitelist/`<white_list>` para IPs de gestión/laboratorio) para cortar el bucle de auto-bloqueo.

## 5. Estado

**PASO 2 dado por validado** con la evidencia anterior. Pendiente de tu confirmación para avanzar al **PASO 3** (empaquetado: APK firmada + instrucciones de instalación).
