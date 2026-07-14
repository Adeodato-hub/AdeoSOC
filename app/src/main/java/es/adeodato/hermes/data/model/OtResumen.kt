package es.adeodato.hermes.data.model

/** Escaparate OT para la pestaña Resumen: derivado en vivo del mismo lote de alertas que Alertas/Activos. */
data class OtResumen(
    val numActivos: Int,
    val numExposicionesAbiertas: Int,
    val ultimaDescripcion: String,
    val ultimaTimestamp: String,
    val estado: String?
)

/** null si no hay ninguna alerta OT en el lote (nada que enseñar en la card). */
fun List<AlertaCruda>.aOtResumen(): OtResumen? {
    val alertasOt = filter { it.isOt }
    if (alertasOt.isEmpty()) return null
    val activos = aActivosOt()
    val masReciente = alertasOt.maxBy { it.timestampMillis ?: 0L }
    return OtResumen(
        numActivos = activos.size,
        numExposicionesAbiertas = activos.sumOf { it.numExposicionesAbiertas },
        ultimaDescripcion = masReciente.ruleDescription,
        ultimaTimestamp = masReciente.timestamp,
        estado = alertasOt.sortedByDescending { it.timestampMillis ?: 0L }.firstNotNullOfOrNull { it.otState }
    )
}
