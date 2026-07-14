package es.adeodato.hermes.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * PASO 1 (vigilancia): si el usuario tenia activado el toggle de "vigilancia
 * en segundo plano" antes de reiniciar el movil, vuelve a arrancar el
 * Foreground Service tras el arranque. Requiere RECEIVE_BOOT_COMPLETED
 * (manifest) y que el usuario haya abierto la app al menos una vez tras
 * instalarla (Android no entrega BOOT_COMPLETED a apps nunca abiertas).
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (MonitorPrefs.isEnabled(context)) {
            AlertMonitorService.start(context)
        }
    }
}
