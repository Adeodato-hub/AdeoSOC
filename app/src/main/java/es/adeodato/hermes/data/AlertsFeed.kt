package es.adeodato.hermes.data

import es.adeodato.hermes.data.model.AlertaCruda
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Ultima lista de alertas recien traida de ARGOS, con el momento en que se publico. */
data class AlertsSnapshot(val alertas: List<AlertaCruda>, val timestampMillis: Long)

/**
 * Punto unico de la lista de alertas, publicado por AlertMonitorService al
 * final de cada ciclo con exito. AlertsViewModel observa esto en vez de hacer
 * su propia peticion HTTP por separado: una sola llamada de red por ciclo, y
 * la pantalla de Alertas se actualiza en el mismo instante en que se dispara
 * el push (ver AlertMonitorService.sondearUnaVez y AlertsViewModel).
 */
object AlertsFeed {
    private val _snapshot = MutableStateFlow<AlertsSnapshot?>(null)
    val snapshot: StateFlow<AlertsSnapshot?> = _snapshot

    fun publicar(alertas: List<AlertaCruda>) {
        _snapshot.value = AlertsSnapshot(alertas, System.currentTimeMillis())
    }
}
