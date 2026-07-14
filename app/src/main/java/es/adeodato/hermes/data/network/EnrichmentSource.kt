package es.adeodato.hermes.data.network

import android.util.Log
import es.adeodato.hermes.data.model.optStringOrNull
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val TAG = "EnrichmentSource"

/** Resultado del enriquecimiento IA de ARGOS (indice argos-ai-enrichment, PASO 2a). */
data class EnrichmentData(
    val aiAnalysis: String,
    val aiSeverityLabel: String?,
    val aiMitre: String?
)

/**
 * Consulta el documento de enriquecimiento IA de UNA alerta por su id
 * (join 1:1: el _id del documento en argos-ai-enrichment es el mismo id que
 * la alerta en wazuh-alerts-*). Mismas dos implementaciones intercambiables
 * que [ArgosAlertsSource] y por la misma razon (ver docs/paso0-api-wazuh.md).
 *
 * Devuelve null si no hay enriquecimiento (severidad Baja -- nunca se
 * enriquece -- o Ollama aun generando/timeout/regla filtrada); nunca lanza
 * por un simple "no encontrado".
 */
interface EnrichmentSource {
    fun fetchEnrichment(alertId: String): EnrichmentData?
}

private const val ENRICHMENT_INDEX = "argos-ai-enrichment"

private fun parseEnrichment(responseBody: String): EnrichmentData? {
    val root = JSONObject(responseBody)
    if (!root.optBoolean("found", true)) return null
    val source = root.optJSONObject("_source") ?: return null
    val analisis = source.optStringOrNull("ai_analysis") ?: return null
    return EnrichmentData(
        aiAnalysis = analisis,
        aiSeverityLabel = source.optStringOrNull("ai_severity_label"),
        aiMitre = source.optStringOrNull("ai_mitre")
    )
}

/** Modelo objetivo: Basic Auth directo contra el Indexer. */
class IndexerBasicAuthEnrichmentSource(private val config: ArgosConfig) : EnrichmentSource {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .confiarEnCertificadoAutofirmado()
        .build()

    override fun fetchEnrichment(alertId: String): EnrichmentData? {
        val url = config.baseUrl.trimEnd('/') + "/$ENRICHMENT_INDEX/_doc/" + URLEncoder.encode(alertId, "UTF-8")
        Log.d(TAG, "IndexerBasicAuth: GET $url")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(config.username, config.password))
            .build()
        client.newCall(request).execute().use { response ->
            Log.d(TAG, "IndexerBasicAuth: respuesta ${response.code} para alertId=$alertId")
            if (response.code == 404) return null
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "IndexerBasicAuth: error ${response.code}: ${body.take(300)}")
                throw ArgosApiException("Indexer (enriquecimiento) respondio ${response.code}: ${body.take(300)}")
            }
            return parseEnrichment(body)
        }
    }
}

/** Atajo de DESARROLLO: mismo proxy del Dashboard que [DashboardProxySource]. */
class DashboardProxyEnrichmentSource(private val config: ArgosConfig) : EnrichmentSource {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .confiarEnCertificadoAutofirmado()
        .build()

    override fun fetchEnrichment(alertId: String): EnrichmentData? {
        val base = config.baseUrl.trimEnd('/')
        val cookie = loginDashboard(base, config, client)
        // OJO: el proxy del Dashboard espera la ruta con barras LITERALES en
        // el parametro "path" (igual que DashboardProxySource.fetchRecentAlertHits,
        // que usa "wazuh-alerts-*/_search" sin codificar). Codificarla con
        // URLEncoder convierte "/" en "%2F" y el proxy siempre devuelve 404.
        val proxyUrl = "$base/api/console/proxy?path=$ENRICHMENT_INDEX/_doc/$alertId&method=GET"
        Log.d(TAG, "DashboardProxy: POST $proxyUrl (alertId=$alertId)")
        // El endpoint /api/console/proxy del Dashboard SOLO acepta POST en si
        // mismo -- "method=GET" en la query es solo el verbo que reenvia
        // internamente a OpenSearch. Un GET real aqui da 404 generico del
        // propio Kibana/OSD, no de OpenSearch (confirmado con curl directo).
        val request = Request.Builder()
            .url(proxyUrl)
            .header("osd-xsrf", "true")
            .header("Cookie", cookie)
            .post("".toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            Log.d(TAG, "DashboardProxy: respuesta ${response.code} para alertId=$alertId")
            if (response.code == 404) return null
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "DashboardProxy: error ${response.code}: ${body.take(300)}")
                throw ArgosApiException("Proxy del Dashboard (enriquecimiento) respondio ${response.code}: ${body.take(300)}")
            }
            Log.d(TAG, "DashboardProxy: body=${body.take(300)}")
            return parseEnrichment(body)
        }
    }
}

object EnrichmentSourceFactory {
    fun create(config: ArgosConfig): EnrichmentSource =
        if (config.useDashboardProxy) DashboardProxyEnrichmentSource(config) else IndexerBasicAuthEnrichmentSource(config)
}
