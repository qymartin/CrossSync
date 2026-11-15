package io.github.qymartin.crosssync.garmin

import android.content.Context
import android.net.Uri
import io.github.qymartin.crosssync.core.AuthRequiredException
import io.github.qymartin.crosssync.core.DailyValue
import io.github.qymartin.crosssync.core.Metric
import io.github.qymartin.crosssync.core.Settings
import io.github.qymartin.crosssync.core.Source
import io.github.qymartin.crosssync.core.TemporaryException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class GarminSource(context: Context) : Source {
    override val id = "garmin"

    private val auth = GarminAuth(context)
    private val settings = Settings(context)

    override fun isReady() = auth.isLoggedIn()

    override suspend fun fetch(metric: Metric, date: LocalDate): DailyValue? = when (metric) {
        Metric.TOTAL_CALORIES -> {
            val day = dailySummary(date)
            if (day.optBoolean("privacyProtected", false)) throw AuthRequiredException("Garmin 拒绝访问数据，请重新登录")
            if (!day.has("totalKilocalories") || day.isNull("totalKilocalories")) null
            else DailyValue(metric, date, day.getDouble("totalKilocalories"), offsetFor(day, date))
        }
    }

    /** Fetches and caches the account's displayName, needed in most API paths. */
    suspend fun displayName(): String {
        settings.displayName?.let { return it }
        val name = get("/userprofile-service/socialProfile").optString("displayName")
        if (name.isEmpty()) throw TemporaryException("Garmin 未返回用户名")
        settings.displayName = name
        return name
    }

    private suspend fun dailySummary(date: LocalDate): JSONObject =
        get("/usersummary-service/usersummary/daily/${Uri.encode(displayName())}?calendarDate=$date")

    /** Garmin reports both local and GMT start of the day; their gap is the day's UTC offset. */
    private fun offsetFor(day: JSONObject, date: LocalDate): ZoneOffset = try {
        val local = LocalDateTime.parse(day.getString("wellnessStartTimeLocal").take(19))
        val gmt = LocalDateTime.parse(day.getString("wellnessStartTimeGmt").take(19))
        ZoneOffset.ofTotalSeconds(Duration.between(gmt, local).seconds.toInt())
    } catch (e: Exception) {
        ZoneId.systemDefault().rules.getOffset(date.atStartOfDay())
    }

    private suspend fun get(path: String): JSONObject {
        var resp = call(path, auth.accessToken())
        if (resp.first == 401) resp = call(path, auth.refresh())
        val (code, body) = resp
        return when {
            code in 200..299 -> if (body.isBlank()) JSONObject() else JSONObject(body)
            code == 401 -> throw AuthRequiredException("Garmin 登录已失效，请重新登录")
            code == 429 -> throw TemporaryException("Garmin 限流（429），稍后自动重试")
            code == 403 -> throw TemporaryException("Garmin 拒绝了请求（403），可能是临时拦截")
            else -> throw TemporaryException("Garmin 返回错误（HTTP $code）")
        }
    }

    private suspend fun call(path: String, token: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(GarminConfig.API + path).get().apply {
            GarminConfig.nativeHeaders().forEach { (k, v) -> header(k, v) }
            header("Authorization", "Bearer $token")
            header("Accept", "application/json")
        }.build()
        try {
            Http.client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IOException) {
            throw TemporaryException("无法连接 Garmin：${e.message}", e)
        }
    }
}
