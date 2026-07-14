package es.adeodato.hermes.data.model

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Alerta de Wazuh/ARGOS ya aplanada para la app.
 *
 * Modelo de parseo acordado en el informe tecnico (docs/paso0-api-wazuh.md):
 * SOLO timestamp, ruleId, ruleLevel y ruleDescription son obligatorios.
 * Todo lo demas es opcional/nullable: la forma de "data" varia por completo
 * segun el decoder de origen (Suricata, PAM, rootcheck, auditd...), asi que
 * NUNCA se modela como una clase fija -- se expone como JSONObject dinamico.
 */
data class AlertaCruda(
    val docId: String,
    /**
     * Id INTERNO de Wazuh (campo "id" dentro de _source, p. ej.
     * "1783364734.779734") -- DISTINTO del _id que le pone OpenSearch/Filebeat
     * al documento en wazuh-alerts-* (docId). El pipeline de enriquecimiento
     * IA de ARGOS (custom-ollama) indexa argos-ai-enrichment usando este id
     * interno, no el _id de OpenSearch -- por eso EnrichmentSource debe
     * consultar por sourceId, nunca por docId (ver docs/paso-ollama-*.md).
     */
    val sourceId: String?,
    val index: String?,
    val timestamp: String,
    val timestampMillis: Long?,
    val ruleId: String,
    val ruleLevel: Int,
    val ruleDescription: String,
    val ruleGroups: List<String>,
    val mitreIds: List<String>,
    val mitreTactics: List<String>,
    val agentId: String?,
    val agentName: String?,
    val agentIp: String?,
    val managerName: String?,
    val decoderName: String?,
    val decoderParent: String?,
    val location: String?,
    val fullLog: String?,
    val data: JSONObject?
) {
    /** IP de origen: Wazuh nativo usa "srcip", Suricata via decoder json usa "src_ip". */
    val srcIp: String?
        get() = data?.optStringOrNull("srcip") ?: data?.optStringOrNull("src_ip")

    val srcUser: String?
        get() = data?.optStringOrNull("srcuser")

    val dstUser: String?
        get() = data?.optStringOrNull("dstuser")

    /**
     * true si la alerta viene del poller OT externo (integracion "argos-ot")
     * o esta en el grupo de regla "ot" -- ver rules 100200-100208 en ARGOS.
     * agent.name para estas alertas es el agente Wazuh que decodifico el log
     * (hoy "wazuh-server"), NUNCA el activo OT real -- para eso esta [otAsset].
     */
    val isOt: Boolean
        get() = data?.optStringOrNull("integration") == "argos-ot" || ruleGroups.contains("ot")

    /** Identidad del activo OT (p.ej. "Ender3V3KE"), independiente del agente Wazuh. */
    val otAsset: String?
        get() = data?.optStringOrNull("asset")

    /** IP del activo OT (p.ej. "<IP_LAN_ACTIVO_OT>"), distinta de agentIp (la del agente Wazuh). */
    val otIp: String?
        get() = data?.optStringOrNull("ip")

    val ctrlUnauth: Boolean?
        get() = data?.optBooleanOrNull("ctrl_unauth")

    val camOpen: Boolean?
        get() = data?.optBooleanOrNull("cam_open")

    val wsUnauth: Boolean?
        get() = data?.optBooleanOrNull("ws_unauth")

    val otState: String?
        get() = data?.optStringOrNull("state")

    val otEvent: String?
        get() = data?.optStringOrNull("event")

    val otErrCode: String?
        get() = data?.optStringOrNull("errcode")

    val nozzleTemp: Double?
        get() = data?.optDoubleOrNull("nozzle_temp")

    val bedTemp: Double?
        get() = data?.optDoubleOrNull("bed_temp")

    enum class Severidad { BAJA, MEDIA, ALTA, CRITICA }

    /** Misma escala que severidad_desde_nivel() en argos_triage.py. */
    val severidad: Severidad
        get() = when {
            ruleLevel <= 6 -> Severidad.BAJA
            ruleLevel <= 9 -> Severidad.MEDIA
            ruleLevel <= 12 -> Severidad.ALTA
            else -> Severidad.CRITICA
        }

    companion object {
        private const val TAG = "AlertaCruda"

        // Wazuh entrega el timestamp con offset numerico, p. ej. "+0200" (no "+02:00").
        private val FORMATO_TS = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        }

        private fun parseTimestampMillis(ts: String): Long? = try {
            FORMATO_TS.get()!!.parse(ts)?.time
        } catch (e: Exception) {
            null
        }

        /**
         * Construye una AlertaCruda a partir de UN elemento de hits.hits[] tal cual
         * lo devuelve la API (con _index, _id, _source). Devuelve null (en vez de
         * lanzar) si faltan los campos realmente obligatorios -- esa alerta se
         * descarta pero no debe tumbar el resto del listado.
         */
        fun fromHit(hit: JSONObject): AlertaCruda? {
            val source = hit.optJSONObject("_source") ?: run {
                Log.w(TAG, "hit sin _source, se descarta: $hit")
                return null
            }

            val timestamp = source.optStringOrNull("timestamp")
            val rule = source.optJSONObject("rule")
            val ruleId = rule?.optStringOrNull("id")
            val ruleLevel = rule?.let { if (it.has("level")) it.optInt("level", Int.MIN_VALUE) else null }
            val ruleDescription = rule?.optStringOrNull("description")

            if (timestamp == null || ruleId == null || ruleLevel == null || ruleLevel == Int.MIN_VALUE || ruleDescription == null) {
                Log.w(TAG, "hit sin campos obligatorios (timestamp/rule.id/level/description), se descarta: ${hit.optString("_id")}")
                return null
            }

            val agent = source.optJSONObject("agent")
            val decoder = source.optJSONObject("decoder")
            val mitre = rule.optJSONObject("mitre")

            return AlertaCruda(
                docId = hit.optString("_id", ruleId + timestamp),
                sourceId = source.optStringOrNull("id"),
                index = hit.optStringOrNull("_index"),
                timestamp = timestamp,
                timestampMillis = parseTimestampMillis(timestamp),
                ruleId = ruleId,
                ruleLevel = ruleLevel,
                ruleDescription = ruleDescription,
                ruleGroups = rule.optJSONArray("groups").toStringList(),
                mitreIds = mitre?.optJSONArray("id").toStringList(),
                mitreTactics = mitre?.optJSONArray("tactic").toStringList(),
                agentId = agent?.optStringOrNull("id"),
                agentName = agent?.optStringOrNull("name"),
                agentIp = agent?.optStringOrNull("ip"),
                managerName = source.optJSONObject("manager")?.optStringOrNull("name"),
                decoderName = decoder?.optStringOrNull("name"),
                decoderParent = decoder?.optStringOrNull("parent"),
                location = source.optStringOrNull("location"),
                fullLog = source.optStringOrNull("full_log"),
                data = source.optJSONObject("data")
            )
        }
    }
}

/** optString() de org.json devuelve "" si falta la clave; esto devuelve null de verdad. */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

internal fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it, null) }
}
