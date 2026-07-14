package es.adeodato.hermes.data.network

/** Configuracion de conexion a ARGOS, independiente de como se guarde (ver CredentialStore). */
data class ArgosConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    val useDashboardProxy: Boolean
)
