package io.github.qymartin.crosssync.garmin

import android.content.Context
import android.net.Uri
import android.util.Base64
import io.github.qymartin.crosssync.core.AuthRequiredException
import io.github.qymartin.crosssync.core.SecureStore
import io.github.qymartin.crosssync.core.TemporaryException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Garmin's 2026 auth: sign in on Garmin's own mobile SSO page (in a WebView, like
 * the official app), receive a CAS service ticket, and exchange it for a "DI"
 * OAuth2 access token + refresh token. Everything Garmin-specific lives here.
 */
object GarminConfig {
    const val SSO = "https://sso.garmin.com"
    const val CLIENT_ID = "GCM_ANDROID_DARK"
    const val SERVICE_URL = "https://mobile.integration.garmin.com/gcm/android"
    const val API = "https://connectapi.garmin.com"
    const val DI_TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"
    const val DI_GRANT = "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"
    val DI_CLIENT_IDS = listOf(
        "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
        "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
        "GARMIN_CONNECT_MOBILE_ANDROID_DI",
    )

    val signInUrl: String =
        "$SSO/mobile/sso/en_US/sign-in?clientId=$CLIENT_ID&service=${Uri.encode(SERVICE_URL)}"

    /** Headers Garmin's API tier expects from its Android app. */
    fun nativeHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "GCM-Android-5.23",
        "X-Garmin-User-Agent" to "com.garmin.android.apps.connectmobile/5.23; ; Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0",
        "X-Garmin-Paired-App-Version" to "10861",
        "X-Garmin-Client-Platform" to "Android",
        "X-App-Ver" to "10861",
        "X-Lang" to "en",
        "X-GCExperience" to "GC5",
        "Accept-Language" to "en-US,en;q=0.9",
    )
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

class GarminAuth(context: Context) {
    private val store = SecureStore(context, "garmin")

    fun isLoggedIn() = store.get(KEY_REFRESH) != null

    fun logout() = store.clear()

    /** Called by [LoginActivity] once the SSO page hands back a ticket. */
    suspend fun exchangeTicket(ticket: String) = withContext(Dispatchers.IO) {
        var lastStatus = 0
        for (clientId in GarminConfig.DI_CLIENT_IDS) {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("service_ticket", ticket)
                .add("grant_type", GarminConfig.DI_GRANT)
                .add("service_url", GarminConfig.SERVICE_URL)
                .build()
            tokenRequest(clientId, body).use { resp ->
                lastStatus = resp.code
                if (resp.code == 429) throw TemporaryException("Garmin 限流（429），请稍后再登录")
                if (resp.isSuccessful) {
                    saveTokens(JSONObject(resp.body!!.string()), clientId)
                    return@withContext
                }
            }
        }
        throw IOException("Garmin 令牌交换失败（HTTP $lastStatus）")
    }

    /** A usable access token, refreshing it first if it expires within 15 minutes. */
    suspend fun accessToken(): String = mutex.withLock {
        val token = store.get(KEY_ACCESS)
        if (token != null && expiresAt(token) - System.currentTimeMillis() / 1000 > 900) token
        else refreshLocked()
    }

    /** Force a refresh (after the API rejected the current token). */
    suspend fun refresh(): String = mutex.withLock { refreshLocked() }

    private suspend fun refreshLocked(): String = withContext(Dispatchers.IO) {
        val refresh = store.get(KEY_REFRESH) ?: throw AuthRequiredException("尚未登录 Garmin")
        val clientId = store.get(KEY_CLIENT) ?: GarminConfig.DI_CLIENT_IDS.first()
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refresh)
            .build()
        tokenRequest(clientId, body).use { resp ->
            when {
                resp.isSuccessful -> saveTokens(JSONObject(resp.body!!.string()), clientId)
                resp.code == 400 || resp.code == 401 ->
                    throw AuthRequiredException("Garmin 登录已失效，请重新登录")
                else -> throw TemporaryException("刷新 Garmin 令牌失败（HTTP ${resp.code}）")
            }
        }
    }

    private fun tokenRequest(clientId: String, body: FormBody) = try {
        val auth = "Basic " + Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP)
        val req = Request.Builder().url(GarminConfig.DI_TOKEN_URL).post(body).apply {
            GarminConfig.nativeHeaders().forEach { (k, v) -> header(k, v) }
            header("Authorization", auth)
            header("Accept", "application/json")
            header("Cache-Control", "no-cache")
        }.build()
        Http.client.newCall(req).execute()
    } catch (e: IOException) {
        throw TemporaryException("无法连接 Garmin：${e.message}", e)
    }

    private fun saveTokens(json: JSONObject, clientId: String): String {
        val access = json.getString("access_token")
        store.put(KEY_ACCESS, access)
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { store.put(KEY_REFRESH, it) }
        store.put(KEY_CLIENT, jwtClaim(access, "client_id") ?: clientId)
        return access
    }

    private fun expiresAt(jwt: String): Long = jwtClaim(jwt, "exp")?.toLongOrNull() ?: 0

    private fun jwtClaim(jwt: String, name: String): String? = try {
        val payload = jwt.split(".")[1]
        val json = JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        if (json.has(name)) json.get(name).toString() else null
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_CLIENT = "client"
        val mutex = Mutex()
    }
}
