package es.adeodato.hermes.data.network

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val JSON = "application/json; charset=utf-8".toMediaType()

/** Credenciales del usuario API dedicado adeosoc_ar (rol restringido a active-response:command) y URL de la API de Wazuh (puerto 55000). */
data class ActiveResponseConfig(
    val wazuhApiUrl: String,
    val username: String,
    val password: String
) {
    val isComplete: Boolean
        get() = wazuhApiUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

/**
 * Dispara Active Response contra la API nativa de Wazuh (puerto 55000, directo
 * por Tailscale -- a diferencia del Indexer/9200 este puerto no tiene el
 * problema de bloqueo NAT documentado en docs/paso0-api-wazuh.md, asi que no
 * hace falta la variante "proxy del Dashboard" que usan ArgosAlertsSource y
 * EnrichmentSource).
 *
 * Flujo verificado manualmente el 2026-07-24 (agente 001):
 *  1. POST /security/user/authenticate?raw=true con Basic Auth -> JWT (~15 min
 *     de vida). No se persiste: se pide uno nuevo en cada bloqueo, evitando
 *     tener que gestionar caducidad/renovacion para una accion que el usuario
 *     dispara a mano y no con frecuencia.
 *  2. PUT /active-response?agents_list=<agentId> con Bearer <jwt> y comando
 *     "!firewall-drop" -> genera una regla DROP <srcIp> en el iptables del
 *     agente indicado.
 *
 * Solo cubre IT: las alertas OT (AlertaCruda.isOt) no tienen un agente Wazuh
 * real en el activo (el que decodifica el log OT es siempre "wazuh-server"),
 * asi que este comando no tiene sentido ahi -- el aislamiento de activos OT es
 * un flujo aparte con confirmacion humana explicita, pendiente (punto D del
 * roadmap). AlertDetailScreen oculta el boton cuando isOt es true.
 */
class ActiveResponseSource(private val config: ActiveResponseConfig) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .confiarEnCertificadoAutofirmado()
        .build()

    /** Prueba solo la autenticacion (Basic Auth -> JWT) sin disparar ningun comando. Lanza ArgosApiException si falla. */
    fun probarAutenticacion() {
        authenticate()
    }

    private fun authenticate(): String {
        val base = config.wazuhApiUrl.trimEnd('/')
        val request = Request.Builder()
            .url("$base/security/user/authenticate?raw=true")
            .header("Authorization", Credentials.basic(config.username, config.password))
            .post("".toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ArgosApiException("Autenticacion API Wazuh respondio ${response.code}: ${body.take(300)}")
            }
            return body.trim()
        }
    }

    /**
     * Ejecuta !firewall-drop en [agentId] para bloquear [srcIp]. Lanza
     * ArgosApiException si la autenticacion o el Active Response fallan, o si
     * la API devuelve error!=0 (p.ej. agente desconectado).
     */
    fun blockIp(agentId: String, srcIp: String) {
        dispararComando(agentId, "!firewall-drop", "srcip", srcIp)
    }

    /**
     * Ejecuta el comando ESTANDAR !disable-account (no el custom -- verificado
     * en el servidor el 2026-07-31: los AR custom no se ejecutan al invocarlos
     * por API, solo los del ruleset base de Wazuh) en [agentId] para bloquear
     * la cuenta [dstuser] (usermod -L). El binario del AR espera el usuario en
     * el campo JSON "dstuser", no "srcuser" -- confirmado extrayendo las
     * cadenas del binario compilado disable-account.
     */
    fun disableAccount(agentId: String, dstuser: String) {
        dispararComando(agentId, "!disable-account", "dstuser", dstuser)
    }

    /** Autentica y dispara [command] en [agentId] con un unico campo de datos en alert.data. Lanza ArgosApiException si falla o si la API devuelve error!=0. */
    private fun dispararComando(agentId: String, command: String, campoDatos: String, valorDatos: String) {
        val jwt = authenticate()
        val base = config.wazuhApiUrl.trimEnd('/')
        val bodyJson = JSONObject().apply {
            put("command", command)
            put("alert", JSONObject().apply {
                put("data", JSONObject().apply { put(campoDatos, valorDatos) })
            })
        }.toString()
        val request = Request.Builder()
            .url("$base/active-response?agents_list=$agentId")
            .header("Authorization", "Bearer $jwt")
            .put(bodyJson.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ArgosApiException("Active Response respondio ${response.code}: ${body.take(300)}")
            }
            val error = JSONObject(body).optInt("error", -1)
            if (error != 0) {
                throw ArgosApiException("Active Response devolvio error=$error: ${body.take(300)}")
            }
        }
    }
}
