package io.hacalsync.ha

import io.hacalsync.Const
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** Token rejected or lacking permission. */
class HaAuthException(message: String) : IOException(message)

/** HA understood the request but refused it (validation error, unsupported feature, unknown uid...). */
class HaCommandException(message: String) : Exception(message)

data class HaCalendar(val entityId: String, val name: String, val features: Int) {
    fun supports(feature: Int): Boolean = (features and feature) != 0
    val writable: Boolean
        get() = (features and (Const.FEATURE_CREATE or Const.FEATURE_UPDATE or Const.FEATURE_DELETE)) != 0
}

/**
 * One event *instance* as returned by GET /api/calendars/<entity>.
 * HA expands recurring series into instances, each with its own recurrence_id.
 */
data class HaEvent(
    val uid: String?,
    val recurrenceId: String?,
    val summary: String,
    val description: String?,
    val location: String?,
    val allDay: Boolean,
    val startMillis: Long,
    val endMillis: Long,
) {
    /** Stable identity used as the Android _SYNC_ID. */
    val key: String
        get() = (uid ?: "nouid:$summary@$startMillis") + "|" + (recurrenceId ?: "")

    /** Detects remote changes without comparing every column. */
    val contentHash: String
        get() = listOf(summary, description, location, allDay, startMillis, endMillis)
            .joinToString("\u0001").hashCode().toString()
}

class HaClient(baseUrl: String, private val token: String) {

    val baseUrl: String = baseUrl.trim().trimEnd('/')

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun url(path: String): HttpUrl = "$baseUrl/$path".toHttpUrl()

    private fun get(url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { r ->
            if (r.code == 401 || r.code == 403) {
                throw HaAuthException("Home Assistant rejected the token (HTTP ${r.code})")
            }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} from ${url.encodedPath}")
            return r.body?.string().orEmpty()
        }
    }

    /** All calendar entities, with their supported_features from the state machine. */
    fun listCalendars(): List<HaCalendar> {
        val arr = JSONArray(get(url("api/calendars")))
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val id = c.getString("entity_id")
            val features = runCatching {
                JSONObject(get(url("api/states/$id")))
                    .optJSONObject("attributes")
                    ?.optInt("supported_features", 0) ?: 0
            }.getOrElse { e -> if (e is HaAuthException) throw e else 0 }
            HaCalendar(id, c.optString("name", id), features)
        }.sortedBy { it.name.lowercase() }
    }

    fun events(entityId: String, start: Instant, end: Instant): List<HaEvent> {
        val u = url("api/calendars/$entityId").newBuilder()
            .addQueryParameter("start", start.truncatedTo(ChronoUnit.SECONDS).toString())
            .addQueryParameter("end", end.truncatedTo(ChronoUnit.SECONDS).toString())
            .build()
        val arr = JSONArray(get(u))
        return (0 until arr.length()).mapNotNull { parseEvent(arr.getJSONObject(it)) }
    }

    fun openWebSocket(): HaWebSocket {
        val wsUrl = baseUrl.replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") + "/api/websocket"
        return HaWebSocket(http, wsUrl, token)
    }

    companion object {
        private fun JSONObject.str(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        /** Returns (epochMillis, isAllDay). All-day dates map to UTC midnight, as Android requires. */
        private fun parseTime(o: JSONObject): Pair<Long, Boolean> {
            o.str("dateTime")?.let { s ->
                val instant = runCatching { OffsetDateTime.parse(s).toInstant() }
                    .getOrElse { LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant() }
                return instant.toEpochMilli() to false
            }
            val d = LocalDate.parse(o.getString("date"))
            return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to true
        }

        fun parseEvent(o: JSONObject): HaEvent? = runCatching {
            val (start, allDay) = parseTime(o.getJSONObject("start"))
            val (endRaw, _) = parseTime(o.getJSONObject("end"))
            val end = if (endRaw > start) endRaw else start + if (allDay) 86_400_000L else 3_600_000L
            HaEvent(
                uid = o.str("uid"),
                recurrenceId = o.str("recurrence_id"),
                summary = o.str("summary") ?: "(No title)",
                description = o.str("description"),
                location = o.str("location"),
                allDay = allDay,
                startMillis = start,
                endMillis = end,
            )
        }.getOrNull()
    }
}
