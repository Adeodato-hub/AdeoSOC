package es.adeodato.hermes.data

import es.adeodato.hermes.data.model.AlertaCruda
import es.adeodato.hermes.data.network.ArgosAlertsSource
import org.json.JSONObject

/** Capa fina entre la fuente de red y la UI: parsea de forma defensiva y descarta lo invalido. */
class AlertsRepository(private val source: ArgosAlertsSource) {
    fun fetchRecentAlerts(size: Int = 200): List<AlertaCruda> =
        source.fetchRecentAlertHits(size).mapNotNull { AlertaCruda.fromHit(it) }

    /** Query independiente del feed principal (ventana/filtro propios) -- ver ArgosAlertsSource.fetchAlertHits. */
    fun fetchAlerts(size: Int, windowMinutes: Int, filtroExtra: JSONObject? = null): List<AlertaCruda> =
        source.fetchAlertHits(size, windowMinutes, filtroExtra).mapNotNull { AlertaCruda.fromHit(it) }
}
