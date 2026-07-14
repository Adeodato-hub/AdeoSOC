package es.adeodato.hermes.monitor

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import es.adeodato.hermes.MainActivity
import es.adeodato.hermes.data.AlertsFeed
import es.adeodato.hermes.data.AlertsRepository
import es.adeodato.hermes.data.network.ArgosAlertsSourceFactory
import es.adeodato.hermes.data.network.ArgosConfig
import es.adeodato.hermes.notify.AlertNotificationGate
import es.adeodato.hermes.notify.AlertNotifier
import es.adeodato.hermes.security.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PASO 1 (vigilancia): sondea ARGOS cada N segundos (ver MonitorPrefs.getPollSeconds,
 * seleccionable en Ajustes: 15/30/45s) y notifica alertas NUEVAS de severidad
 * Media/Alta/Critica (ambar o rojo) aunque la app este cerrada -- mismo
 * criterio y misma deduplicacion (AlertNotificationGate) que el polling de
 * AlertsViewModel mientras la pantalla esta abierta.
 *
 * Se ejecuta como Foreground Service tipo "dataSync" (sondeo periodico por
 * red -- se probo primero "specialUse", pero el PackageManager de algunos
 * dispositivos reales, p. ej. un Samsung A54 con Android 16, descarta ese
 * <service> en silencio) con una notificacion persistente de baja prioridad
 * ("AdeoSOC vigilando"). No usa ningun WakeLock propio: mientras el
 * Foreground Service esta activo, el sistema ya exime al proceso de las
 * suspensiones de CPU de Doze/App Standby, que es lo que de otro modo
 * cortaria un delay() en segundo plano.
 */
class AlertMonitorService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var pollingJob: Job? = null
    private val formatoHora = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate() {
        super.onCreate()
        AlertNotifier.crearCanales(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        iniciarComoForeground()
        if (pollingJob?.isActive != true) {
            pollingJob = scope.launch {
                var primeraCargaHecha = false
                while (isActive) {
                    sondearUnaVez(primeraCargaHecha)
                    primeraCargaHecha = true
                    // Se relee en cada vuelta (no al arrancar el servicio) para
                    // que un cambio del selector en Ajustes se aplique sin
                    // reiniciar el servicio.
                    val intervaloMs = MonitorPrefs.getPollSeconds(applicationContext).toLong() * 1000L
                    delay(intervaloMs)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollingJob?.cancel()
        super.onDestroy()
    }

    private suspend fun sondearUnaVez(primeraCargaHecha: Boolean) {
        val stored = CredentialStore.load(applicationContext)
        if (!stored.isComplete) {
            actualizarNotificacionServicio("Pendiente de configurar ARGOS en Ajustes")
            return
        }
        try {
            val config = ArgosConfig(stored.baseUrl, stored.username, stored.password, stored.useDashboardProxy)
            val repo = AlertsRepository(ArgosAlertsSourceFactory.create(config))
            val alertas = repo.fetchRecentAlerts(size = 200)
                .sortedByDescending { it.timestampMillis ?: 0L }
            // Publicado ANTES de notificar: la lista de AlertsViewModel se
            // actualiza en el mismo instante en que se dispara el push, con
            // una unica llamada de red por ciclo (ver AlertsFeed.kt).
            AlertsFeed.publicar(alertas)
            val nuevas = AlertNotificationGate.filtrarNuevasNotificables(alertas, !primeraCargaHecha)
            nuevas.forEach { AlertNotifier.notificarAlerta(applicationContext, it) }
            actualizarNotificacionServicio("Última comprobación ${formatoHora.format(Date())}")
        } catch (e: Exception) {
            actualizarNotificacionServicio("Sin conexión (última: ${formatoHora.format(Date())})")
        }
    }

    private fun iniciarComoForeground() {
        val notificacion = construirNotificacionServicio("Vigilando ARGOS…")
        startForeground(NOTIF_ID_SERVICIO, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun construirNotificacionServicio(texto: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, AlertNotifier.CHANNEL_ID_SERVICIO)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("AdeoSOC vigilando")
            .setContentText(texto)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun actualizarNotificacionServicio(texto: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID_SERVICIO, construirNotificacionServicio(texto))
    }

    companion object {
        private const val NOTIF_ID_SERVICIO = 9001

        fun start(context: Context) {
            val intent = Intent(context, AlertMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlertMonitorService::class.java))
        }

        /**
         * Comprobacion "de verdad" (no la preferencia, el proceso real). Un
         * reinstall (adb install -r) mata el servicio sin tocar la
         * preferencia guardada, dejando el toggle en "true" con el servicio
         * en realidad muerto -- ConfigViewModel usa esto para autocorregirse
         * al abrir Ajustes (ver ConfigViewModel.kt).
         */
        fun estaCorriendo(context: Context): Boolean {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
            @Suppress("DEPRECATION")
            return activityManager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == AlertMonitorService::class.java.name }
        }
    }
}
