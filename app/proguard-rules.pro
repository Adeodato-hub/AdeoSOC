# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# androidx.security:security-crypto arrastra com.google.crypto.tink, que a su
# vez referencia anotaciones de error-prone solo usadas en tiempo de
# compilacion (no estan en tiempo de ejecucion ni hacen falta). Sin este
# -dontwarn, R8 falla el build de release con "Missing classes".
-dontwarn com.google.errorprone.annotations.**
