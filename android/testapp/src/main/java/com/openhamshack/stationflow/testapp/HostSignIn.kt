package com.openhamshack.stationflow.testapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.openhamshack.core.data.OpenHamShackApp
import com.openhamshack.core.data.OpenHamShackDirectory
import com.openhamshack.core.net.BoxAddress
import com.openhamshack.core.net.BoxTrust
import com.openhamshack.core.net.SessionCookie
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A stand-in for the host's half: what the OpenHamShack edition of Dock360 will
 * do (sign in, hand over a client that carries the session). StationFlow has
 * no app sign-in of its own (TV-API.md section 2: the JSON login and
 * api/auth were withdrawn); what it has today is its web page's
 * ACCESS_PASSWORD form login and cookie, so that is what this uses -
 * exactly as the page does - until the platform's device tokens exist.
 *
 * Deliberately outside :kind-stationflow: the kind is handed a base URL, a
 * client and a level, and never signs in or discovers anything itself.
 */
object HostSignIn {
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** The client a kind is given: the host's trust for this box, and its session on every request and socket. */
    fun clientFor(session: SessionCookie?, trustedCa: String?): OkHttpClient {
        val trusted = BoxTrust.client(base, trustedCa)
        if (session == null) return trusted
        return trusted.newBuilder().addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Cookie", session.header).build())
        }).build()
    }

    /**
     * Whether the box needs signing in: `GET api/state` with what this device
     * holds. 401 (or 1.23.0's redirect to its login page) says it does.
     * Throws when nothing, or not StationFlow, answers.
     */
    suspend fun needsPassword(address: BoxAddress, trustedCa: String?, session: SessionCookie?): Boolean = withContext(Dispatchers.IO) {
        val http = clientFor(session, trustedCa).newBuilder().followRedirects(false).build()
        http.newCall(Request.Builder().url(address.url("api/state")).build()).execute().use { r ->
            when {
                r.code == 401 -> true
                r.isRedirect && r.header("Location").orEmpty().contains("login") -> true
                r.isSuccessful -> false
                else -> throw IOException("HTTP ${r.code} from ${address.display}")
            }
        }
    }

    /**
     * The module's own form login: `POST api/login` form-encoded, answered by
     * a redirect - to the page with a cookie, or back to `login?bad=1`.
     */
    suspend fun login(address: BoxAddress, trustedCa: String?, password: String): SessionCookie = withContext(Dispatchers.IO) {
        val http = BoxTrust.client(base, trustedCa).newBuilder().followRedirects(false).build()
        val body = FormBody.Builder().add("password", password).build()
        http.newCall(Request.Builder().url(address.url("api/login")).post(body).build()).execute().use { r ->
            SessionCookie.fromSetCookie(r.headers("Set-Cookie"), setOf("icom9700"))
                ?: throw IOException(
                    if (r.header("Location").orEmpty().contains("bad")) "Wrong password"
                    else "Sign-in refused (HTTP ${r.code})")
        }
    }

    /** A platform server's app list (open, PLATFORM-PLAN section 2.1). */
    suspend fun directory(server: BoxAddress, path: String): List<OpenHamShackApp> = withContext(Dispatchers.IO) {
        base.newCall(Request.Builder().url(server.url(path)).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            OpenHamShackDirectory.parse(server, JSONObject(r.body?.string().orEmpty()))
        }
    }

    /**
     * Debug builds only: where fakeflow --tls serves its CA, because an
     * emulator cannot reach port 80 on its host (that is the real Dock360,
     * whose CA must not be used) nor bind it on itself. Set at launch with
     * `am start ... --ei caPort 18380`.
     */
    @Volatile var caPortOverride: Int = 80

    /** The CA a Dock360 host serves, for the operator to compare and accept. */
    suspend fun fetchCa(address: BoxAddress) = withContext(Dispatchers.IO) { BoxTrust.fetchCa(address, base, caPortOverride) }
}
