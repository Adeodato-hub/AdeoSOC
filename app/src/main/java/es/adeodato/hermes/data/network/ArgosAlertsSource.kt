package es.adeodato.hermes.data.network

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val JSON = "application/json; charset=utf-8".toMediaType()

class ArgosApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Fuente de datos de alertas de ARGOS. Dos implementaciones intercambiables
 * SIN tocar ni el modelo (AlertaCruda) ni la UI -- ambas devuelven la misma
 * forma cruda de hits.hits[] de OpenSearch:
 *
 *  - [IndexerBasicAuthSource]: el modelo OBJETIVO. Basic Auth directo contra
 *    el Indexer (puerto 9200), con un usuario de solo lectura (rol readall).
 *  - [DashboardProxySource]: atajo de DESARROLLO. Login + cookie de sesion +
 *    proxy interno del Dashboard, usado solo porque hoy el 9200 esta
 *    bloqueado desde el host (ver docs/paso0-api-wazuh.md). No usar en
 *    produccion.
 *
 * Cual se usa lo decide [ArgosAlertsSourceFactory] segun la config guardada
 * (interruptor "usar proxy del Dashboard" en la pantalla de Ajustes) -- para
 * conmutar de una a otra no hace falta tocar ni una linea mas.
 */
interface ArgosAlertsSource {
    /** Hasta [size] alertas mas recientes, forma cruda hits.hits[] (con _index, _id, _source). */
    fun fetchRecentAlertHits(size: Int): List<JSONObject>

    /**
     * Query independiente del feed principal, con su propia ventana y filtro
     * server-side (rule.groups/rule.level...). Usada por los chips OT/Criticas
     * de Alertas: una alerta critica NO puede desaparecer solo porque un
     * aluvion de ruido IT la empuje fuera del lote de [fetchRecentAlertHits]
     * (ver caso real: alerta OT 100201 enterrada por el escaneo FIM inicial de
     * un agente nuevo). [filtroExtra], si se da, es una clausula de query DSL
     * de OpenSearch que se combina en AND con el rango de tiempo.
     */
    fun fetchAlertHits(size: Int, windowMinutes: Int, filtroExtra: JSONObject? = null): List<JSONObject>
}

// Ventana de tiempo del filtro server-side del feed principal: 24h, acotada
// por el propio limite de tamano (size=200 en todos los llamadores) -- no un
// cap estricto de minutos. Con un cap corto (30 min), tras reducir el ruido
// IT/PAM, "Todas"/"IT" se quedaban vacios sin que hubiera ningun problema real
// (ver informe de la sesion: 0 hits reales en 30 min, 313 en 180 min). El
// indice esta particionado por dia (wazuh-alerts-4.x-YYYY.MM.DD), asi que una
// ventana de 24h solo toca 1-2 indices diarios -- no es mas cara que 30 min
// para OpenSearch, y "now-Nm" lo sigue resolviendo el propio Indexer con SU
// reloj (evita cualquier desfase del reloj del movil o de un agente, ver
// docs/paso-*.md).
private const val VENTANA_RECIENTE_MIN = 24 * 60

/** Filtros de query DSL reutilizables para [ArgosAlertsSource.fetchAlertHits]. */
object FiltrosOpenSearch {
    /** rule.groups="ot" (grupo garantizado por las reglas 100200-100208) O data.integration="argos-ot". */
    fun grupoOt(): JSONObject = JSONObject().apply {
        put("bool", JSONObject().apply {
            put(
                "should",
                JSONArray()
                    .put(JSONObject().apply { put("term", JSONObject().apply { put("rule.groups", "ot") }) })
                    .put(JSONObject().apply { put("match_phrase", JSONObject().apply { put("data.integration", "argos-ot") }) })
            )
            put("minimum_should_match", 1)
        })
    }

    /** rule.level >= nivel. */
    fun nivelMinimo(nivel: Int): JSONObject = JSONObject().apply {
        put("range", JSONObject().apply {
            put("rule.level", JSONObject().apply { put("gte", nivel) })
        })
    }
}

