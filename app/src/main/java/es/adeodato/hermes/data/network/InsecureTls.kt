package es.adeodato.hermes.data.network

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * ARGOS usa los certificados autofirmados que trae por defecto la OVA de
 * Wazuh (Indexer y Dashboard). Este TrustManager acepta cualquier
 * certificado -- es una chapuza DELIBERADA Y DOCUMENTADA de laboratorio
 * casero (ver docs/paso0-api-wazuh.md, seccion "Decisiones de arquitectura").
 *
 * TODO antes de sacar la app de la red domestica: sustituir por pinning del
 * certificado real de ARGOS (o de una CA propia), no confiar en cualquiera.
 */
internal fun OkHttpClient.Builder.confiarEnCertificadoAutofirmado(): OkHttpClient.Builder {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    }
    sslSocketFactory(sslContext.socketFactory, trustAll)
    hostnameVerifier(HostnameVerifier { _, _ -> true })
    return this
}
