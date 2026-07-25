package es.adeodato.hermes.notify

import es.adeodato.hermes.data.model.AlertaCruda

/**
 * Deduplicacion de notificaciones compartida entre el polling en primer plano
 * (AlertsViewModel, mientras la pantalla de Alertas esta abierta) y el
 * Foreground Service de vigilancia en segundo plano (AlertMonitorService).
 * Al vivir ambos en el mismo proceso, comparten este set en memoria: si los
 * dos sondeos coinciden en detectar la misma alerta nueva, solo notifica el
 * que llega primero.
 *
 * Solo en memoria (proceso): si el proceso muere y algo lo reinicia, la
 * primera carga de ese consumidor vuelve a sembrar sin notificar (mismo
 * criterio que el polling original, para no bombardear con el historial).
 */
object AlertNotificationGate {
    private val idsNotificados = mutableSetOf<String>()
    private val lock = Any()

    /**
     * Umbral minimo configurable en Ajustes (ver MonitorPrefs). Vive aqui
     * como estado del propio gate -- NO como parametro de
     * [filtrarNuevasNotificables] -- para que tanto AlertsViewModel como
     * AlertMonitorService (dos llamadores ya existentes) respeten el cambio
     * sin tener que tocar sus firmas ni su codigo: HermesApp.onCreate() lo
     * inicializa desde la preferencia guardada ANTES de que ningun sondeo
     * pueda arrancar (arranque por boot incluido), y ConfigViewModel lo
     * actualiza al vuelo cuando el usuario cambia el selector.
     */
    @Volatile
    private var umbralMinimo: AlertaCruda.Severidad = AlertaCruda.Severidad.ALTA

    fun actualizarUmbral(umbral: AlertaCruda.Severidad) {
        umbralMinimo = umbral
    }

    /**
     * [primeraCarga]: true si es la primera vez que ESTE consumidor sondea
     * desde que arranco. Devuelve las alertas nuevas de severidad >= umbral
     * configurado (por defecto Alta, ver MonitorPrefs) que ningun consumidor
     * haya notificado aun. Baja nunca notifica pase lo que pase.
     */
    fun filtrarNuevasNotificables(alertas: List<AlertaCruda>, primeraCarga: Boolean): List<AlertaCruda> {
        synchronized(lock) {
            if (primeraCarga) {
                idsNotificados.addAll(alertas.map { it.docId })
                return emptyList()
            }
            val nuevas = alertas.filter {
                it.docId !in idsNotificados &&
                    it.severidad != AlertaCruda.Severidad.BAJA &&
                    it.severidad.ordinal >= umbralMinimo.ordinal
            }
            idsNotificados.addAll(alertas.map { it.docId })
            // Evita crecimiento sin limite en sesiones/servicios muy largos.
            if (idsNotificados.size > 2000) {
                idsNotificados.clear()
                idsNotificados.addAll(alertas.map { it.docId })
            }
            return nuevas
        }
    }
}
