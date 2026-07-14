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
     * [primeraCarga]: true si es la primera vez que ESTE consumidor sondea
     * desde que arranco. Devuelve las alertas nuevas de severidad Media, Alta
     * o Critica (ambar o rojo) que ningun consumidor haya notificado aun.
     */
    fun filtrarNuevasNotificables(alertas: List<AlertaCruda>, primeraCarga: Boolean): List<AlertaCruda> {
        synchronized(lock) {
            if (primeraCarga) {
                idsNotificados.addAll(alertas.map { it.docId })
                return emptyList()
            }
            val nuevas = alertas.filter {
                it.docId !in idsNotificados && it.severidad != AlertaCruda.Severidad.BAJA
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
