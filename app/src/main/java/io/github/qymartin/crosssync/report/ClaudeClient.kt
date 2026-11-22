package io.github.qymartin.crosssync.report

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The API key was rejected: ask for it again, do not retry. */
class KeyInvalidException(message: String) : Exception(message)
/** Worth retrying later (network, overload, a malformed answer). */
class RetryableException(message: String) : Exception(message)
/** Retrying will not help (e.g. no credit left, bad request). */
class PermanentException(message: String) : Exception(message)

/**
 * Talks to the Claude API. No web search; the model is the newest Sonnet on the account.
 * Only the averages and key numbers built in [ReportPeriods.payload] are sent.
 */
class ClaudeClient(private val apiKey: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** The newest Sonnet the API lists (newest first), or the built-in fallback. */
    fun latestSonnet(): String {
        val req = base("https://api.anthropic.com/v1/models?limit=100").get().build()
        return try {
            http.newCall(req).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw keyInvalid()
                if (!r.isSuccessful) return FALLBACK_MODEL
                val data = JSONObject(r.body!!.string()).optJSONArray("data") ?: return FALLBACK_MODEL
                (0 until data.length()).map { data.getJSONObject(it).optString("id") }
                    .firstOrNull { it.startsWith("claude-sonnet") } ?: FALLBACK_MODEL
            }
        } catch (e: IOException) {
            FALLBACK_MODEL
        }
    }

    fun write(model: String, system: String, userText: String): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("system", system)
            .put("tools", JSONArray().put(TOOL))
            .put("tool_choice", JSONObject().put("type", "tool").put("name", TOOL_NAME))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userText)))
        val req = base("https://api.anthropic.com/v1/messages")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = try {
            http.newCall(req).execute().use { r -> check(r); r.body!!.string() }
        } catch (e: IOException) {
            throw RetryableException("网络不可用（${e.message ?: e.javaClass.simpleName}）")
        }
        val root = JSONObject(text)
        if (root.optString("stop_reason") == "max_tokens") throw RetryableException("回复被截断")
        val content = root.optJSONArray("content") ?: throw RetryableException("回复格式不对")
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            if (c.optString("type") == "tool_use" && c.optString("name") == TOOL_NAME) return c.getJSONObject("input")
        }
        throw RetryableException("回复里没有报告")
    }

    private fun base(url: String) = Request.Builder().url(url)
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")

    private fun keyInvalid() = KeyInvalidException("API 密钥无效或已被撤销，请重新填写")

    private fun check(r: Response) {
        if (r.isSuccessful) return
        val msg = try {
            JSONObject(r.body!!.string()).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) {
            ""
        }
        when {
            r.code == 401 || r.code == 403 -> throw keyInvalid()
            r.code == 429 || r.code >= 500 -> throw RetryableException("Claude 服务繁忙（HTTP ${r.code}）")
            msg.contains("credit", ignoreCase = true) ->
                throw PermanentException("API 余额不足，请在 Anthropic Console 充值后重试")
            else -> throw PermanentException("请求失败（HTTP ${r.code}）${if (msg.isNotEmpty()) "：$msg" else ""}")
        }
    }

    companion object {
        const val FALLBACK_MODEL = "claude-sonnet-5-5"
        private const val TOOL_NAME = "write_report"
        val GRADES = listOf("良好", "一般", "需改善")
        val THEMES = listOf("睡眠", "活动与恢复", "体重与体成分")

        private val TOOL: JSONObject = JSONObject(
            """
            {
              "name": "$TOOL_NAME",
              "description": "提交写好的健康报告。",
              "input_schema": {
                "type": "object",
                "properties": {
                  "grade": {"type": "string", "enum": ["良好", "一般", "需改善"], "description": "整体健康程度评级"},
                  "direction": {"type": "string", "description": "和上一期相比的变化方向，一句短语，例如“较前 3 周略有下降”"},
                  "summary": {"type": "string", "description": "综合评价正文：整体健康程度、变化方向、目前对健康影响最大的一个方面"},
                  "findings": {
                    "type": "array", "minItems": 1, "maxItems": 3,
                    "items": {
                      "type": "object",
                      "properties": {
                        "title": {"type": "string", "description": "一句话标题"},
                        "body": {"type": "string", "description": "关系或规律，以及它的健康含义"}
                      },
                      "required": ["title", "body"]
                    }
                  },
                  "goals": {
                    "type": "array", "minItems": 1, "maxItems": 3,
                    "items": {
                      "type": "object",
                      "properties": {
                        "theme": {"type": "string", "enum": ["睡眠", "活动与恢复", "体重与体成分"]},
                        "action": {"type": "string", "description": "具体行动"},
                        "target": {"type": "string", "description": "可衡量的目标"}
                      },
                      "required": ["theme", "action", "target"]
                    }
                  }
                },
                "required": ["grade", "direction", "summary", "findings", "goals"]
              }
            }
            """.trimIndent()
        )

        /** The writing requirements confirmed with the user (2026-11-22). */
        fun systemPrompt(p: Period, age: Int, sex: String, ethnicity: String): String {
            val next = when (p.kind) {
                ReportKind.WEEK -> "下周"
                ReportKind.MONTH -> "下个月"
                ReportKind.YEAR -> "未来 12 个月"
            }
            val length = when (p.kind) {
                ReportKind.WEEK -> 300
                ReportKind.MONTH -> 400
                ReportKind.YEAR -> 600
            }
            val sexText = if (sex == "female") "女性" else "男性"
            val ethText = if (ethnicity == "asian") "亚洲人" else "族裔未指定"
            return """
                你是一名严谨的健康数据分析助手，为用户撰写${p.kind.label}。
                用户：$sexText，${age}岁，$ethText。这些信息只作分析参考，不要在报告中复述。
                你只会收到${p.unit}和本地算好的关键数字。需要引用数字时直接用提供的数值，不要自己重新计算。

                1. 综合评价：先给出评级（良好 / 一般 / 需改善，三选一），再说明和上一期相比是改善、持平还是下降，最后指出目前对健康影响最大的一个方面。
                2. 主要发现：写 2–3 条，重点写指标之间的关系和规律，以及它们对健康意味着什么。不要逐周罗列数字。
                3. ${next}目标与行动：按「睡眠」「活动与恢复」「体重与体成分」分组。每组写一条「行动」和一个可衡量的「目标」，不写理由。不需要改进的组可以省略。
                4. 尽量少用数字，只保留支撑结论的关键数字。
                5. 评价性的结论必须有权威、高质量的科学依据。由你自己判断选用最合适的来源，比如国际或亚洲专业机构的指南、共识或大型研究，并在句子里写出机构或研究的名称。不得编造数据、研究或引用；没有把握时宁可不引用。
                6. 不得单方面假设或推测。如果某个判断必须依赖假设，就标注「（推测）」并写明依据。
                7. 不讨论设备准确性，不写数据提醒，不写数据缺失说明。
                8. 用中文写，全文约 $length 字。
                9. 用 write_report 工具提交报告。
            """.trimIndent()
        }
    }
}
