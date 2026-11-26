package com.sleepclub.app

import com.sleepclub.core.MemberStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

class ApiException(val code: Int, message: String) : IOException(message)

data class JoinResult(
    val token: String, val memberId: Long, val name: String, val groupName: String,
    val inviteCode: String, val isOwner: Boolean,
)

/** Положение в игре: в игре / вылетел / победитель. */
data class GameState(val state: String, val at: Long?, val reason: String?)

data class MemberRow(
    val id: Long, val name: String, val isMe: Boolean, val isOwner: Boolean,
    val status: MemberStatus, val game: GameState,
    /** Местное время участника: смещение от UTC в минутах и название пояса (Europe/Moscow). */
    val tzOffsetMin: Int? = null, val tzId: String? = null,
    /** curfew — у него сейчас отбой; pre — меньше 40 минут до отбоя; day — день. */
    val phase: String = "day",
)

data class MembersInfo(val members: List<MemberRow>, val round: Int, val inNight: Boolean, val roundInfo: RoundInfo?)

/** Испытание: длина в ночах, сколько прошло, итог. finishKind: time | manual | all_out. */
data class RoundInfo(
    val no: Int,
    val nights: Int,
    val nightsDone: Int,
    val endsAt: Long?,
    val finishedAt: Long?,
    val finishKind: String?,
    val winners: List<String>,
) {
    val finished get() = finishedAt != null
}

data class SessionRow(val pkg: String, val name: String, val start: Long, val end: Long)

data class Timeline(
    val member: MemberRow,
    val screens: List<Pair<Long, Long>>,
    val used: List<Pair<Long, Long>>,
    val nights: List<Pair<Long, Long>>,
    val sessions: List<SessionRow>,
    val totals: List<Pair<String, Long>>,
)

data class GroupSettings(
    val bedtime: String,
    val wake: String,
    val maxSessionS: Int,
    val nightTotalS: Int,
    val forbiddenS: Int,
    val forbiddenCategories: Set<String>,
    val forbiddenApps: Set<String>,
    val allowedApps: Set<String>,
    val days: Set<Int>,
    val trackingOffEliminates: Boolean,
    val roundNights: Int,
    val ownAppS: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("bedtime", bedtime).put("wake", wake)
        .put("max_session_s", maxSessionS).put("night_total_s", nightTotalS).put("forbidden_s", forbiddenS)
        .put("forbidden_categories", JSONArray(forbiddenCategories.toList()))
        .put("forbidden_apps", JSONArray(forbiddenApps.toList()))
        .put("allowed_apps", JSONArray(allowedApps.toList()))
        .put("days", JSONArray(days.sorted()))
        .put("tracking_off_eliminates", trackingOffEliminates)
        .put("round_nights", roundNights)
        .put("own_app_s", ownAppS)

    companion object {
        fun from(o: JSONObject) = GroupSettings(
            bedtime = o.getString("bedtime"),
            wake = o.getString("wake"),
            maxSessionS = o.getInt("max_session_s"),
            nightTotalS = o.getInt("night_total_s"),
            forbiddenS = o.getInt("forbidden_s"),
            forbiddenCategories = o.getJSONArray("forbidden_categories").strings().toSet(),
            forbiddenApps = o.getJSONArray("forbidden_apps").strings().toSet(),
            allowedApps = o.getJSONArray("allowed_apps").strings().toSet(),
            days = o.getJSONArray("days").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() },
            trackingOffEliminates = o.optBoolean("tracking_off_eliminates", true),
            roundNights = o.optInt("round_nights", 7),
            ownAppS = o.optInt("own_app_s", 300),
        )
    }
}

data class GroupInfo(
    val name: String,
    val inviteCode: String,
    val isOwner: Boolean,
    val ownerName: String?,
    val settings: GroupSettings,
    val rules: List<String>,
    val categories: List<Pair<String, String>>,
    val inNight: Boolean,
    val bedMs: Long?,
    val wakeMs: Long?,
    val round: RoundInfo,
    /** Ближайший (или идущий) отбой — с ним работает кнопка «Отменить отбой». */
    val cancelTarget: NightTarget?,
)

