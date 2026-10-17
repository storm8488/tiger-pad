package com.damou.tigertouch.net

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/*
 * The network clients of the app. Two kinds of trust:
 *  - the Internet (Firebase, the makers' clouds): the system's CAs, as usual;
 *  - the printers on the LAN: their certificates are self-signed and name no
 *    host we could check, so any certificate is accepted. That is what Tiger
 *    Studio and the firmware do too; the link stays encrypted, not authenticated.
 */
object Net {
    /** Accepts every certificate. LAN printers only. */
    val trustAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val trustAllHosts = HostnameVerifier { _, _ -> true }

    /** An SSL socket factory that accepts every certificate: for MQTT over TLS to a LAN printer. */
    val lanSslSocketFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        ctx.socketFactory
    }

    /** For Firebase and the makers' clouds. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** For printers on the LAN: short timeouts, any certificate, WebSocket pings. */
    val lanHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(lanSslSocketFactory, trustAll)
            .hostnameVerifier(trustAllHosts)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
