# PASO 3 — APK de release firmada + instalación

**Fecha:** 2026-07-06

## 1. Firma

- Keystore generado con `keytool` (RSA 2048, validez 10.000 días), guardado **fuera de control de versiones** como `HERMES/adeosoc.jks` (cubierto por `.gitignore`, igual que `keystore.properties`).
- `app/build.gradle.kts` define `signingConfigs.release` leyendo `storeFile`/`storePassword`/`keyAlias`/`keyPassword` desde `keystore.properties` (nunca hardcodeado en el build script).
- Se añadió `-dontwarn com.google.errorprone.annotations.**` a `proguard-rules.pro`: `androidx.security:security-crypto` arrastra `com.google.crypto.tink`, que referencia anotaciones de solo-compilación que R8 marcaba como "missing classes" y hacían fallar el build de release.

## 2. Build

```
.\gradlew.bat :app:assembleRelease
```

APK resultante:
```
app/build/outputs/apk/release/app-release.apk
```

- **Versión:** `versionName=0.1.0-fase1`, `versionCode=1`
- **applicationId:** `es.adeodato.adeosoc` (nombre público **AdeoSOC**; codename interno HERMES)
- **Tamaño:** ~3.1 MB

## 3. Verificación de firma

```
apksigner verify --verbose --print-certs app-release.apk
```

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
V2 Signer: certificate DN: CN=Rafael Adiosdado Caballero Dieguez, OU=Adeodato, O=Adeodato, L=Lucena, ST=Cordoba, C=ES
V2 Signer: certificate SHA-256 digest: 7df2f2a1e7f904a6934a8f1ee392e28f0b2e683e8530ebfffe91f438204034b5
V2 Signer: key algorithm: RSA, key size: 2048
```

## 4. Hash SHA-256 del APK

```
da61d27e0aaafde0955a754eaa3bdd095e997687f839f52e6d979c18a10ea61e
```
(Verificable en el móvil o el PC receptor antes de instalar, para confirmar que el archivo no se corrompió/alteró en el transporte.)

## 5. Instalación en un Android real

### Opción A — Transferencia directa (sin cable ni ADB)
1. Copia `app-release.apk` al móvil (por USB, un enlace privado propio, Google Drive/Bluetooth, etc. — evita subirlo a sitios públicos, es una app de seguridad con acceso a tus credenciales de ARGOS).
2. En el móvil, **Ajustes → Aplicaciones → Acceso especial → Instalar apps desconocidas**, y activa el permiso para la app que vayas a usar para abrir el APK (Archivos, el gestor de descargas, etc.). En Android 8+ el permiso se concede por app instaladora, no de forma global.
3. Abre el APK desde el gestor de archivos del móvil y confirma la instalación.

### Opción B — Por ADB (con el móvil conectado por USB)
1. Activa **Opciones de desarrollador → Depuración USB** en el móvil.
2. Conéctalo por USB y acepta el diálogo de autorización RSA que aparece en pantalla.
3. Desde el PC:
   ```
   adb install app-release.apk
   ```

### Tras instalar
- Al abrir la app, Android pedirá el permiso de notificaciones (Android 13+) — acéptalo para recibir los avisos de alertas Alta/Crítica del Paso 2.
- En **Ajustes** de la app, configura la URL de ARGOS accesible desde tu red (ver notas del Paso 0/1 sobre el adaptador puente pendiente), usuario y contraseña.

## 6. Estado

**PASO 3 cerrado.** Fase 1 completa: estructura de la app (Paso 1), notificaciones locales (Paso 2) y empaquetado firmado (Paso 3), todos verificados y documentados en `docs/`.
