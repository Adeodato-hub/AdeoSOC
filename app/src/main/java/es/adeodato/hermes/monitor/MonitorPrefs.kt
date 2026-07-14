package es.adeodato.hermes.monitor

import android.content.Context

/**
 * Preferencia del toggle "vigilancia en segundo plano" (Ajustes). No guarda
 * ningun dato sensible (solo un booleano), por eso usa SharedPreferences
 * normales y no el almacen cifrado de CredentialStore.
 */
object MonitorPrefs {
    private const val FILE_NAME = "hermes_monitor_prefs"
    private const val KEY_ENABLED = "background_monitor_enabled"
    private const val KEY_POLL_SECONDS = "background_poll_seconds"

    const val POLL_SECONDS_DEFAULT = 15

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /** Intervalo de sondeo del Foreground Service (15/30/45s, ver ConfigScreen). */
    fun getPollSeconds(context: Context): Int =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_POLL_SECONDS, POLL_SECONDS_DEFAULT)

    fun setPollSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_POLL_SECONDS, seconds)
            .apply()
    }
}
