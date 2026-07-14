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
        val pollSeconds: Int
    ) {
        val isComplete: Boolean
            get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    }

    fun load(context: Context): Config {
        val p = prefs(context)
        return Config(
            baseUrl = p.getString(KEY_BASE_URL, "") ?: "",
            username = p.getString(KEY_USERNAME, "") ?: "",
            password = p.getString(KEY_PASSWORD, "") ?: "",
            useDashboardProxy = p.getBoolean(KEY_USE_DASHBOARD_PROXY, true),
            pollSeconds = p.getInt(KEY_POLL_SECONDS, 30)
        )
    }

    fun save(context: Context, config: Config) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_PASSWORD, config.password)
            .putBoolean(KEY_USE_DASHBOARD_PROXY, config.useDashboardProxy)
            .putInt(KEY_POLL_SECONDS, config.pollSeconds)
            .apply()
    }
}
