package es.adeodato.hermes.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import es.adeodato.hermes.MainActivity
import es.adeodato.hermes.data.model.AlertaCruda
import es.adeodato.hermes.data.network.ArgosConfig
import es.adeodato.hermes.data.network.EnrichmentData
import es.adeodato.hermes.data.network.EnrichmentSourceFactory
import es.adeodato.hermes.security.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AlertNotifier"

/**
 * Fase 1: no hay ningun canal de ARGOS reutilizable para Android (la Fase 6 de
 * ARGOS es un push de SALIDA desde el Wazuh Manager hacia bots de Telegram/
 * WhatsApp con tokens propios del servidor -- no expone webhook ni API para
 * que una app externa se enganche). Por eso la notificacion es 100% local,
 * disparada por el sondeo de la app (en pantalla o en segundo plano, ver
 * monitor/AlertMonitorService.kt) al detectar una alerta nueva de severidad
 * Media, Alta o Critica -- es decir, semaforo ambar o rojo (colorSeveridad en
 * AlertsScreen.kt). La severidad Baja (verde) nunca notifica.
 */
object AlertNotifier {
    const val CHANNEL_ID_ALTA = "argos_alertas_altas"
    const val CHANNEL_ID_MEDIA = "argos_alertas_medias"
    const val CHANNEL_ID_SERVICIO = "argos_servicio_vigilancia"

    // Reintentos del enriquecimiento EN SEGUNDO PLANO tras el push inmediato
    // (no bloquean el aviso, ver notificarAlerta). Mismo patron 5s x 7 que
    // AlertDetailScreen.
    private const val REINTENTOS_ENRIQUECIMIENTO = 7
    private const val ESPERA_ENTRE_REINTENTOS_MS = 5_000L

    // Propio de AlertNotifier, independiente del scope del llamante (ViewModel
    // o Service): el refresco del enriquecimiento debe seguir su curso aunque
    // quien disparo la notificacion ya haya terminado su ciclo de sondeo.
    private val scopeEnriquecimiento = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun crearCanales(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_ALTA,
                "Alertas de ARGOS (nivel alto)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisa cuando ARGOS registra una alerta de severidad Alta o Critica"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_MEDIA,
                "Alertas de ARGOS (nivel medio)",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Avisa cuando ARGOS registra una alerta de severidad Media"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_SERVICIO,
                "Vigilancia en segundo plano",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificacion persistente mientras AdeoSOC vigila ARGOS con la app cerrada"
                setShowBadge(false)
            }
        )
    }

    /**
     * Publica el push INMEDIATAMENTE (sin esperar a Ollama) y, si la alerta
     * tiene sourceId, lanza en segundo plano (sin bloquear esta funcion) un
     * sondeo del enriquecimiento IA; si llega dentro de la ventana de
     * reintentos, actualiza la MISMA notificacion con el analisis.
     */
    fun notificarAlerta(context: Context, alerta: AlertaCruda) {
        // Verde (Baja) nunca notifica; guarda de defensa aunque los llamantes ya filtran.
        if (alerta.severidad == AlertaCruda.Severidad.BAJA) return
        if (!permisoConcedido(context)) return

        val (canal, titulo, prioridad) = datosNotificacion(alerta) ?: return
        val notifId = alerta.docId.hashCode()
        val pendingIntent = pendingIntentDetalle(context, alerta, notifId)
        val agente = alerta.agentName ?: alerta.agentId ?: "?"
        val cuerpo = "$agente · nivel ${alerta.ruleLevel} · ${alerta.ruleDescription}"

        val horaPush = System.currentTimeMillis()
        Log.i(TAG, "Push INMEDIATO alertId=${alerta.sourceId ?: alerta.docId} a las $horaPush (sin esperar IA)")
        publicarNotificacion(context, notifId, canal, titulo, prioridad, pendingIntent, cuerpo, cuerpo)

        val sourceId = alerta.sourceId ?: return
        scopeEnriquecimiento.launch {
            val enriquecimiento = buscarEnriquecimientoConReintentos(context, sourceId)
            if (enriquecimiento != null) {
                Log.i(TAG, "Enriquecimiento llegado para $sourceId; refrescando notificacion existente")
                val cuerpoExpandido = "$cuerpo\n\nAnálisis IA: ${enriquecimiento.aiAnalysis}"
                publicarNotificacion(context, notifId, canal, titulo, prioridad, pendingIntent, cuerpo, cuerpoExpandido)
            }
        }
    }

    private fun permisoConcedido(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun datosNotificacion(alerta: AlertaCruda): Triple<String, String, Int>? = when (alerta.severidad) {
        AlertaCruda.Severidad.CRITICA -> Triple(CHANNEL_ID_ALTA, "Alerta CRÍTICA en ARGOS", NotificationCompat.PRIORITY_HIGH)
        AlertaCruda.Severidad.ALTA -> Triple(CHANNEL_ID_ALTA, "Alerta de nivel ALTO en ARGOS", NotificationCompat.PRIORITY_HIGH)
        AlertaCruda.Severidad.MEDIA -> Triple(CHANNEL_ID_MEDIA, "Alerta de nivel MEDIO en ARGOS", NotificationCompat.PRIORITY_DEFAULT)
        AlertaCruda.Severidad.BAJA -> null // inalcanzable, cubierto por el llamante
    }

    private fun pendingIntentDetalle(context: Context, alerta: AlertaCruda, notifId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context, notifId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun publicarNotificacion(
        context: Context,
        notifId: Int,
        canal: String,
        titulo: String,
        prioridad: Int,
        pendingIntent: PendingIntent,
        cuerpoCorto: String,
        cuerpoExpandido: String
    ) {
        if (!permisoConcedido(context)) return
        val notificacion = NotificationCompat.Builder(context, canal)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(titulo)
            .setContentText(cuerpoCorto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cuerpoExpandido))
            .setPriority(prioridad)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(notifId, notificacion)
    }

    private suspend fun buscarEnriquecimientoConReintentos(context: Context, sourceId: String): EnrichmentData? {
        for (intento in 1..REINTENTOS_ENRIQUECIMIENTO) {
            val resultado = try {
                withContext(Dispatchers.IO) {
                    val stored = CredentialStore.load(context)
                    if (!stored.isComplete) return@withContext null
                    val config = ArgosConfig(stored.baseUrl, stored.username, stored.password, stored.useDashboardProxy)
                    EnrichmentSourceFactory.create(config).fetchEnrichment(sourceId)
                }
            } catch (e: Exception) {
                null
            }
            if (resultado != null) return resultado
            if (intento < REINTENTOS_ENRIQUECIMIENTO) delay(ESPERA_ENTRE_REINTENTOS_MS)
        }
        return null
    }
}