data class NightTarget(val bedMs: Long, val wakeMs: Long, val cancelled: Boolean)

data class AppRule(
    val pkg: String, val name: String, val categories: List<String>,
    val locked: Boolean, val forbidden: Boolean, val allowed: Boolean,
)

/** Минимальный HTTP-клиент без сторонних библиотек (меньше APK и зависимостей). */
class Api(private val baseUrl: String, private val token: String?) {

    suspend fun join(invite: String, name: String): JoinResult {
        val body = JSONObject().put("invite_code", invite).put("name", name)
            .put("tz_offset_min", tzOffsetNow()).put("tz_id", java.util.TimeZone.getDefault().id)
        return registration(request("POST", "/api/v1/join", body))
    }

    suspend fun createGroup(groupName: String, name: String, tzOffsetMin: Int): JoinResult {
        val body = JSONObject().put("group_name", groupName).put("name", name).put("tz_offset_min", tzOffsetMin)
            .put("tz_id", java.util.TimeZone.getDefault().id)
        return registration(request("POST", "/api/v1/groups", body))
    }

    suspend fun leave() {
        request("POST", "/api/v1/leave", JSONObject())
    }

    suspend fun upload(payload: JSONObject): JSONObject =
        request("POST", "/api/v1/upload", payload, gzip = true)

    suspend fun members(): MembersInfo {
        val r = request("GET", "/api/v1/members")
        return MembersInfo(
            r.getJSONArray("members").objects().map { memberRow(it) },
            r.optInt("round", 1),
            r.optJSONObject("night")?.optBoolean("in_night") ?: false,
            r.optJSONObject("round_info")?.let { roundInfo(it) },
        )
    }

    suspend fun group(): GroupInfo = groupInfo(request("GET", "/api/v1/group"))

    suspend fun updateGroup(name: String?, tzOffsetMin: Int?, settings: GroupSettings?): GroupInfo {
        val body = JSONObject()
        name?.let { body.put("group_name", it) }
        tzOffsetMin?.let { body.put("tz_offset_min", it) }
        settings?.let { body.put("settings", it.toJson()) }
        return groupInfo(request("PUT", "/api/v1/group", body))
    }

    suspend fun newRound(): GroupInfo = groupInfo(request("POST", "/api/v1/group/new-round", JSONObject()))

    suspend fun finishRound(): GroupInfo = groupInfo(request("POST", "/api/v1/group/finish", JSONObject()))

    suspend fun cancelNight(): GroupInfo = groupInfo(request("POST", "/api/v1/group/cancel-night", JSONObject()))

    suspend fun restoreNight(): GroupInfo = groupInfo(request("POST", "/api/v1/group/restore-night", JSONObject()))

    suspend fun newInvite(): GroupInfo = groupInfo(request("POST", "/api/v1/group/new-invite", JSONObject()))

    /** action: kick | allow-rejoin | make-owner */
    suspend fun memberAction(memberId: Long, action: String) {
        request("POST", "/api/v1/group/members/$memberId/$action", JSONObject())
    }

    suspend fun groupApps(): List<AppRule> =
        request("GET", "/api/v1/group/apps").getJSONArray("apps").objects().map {
            AppRule(it.getString("pkg"), it.getString("name"), it.getJSONArray("categories").strings(),
                it.optBoolean("locked"), it.optBoolean("forbidden"), it.optBoolean("allowed"))
        }

