package com.androidtv.gameswidget.net

import com.androidtv.gameswidget.crypto.CryptoProvider
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.Proxy
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Talks the NVIDIA GameStream HTTP(S) protocol to a single host.
 * Ported from moonlight-android's NvHTTP (only the bits this widget needs).
 *
 * Plaintext HTTP (port 47989) is used for serverinfo before pairing and for the
 * /pair handshake. Everything after pairing (applist, appasset, pairchallenge)
 * goes over mutual-TLS HTTPS (port 47984) with the server cert pinned.
 */
class GameStreamHttp(
    val host: String,
    private val httpPort: Int,
    httpsPort: Int,
    private val crypto: CryptoProvider,
) {
    // Shared across all Moonlight clients so we can quit/launch each other's sessions.
    private val uniqueId = "0123456789ABCDEF"

    @Volatile
    var serverCert: X509Certificate? = null

    @Volatile
    var httpsPort: Int = if (httpsPort != 0) httpsPort else DEFAULT_HTTPS_PORT

    private val baseHttp: HttpUrl =
        HttpUrl.Builder().scheme("http").host(host).port(httpPort).build()

    private fun baseHttps(): HttpUrl =
        HttpUrl.Builder().scheme("https").host(host).port(httpsPort).build()

    // ---- TLS plumbing ------------------------------------------------------

    private val keyManager = object : X509KeyManager {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "client"
        override fun getCertificateChain(alias: String?) = arrayOf(crypto.clientCertificate)
        override fun getPrivateKey(alias: String?): PrivateKey = crypto.clientPrivateKey
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }

    private val defaultTrustManager: X509TrustManager = run {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    // Trust CA-signed certs normally; otherwise accept exactly our pinned server cert.
    private val pinningTrustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw IllegalStateException("Should never be called")

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
            try {
                defaultTrustManager.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                val pinned = serverCert
                if (chain.size == 1 && pinned != null) {
                    if (chain[0] != pinned) throw CertificateException("Certificate mismatch")
                } else {
                    throw e
                }
            }
        }
    }

    private val baseClient = OkHttpClient.Builder()
        // Some hosts close the socket after each response, so disable connection
        // reuse — otherwise the 2nd request in a session hangs on a dead socket.
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .connectTimeout(LONG_CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
        .proxy(Proxy.NO_PROXY)
        .hostnameVerifier { _, session ->
            // Accept any hostname for our pinned self-signed cert.
            val pinned = serverCert
            val peers = runCatching { session.peerCertificates }.getOrNull()
            if (pinned != null && peers != null && peers.size == 1 && peers[0] == pinned) true
            else javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
        }
        .build()

    // A fresh SSL socket factory each call avoids the SSLv3 fallback bug (per moonlight).
    private fun tlsClient(noReadTimeout: Boolean = false): OkHttpClient {
        val sc = SSLContext.getInstance("TLS")
        sc.init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(pinningTrustManager), SecureRandom())
        var b = baseClient.newBuilder().sslSocketFactory(sc.socketFactory, pinningTrustManager)
        if (noReadTimeout) b = b.readTimeout(0, TimeUnit.MILLISECONDS)
        return b.build()
    }

    private fun completeUrl(base: HttpUrl, path: String, query: String?): HttpUrl =
        base.newBuilder()
            .addPathSegment(path)
            .query(query)
            .addQueryParameter("uniqueid", uniqueId)
            .addQueryParameter("uuid", UUID.randomUUID().toString())
            .build()

    private fun get(base: HttpUrl, path: String, query: String? = null, noReadTimeout: Boolean = false): String {
        val url = completeUrl(base, path, query)
        val req = Request.Builder().url(url).get().build()
        tlsClient(noReadTimeout).newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HostHttpException(resp.code, resp.message)
            return body
        }
    }

    private fun getBytes(base: HttpUrl, path: String, query: String?): ByteArray {
        val url = completeUrl(base, path, query)
        val req = Request.Builder().url(url).get().build()
        tlsClient().newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) throw HostHttpException(resp.code, resp.message)
            return bytes
        }
    }

    // ---- High-level endpoints ---------------------------------------------

    /** serverinfo over plaintext HTTP (used before/while pairing). */
    fun serverInfoHttp(): String = get(baseHttp, "serverinfo")

    /** serverinfo over HTTPS (reports an accurate PairStatus once paired). */
    fun serverInfoHttps(): String = get(baseHttps(), "serverinfo")

    fun getComputerDetails(serverInfo: String): ComputerDetails {
        val parsedHttps = xmlString(serverInfo, "HttpsPort")?.toIntOrNull()
        if (parsedHttps != null && parsedHttps != 0) httpsPort = parsedHttps
        return ComputerDetails(
            name = xmlString(serverInfo, "hostname")?.takeIf { it.isNotEmpty() } ?: "UNKNOWN",
            uuid = xmlString(serverInfo, "uniqueid") ?: error("Missing uniqueid"),
            httpsPort = parsedHttps ?: DEFAULT_HTTPS_PORT,
            appVersion = xmlString(serverInfo, "appversion") ?: error("Missing appversion"),
            paired = xmlString(serverInfo, "PairStatus") == "1",
        )
    }

    fun executePairingCommand(args: String, enableReadTimeout: Boolean): String =
        get(baseHttp, "pair", "devicename=roth&updateState=1&$args", noReadTimeout = !enableReadTimeout)

    fun executePairingChallenge(): String =
        get(baseHttps(), "pair", "devicename=roth&updateState=1&phrase=pairchallenge")

    fun unpair(): String = get(baseHttp, "unpair")

    fun getAppListRaw(): String = get(baseHttps(), "applist")

    fun getBoxArt(appId: Int): ByteArray =
        getBytes(baseHttps(), "appasset", "appid=$appId&AssetType=2&AssetIdx=0")

    // ---- XML helpers (ported from NvHTTP) ---------------------------------

    companion object {
        const val DEFAULT_HTTP_PORT = 47989
        const val DEFAULT_HTTPS_PORT = 47984
        private const val LONG_CONNECT_TIMEOUT = 5000L
        private const val READ_TIMEOUT = 7000L

        /** First text value of [tag] anywhere in the document, or null. */
        fun xmlString(xml: String, tag: String): String? {
            val xpp = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            xpp.setInput(StringReader(xml))
            val stack = ArrayDeque<String>()
            var ev = xpp.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> stack.addLast(xpp.name)
                    XmlPullParser.END_TAG -> stack.removeLastOrNull()
                    XmlPullParser.TEXT -> if (stack.lastOrNull() == tag) return xpp.text
                }
                ev = xpp.next()
            }
            return null
        }

        fun parseAppList(xml: String): List<NvApp> {
            val xpp = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            xpp.setInput(StringReader(xml))
            val stack = ArrayDeque<String>()
            val out = mutableListOf<MutableNvApp>()
            var ev = xpp.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        stack.addLast(xpp.name)
                        if (xpp.name == "App") out.add(MutableNvApp())
                    }
                    XmlPullParser.END_TAG -> stack.removeLastOrNull()
                    XmlPullParser.TEXT -> {
                        val cur = out.lastOrNull()
                        if (cur != null) when (stack.lastOrNull()) {
                            "AppTitle" -> cur.name = xpp.text
                            "ID" -> cur.id = xpp.text.trim().toIntOrNull()
                            "IsHdrSupported" -> cur.hdr = xpp.text == "1"
                        }
                    }
                }
                ev = xpp.next()
            }
            return out.filter { it.id != null && it.name != null }
                .map { NvApp(it.id!!, it.name!!, it.hdr) }
        }
    }

    private class MutableNvApp {
        var id: Int? = null
        var name: String? = null
        var hdr: Boolean = false
    }
}

class HostHttpException(val code: Int, msg: String?) : java.io.IOException("HTTP $code: $msg")
