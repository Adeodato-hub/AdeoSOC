package es.adeodato.hermes.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Guarda la configuracion de conexion a ARGOS (URL base + credenciales) en
 * EncryptedSharedPreferences respaldado por el Android Keystore. Nunca se
 * escribe nada en texto plano ni en SharedPreferences normales.
 */
object CredentialStore {
    private const val FILE_NAME = "hermes_secure_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_USE_DASHBOARD_PROXY = "use_dashboard_proxy"
    private const val KEY_POLL_SECONDS = "poll_seconds"
    private const val KEY_WAZUH_API_URL = "wazuh_api_url"
    private const val KEY_AR_USERNAME = "ar_username"
    private const val KEY_AR_PASSWORD = "ar_password"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    data class Config(
        val baseUrl: String,
        val username: String,
        val password: String,
        val useDashboardProxy: Boolean,
        val pollSeconds: Int,
        /** URL de la API de Wazuh (puerto 55000, alcanzable por Tailscale) para Respuesta Activa. */
        val wazuhApiUrl: String = "",
        /** Usuario API dedicado adeosoc_ar, rol restringido a active-response:command. */
        val arUsername: String = "",
        val arPassword: String = ""
    ) {
        val isComplete: Boolean
            get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

        /** true si hay credenciales suficientes para disparar Active Response (boton "Bloquear IP"). */
        val isActiveResponseComplete: Boolean
            get() = wazuhApiUrl.isNotBlank() && arUsername.isNotBlank() && arPassword.isNotBlank()
    }

    fun load(context: Context): Config {
        val p = prefs(context)
        return Config(
            baseUrl = p.getString(KEY_BASE_URL, "") ?: "",
            username = p.getString(KEY_USERNAME, "") ?: "",
            password = p.getString(KEY_PASSWORD, "") ?: "",
            useDashboardProxy = p.getBoolean(KEY_USE_DASHBOARD_PROXY, true),
            pollSeconds = p.getInt(KEY_POLL_SECONDS, 30),
            wazuhApiUrl = p.getString(KEY_WAZUH_API_URL, "") ?: "",
            arUsername = p.getString(KEY_AR_USERNAME, "") ?: "",
            arPassword = p.getString(KEY_AR_PASSWORD, "") ?: ""
        )
    }

    fun save(context: Context, config: Config) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_PASSWORD, config.password)
            .putBoolean(KEY_USE_DASHBOARD_PROXY, config.useDashboardProxy)
            .putInt(KEY_POLL_SECONDS, config.pollSeconds)
            .putString(KEY_WAZUH_API_URL, config.wazuhApiUrl.trim())
            .putString(KEY_AR_USERNAME, config.arUsername.trim())
            .putString(KEY_AR_PASSWORD, config.arPassword)
            .apply()
    }
}