    suspend fun timeline(memberId: Long, start: Long, end: Long): Timeline {
        val r = request("GET", "/api/v1/members/$memberId/timeline?start=$start&end=$end")
        val m = r.getJSONObject("member")
        fun pairs(key: String) = r.optJSONArray(key)?.let { a ->
            (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getLong(0) to p.getLong(1) } }
        } ?: emptyList()
        val sessions = r.getJSONArray("sessions").objects().map {
            SessionRow(it.getString("pkg"), it.getString("name"), it.getLong("start"), it.getLong("end"))
        }
        val totals = r.getJSONArray("totals").let { a ->
            (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getLong(1) } }
        }
        return Timeline(
            MemberRow(m.getLong("id"), m.getString("name"), false, false, status(m.getJSONObject("status")),
                game(m.optJSONObject("game"))),
            pairs("screens"), pairs("used"), pairs("nights"), sessions, totals,
        )
    }

    private fun registration(r: JSONObject) = JoinResult(
        r.getString("token"), r.getLong("member_id"), r.getString("name"), r.getString("group_name"),
        r.optString("invite_code"), r.optBoolean("is_owner"),
    )

    private fun groupInfo(o: JSONObject): GroupInfo {
        val night = o.getJSONObject("night")
        val cats = o.getJSONObject("categories")
        return GroupInfo(
            name = o.getString("name"),
            inviteCode = o.getString("invite_code"),
            isOwner = o.optBoolean("is_owner"),
            ownerName = o.optStringOrNull("owner_name"),
            settings = GroupSettings.from(o.getJSONObject("settings")),
            rules = o.getJSONArray("rules").strings(),
            categories = cats.keys().asSequence().map { it to cats.getString(it) }.toList(),
            inNight = night.optBoolean("in_night"),
            bedMs = night.optLongOrNull("bed_ms"),
            wakeMs = night.optLongOrNull("wake_ms"),
            round = roundInfo(o.getJSONObject("round")),
            cancelTarget = night.optJSONObject("cancel_target")?.let {
                NightTarget(it.getLong("bed_ms"), it.getLong("wake_ms"), it.optBoolean("cancelled"))
            },
        )
    }

    private fun roundInfo(o: JSONObject) = RoundInfo(
        no = o.getInt("no"),
        nights = o.optInt("nights", 7),
        nightsDone = o.optInt("nights_done"),
        endsAt = o.optLongOrNull("ends_at"),
        finishedAt = o.optLongOrNull("finished_at"),
        finishKind = o.optStringOrNull("finish_kind"),
        winners = o.optJSONArray("winners")?.strings() ?: emptyList(),
    )

    private fun memberRow(o: JSONObject) = MemberRow(
        o.getLong("id"), o.getString("name"), o.optBoolean("is_me"), o.optBoolean("is_owner"),
        status(o.getJSONObject("status")), game(o.optJSONObject("game")),
        tzOffsetMin = o.optJSONObject("tz")?.let { if (it.isNull("offset_min")) null else it.getInt("offset_min") },
        tzId = o.optJSONObject("tz")?.optStringOrNull("id"),
        phase = o.optString("phase", "day"),
    )

    private fun game(o: JSONObject?) =
        if (o == null) GameState("in", null, null)
        else GameState(o.getString("state"), o.optLongOrNull("at"), o.optStringOrNull("reason"))

    private fun status(o: JSONObject) = MemberStatus(
        state = o.getString("state"),
        reason = o.optStringOrNull("reason"),
        appName = o.optStringOrNull("app_name"),
        sinceMs = o.optLongOrNull("since_ms"),
        lastUploadMs = o.optLongOrNull("last_upload_ms"),
    )

    private suspend fun request(method: String, path: String, json: JSONObject? = null, gzip: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("ngrok-skip-browser-warning", "1")
                conn.setRequestProperty("User-Agent", "Bayushki/${BuildConfig.VERSION_NAME}")
                token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (json != null) {
                    val raw = json.toString().toByteArray()
                    val data = if (gzip) gzip(raw) else raw
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (gzip) conn.setRequestProperty("Content-Encoding", "gzip")
                    conn.setFixedLengthStreamingMode(data.size)
                    conn.outputStream.use { it.write(data) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                    throw ApiException(code, detail?.takeIf { it.isNotBlank() && !it.startsWith("[") }
                        ?: "Ошибка сервера ($code)")
                }
                JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }
}

fun tzOffsetNow(): Int = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
