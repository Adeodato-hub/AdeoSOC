package es.adeodato.hermes.data.model

enum class Semaforo { VERDE, AMBAR, ROJO }

/** Forma comun para pintar una fila de "Activos", sea IT ([Activo]) u OT ([ActivoOt]). */
sealed interface ActivoUi {
    val id: String
    val nombre: String
    val maxLevel: Int
    val semaforo: Semaforo
    val ultimaDescripcion: String
    val ultimaTimestamp: String
    val numAlertas: Int
}

/** Un dispositivo (agente Wazuh) con el estado derivado de sus alertas mas recientes. */
data class Activo(
    val agentId: String,
    val agentName: String,
    val agentIp: String?,
    override val maxLevel: Int,
    val severidad: AlertaCruda.Severidad,
    override val semaforo: Semaforo,
    override val ultimaDescripcion: String,
    override val ultimaTimestamp: String,
    override val numAlertas: Int
) : ActivoUi {
    override val id: String get() = agentId
    override val nombre: String get() = agentName
}

/**
 * Activo OT (p.ej. la impresora 3D), vigilado por un poller externo -- NO es
 * un agente Wazuh. Se identifica por data.asset/data.ip, no por agent.id,
 * porque agent.name en estas alertas es el agente Wazuh que decodifico el
 * log (hoy "wazuh-server"), no el activo real.
 */
data class ActivoOt(
    val assetId: String,
    val ip: String?,
    override val maxLevel: Int,
    val severidad: AlertaCruda.Severidad,
    override val semaforo: Semaforo,
    override val ultimaDescripcion: String,
    override val ultimaTimestamp: String,
    override val numAlertas: Int,
    val estado: String?,
    val nozzleTemp: Double?,
    val bedTemp: Double?,
    val ctrlUnauthAbierto: Boolean,
    val camOpenAbierto: Boolean,
    val wsUnauthAbierto: Boolean
) : ActivoUi {
    override val id: String get() = "ot:$assetId"
    override val nombre: String get() = assetId

    val numExposicionesAbiertas: Int
        get() = listOf(ctrlUnauthAbierto, camOpenAbierto, wsUnauthAbierto).count { it }
}

internal fun semaforoPara(severidad: AlertaCruda.Severidad): Semaforo = when (severidad) {
    AlertaCruda.Severidad.BAJA -> Semaforo.VERDE
    AlertaCruda.Severidad.MEDIA -> Semaforo.AMBAR
    AlertaCruda.Severidad.ALTA, AlertaCruda.Severidad.CRITICA -> Semaforo.ROJO
}

/** Entre varias alertas del mismo activo, el valor no-nulo mas reciente de un campo dado. */
private fun <T> List<AlertaCruda>.valorMasReciente(selector: (AlertaCruda) -> T?): T? =
    sortedByDescending { it.timestampMillis ?: 0L }.firstNotNullOfOrNull(selector)

/**
 * Deriva la vista de "Activos" IT a partir de una tanda de alertas: un activo
 * por agente, con el semaforo segun su alerta mas grave del lote. No es un
 * inventario completo de dispositivos -- Fase 1 es de solo lectura sobre lo
 * que ya ha alertado, no una lista de agentes de Wazuh en si.
 *
 * Excluye las alertas OT (ver [isOt]): esas no representan al agente Wazuh
 * que las decodifico, sino a un activo OT distinto -- se derivan aparte con
 * [aActivosOt] para no contaminar el semaforo del agente real.
 */
fun List<AlertaCruda>.aActivos(): List<Activo> =
    filterNot { it.isOt }
        .groupBy { it.agentId ?: "?" }
        .map { (agentId, alertas) ->
            val masGrave = alertas.maxBy { it.ruleLevel }
            val masReciente = alertas.maxBy { it.timestampMillis ?: 0L }
            Activo(
                agentId = agentId,
                agentName = alertas.firstNotNullOfOrNull { it.agentName } ?: agentId,
                agentIp = alertas.firstNotNullOfOrNull { it.agentIp },
                maxLevel = masGrave.ruleLevel,
                severidad = masGrave.severidad,
                semaforo = semaforoPara(masGrave.severidad),
                ultimaDescripcion = masReciente.ruleDescription,
                ultimaTimestamp = masReciente.timestamp,
                numAlertas = alertas.size
            )
        }
        .sortedByDescending { it.maxLevel }

/**
 * Deriva la vista de "Activos" OT: un activo por data.asset (o data.ip si no
 * hay asset), NUNCA por agent.id -- ver el comentario de [isOt].
 */
fun List<AlertaCruda>.aActivosOt(): List<ActivoOt> =
    filter { it.isOt }
        .groupBy { it.otAsset ?: it.otIp ?: "?" }
        .map { (assetId, alertas) ->
            val masGrave = alertas.maxBy { it.ruleLevel }
            val masReciente = alertas.maxBy { it.timestampMillis ?: 0L }
            ActivoOt(
                assetId = assetId,
                ip = alertas.firstNotNullOfOrNull { it.otIp },
                maxLevel = masGrave.ruleLevel,
                severidad = masGrave.severidad,
                semaforo = semaforoPara(masGrave.severidad),
                ultimaDescripcion = masReciente.ruleDescription,
                ultimaTimestamp = masReciente.timestamp,
                numAlertas = alertas.size,
                estado = alertas.valorMasReciente { it.otState },
                nozzleTemp = alertas.valorMasReciente { it.nozzleTemp },
                bedTemp = alertas.valorMasReciente { it.bedTemp },
                ctrlUnauthAbierto = alertas.valorMasReciente { it.ctrlUnauth } == true,
                camOpenAbierto = alertas.valorMasReciente { it.camOpen } == true,
                wsUnauthAbierto = alertas.valorMasReciente { it.wsUnauth } == true
            )
        }
        .sortedByDescending { it.maxLevel }