private fun buildQueryBody(size: Int, windowMinutes: Int = VENTANA_RECIENTE_MIN, filtroExtra: JSONObject? = null): String {
    val clausulas = JSONArray().put(
        JSONObject().apply {
            put("range", JSONObject().apply {
                put("timestamp", JSONObject().apply { put("gte", "now-${windowMinutes}m") })
            })
        }
    )
    filtroExtra?.let { clausulas.put(it) }
    return JSONObject().apply {
        put("size", size)
        put("sort", JSONArray().put(JSONObject().apply {
            put("timestamp", JSONObject().apply { put("order", "desc") })
        }))
        put("query", JSONObject().apply {
            put("bool", JSONObject().apply { put("must", clausulas) })
        })
    }.toString()
}

private fun extractHits(responseBody: String): List<JSONObject> {
    val root = JSONObject(responseBody)
    val hitsArray = root.optJSONObject("hits")?.optJSONArray("hits") ?: JSONArray()
    return (0 until hitsArray.length()).mapNotNull { hitsArray.optJSONObject(it) }
}

/**
 * Login contra el Dashboard (backend "basicauth" de OpenSearch Dashboards,
 * POST /auth/login), compartido entre [DashboardProxySource] y
 * [es.adeodato.hermes.data.network.DashboardProxyEnrichmentSource] -- ambos
 * usan el mismo proxy interno hacia el Indexer.
 */
internal fun loginDashboard(base: String, config: ArgosConfig, client: OkHttpClient): String {
    val loginBody = JSONObject().apply {
        put("username", config.username)
        put("password", config.password)
    }.toString()
    val request = Request.Builder()
        .url("$base/auth/login")
        .header("osd-xsrf", "true")
        .post(loginBody.toRequestBody(JSON))
        .build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw ArgosApiException("Login del Dashboard respondio ${response.code}: credenciales invalidas o URL incorrecta")
        }
        val setCookie = response.headers("Set-Cookie")
            .firstOrNull { it.startsWith("security_authentication=") }
            ?: throw ArgosApiException("Login OK pero sin cookie de sesion en la respuesta")
        return setCookie.substringBefore(";")
    }
}

/** Modelo objetivo: Basic Auth directo contra el Indexer (OpenSearch REST, típicamente :9200). */
class IndexerBasicAuthSource(private val config: ArgosConfig) : ArgosAlertsSource {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .confiarEnCertificadoAutofirmado()
        .build()

    override fun fetchRecentAlertHits(size: Int): List<JSONObject> =
        fetchAlertHits(size, VENTANA_RECIENTE_MIN, null)

    override fun fetchAlertHits(size: Int, windowMinutes: Int, filtroExtra: JSONObject?): List<JSONObject> {
        val url = config.baseUrl.trimEnd('/') + "/wazuh-alerts-*/_search"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(config.username, config.password))
            .post(buildQueryBody(size, windowMinutes, filtroExtra).toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ArgosApiException("Indexer respondio ${response.code}: ${body.take(300)}")
            }
            return extractHits(body)
        }
    }
}

/**
 * Atajo de DESARROLLO: se autentica contra el Dashboard (backend "basicauth"
 * de OpenSearch Dashboards, POST /auth/login) y reutiliza su proxy interno
 * (/api/console/proxy) hacia el Indexer. Solo mientras el puerto 9200 no sea
 * alcanzable desde el host de desarrollo.
 */
class DashboardProxySource(private val config: ArgosConfig) : ArgosAlertsSource {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .confiarEnCertificadoAutofirmado()
        .build()

    override fun fetchRecentAlertHits(size: Int): List<JSONObject> =
        fetchAlertHits(size, VENTANA_RECIENTE_MIN, null)

    override fun fetchAlertHits(size: Int, windowMinutes: Int, filtroExtra: JSONObject?): List<JSONObject> {
        val base = config.baseUrl.trimEnd('/')
        val cookie = loginDashboard(base, config, client)
        val proxyUrl = "$base/api/console/proxy?path=wazuh-alerts-*/_search&method=GET"
        val request = Request.Builder()
            .url(proxyUrl)
            .header("osd-xsrf", "true")
            .header("Cookie", cookie)
            .post(buildQueryBody(size, windowMinutes, filtroExtra).toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ArgosApiException("Proxy del Dashboard respondio ${response.code}: ${body.take(300)}")
            }
            return extractHits(body)
        }
    }
}

object ArgosAlertsSourceFactory {
    fun create(config: ArgosConfig): ArgosAlertsSource =
        if (config.useDashboardProxy) DashboardProxySource(config) else IndexerBasicAuthSource(config)
}
