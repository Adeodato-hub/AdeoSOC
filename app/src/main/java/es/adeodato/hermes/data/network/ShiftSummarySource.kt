package es.adeodato.hermes.data.network

import es.adeodato.hermes.data.model.ShiftSummary
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private const val SUMMARY_INDEX = "argos-shift-summary"

private fun buildLatestQuery(): String = JSONObject().apply {
    put("size", 1)
    put("sort", JSONArray().put(JSONObject().apply {
        put("generado_en", JSONObject().apply { put("order", "desc") })
    }))
}.toString()

private fun parseLatest(responseBody: String): ShiftSummary? {
    val root = JSONObject(responseBody)
    val hits = root.optJSONObject("hits")?.optJSONArray("hits") ?: return null
    if (hits.length() == 0) return null
    val source = hits.optJSONObject(0)?.optJSONObject("_source") ?: return null
    return ShiftSummary.fromSource(source)
}

/**
 * Trae el ULTIMO resumen de turno de argos-shift-summary (ordenado por
 * generado_en desc, size 1) -- no hace falta adivinar el _id (la fecha) desde
 * el cliente, evita cualquier lio de zona horaria entre el movil y el
 * servidor. Null si el indice aun no existe o no tiene documentos (caso
 * "Sin resumen todavia" en la UI).
 */
interface ShiftSummarySource {
    fun fetchLatest(): ShiftSummary?
}

class IndexerBasicAuthShiftSummarySource(private val config: ArgosConfig) : ShiftSummarySource {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .confiarEnCertificadoAutofirmado()
        .build()

    override fun fetchLatest(): ShiftSummary? {
        val url = config.baseUrl.trimEnd('/') + "/$SUMMARY_INDEX/_search"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(config.username, config.password))
            .post(buildLatestQuery().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ArgosApiException("Indexer (resumen) respondio ${response.code}: ${body.take(300)}")
            }
            return parseLatest(body)
        }
    }
}

/** Atajo de DESARROLLO: mismo proxy del Dashboard que [DashboardProxySource]/[DashboardProxyEnrichmentSource]. */
class DashboardProxyShiftSummarySource(private val config: ArgosConfig) : ShiftSummarySource {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .confiarEnCertificadoAutofirmado()
        .build()

    override fun fetchLatest(): ShiftSummary? {
        val base = config.baseUrl.trimEnd('/')
        val cookie = loginDashboard(base, config, client)
        // Barras literales en "path" -- el proxy exige POST en si mismo
        // (ver EnrichmentSource.kt: un GET real al proxy da 404 generico).
        val proxyUrl = "$base/api/console/proxy?path=$SUMMARY_INDEX/_search&method=GET"
        val request = Request.Builder()
            .url(proxyUrl)
            .header("osd-xsrf", "true")
            .header("Cookie", cookie)
            .post(buildLatestQuery().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ArgosApiException("Proxy del Dashboard (resumen) respondio ${response.code}: ${body.take(300)}")
            }
            return parseLatest(body)
        }
    }
}

object ShiftSummarySourceFactory {
    fun create(config: ArgosConfig): ShiftSummarySource =
        if (config.useDashboardProxy) DashboardProxyShiftSummarySource(config) else IndexerBasicAuthShiftSummarySource(config)
}
