package io.hacalsync.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.SyncResult
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import io.hacalsync.Const
import io.hacalsync.ha.HaCalendar
import io.hacalsync.ha.HaClient
import io.hacalsync.ha.DebugLog
import io.hacalsync.ha.HaCommandException
import io.hacalsync.ha.HaEvent
import io.hacalsync.ha.HaWebSocket
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.TimeZone

/**
 * Column usage on Android event rows we own:
 *   _SYNC_ID    = HaEvent.key (uid|recurrence_id), null for rows created on the phone
 *   SYNC_DATA1  = HA recurrence_id
 *   SYNC_DATA2  = HA uid (null if the integration doesn't provide one -> not editable)
 *   SYNC_DATA3  = content hash of the last version pulled from HA ("" forces a refresh)
 * Calendar rows:
 *   _SYNC_ID / NAME = HA entity_id, CAL_SYNC1 = supported_features
 */
class CalendarSync(
    private val account: Account,
    private val provider: ContentProviderClient,
    private val client: HaClient,
    private val syncResult: SyncResult,
) {
    private val calUri = Calendars.CONTENT_URI.asSyncAdapter()
    private val evUri = Events.CONTENT_URI.asSyncAdapter()
    private var ws: HaWebSocket? = null

    private fun socket(): HaWebSocket = ws ?: client.openWebSocket().also { ws = it }

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
        .build()

    fun run(selected: Set<String>) {
        try {
            // Throws on network/auth failure before anything local is touched.
            val remote = client.listCalendars().filter { it.entityId in selected }
            val calIds = reconcileCalendars(remote)

            val now = Instant.now()
            val start = now.minus(Const.PAST_DAYS, ChronoUnit.DAYS)
            val end = now.plus(Const.FUTURE_DAYS, ChronoUnit.DAYS)

            for (cal in remote) {
                val calId = calIds[cal.entityId] ?: continue
                pushLocalChanges(cal, calId)   // phone -> HA first, so the pull sees the result
                pullEvents(cal, calId, start, end)
            }
        } finally {
            runCatching { ws?.close() }
        }
    }

    // ---------------------------------------------------------------- calendars

    private fun reconcileCalendars(remote: List<HaCalendar>): Map<String, Long> {
        val existing = mutableMapOf<String, Long>()
        provider.query(
            calUri, arrayOf(Calendars._ID, Calendars._SYNC_ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?",
            arrayOf(account.name, account.type), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val syncId = c.getString(1)
                if (syncId == null || syncId in existing) {
                    deleteCalendar(c.getLong(0))   // junk/duplicate row
                } else {
                    existing[syncId] = c.getLong(0)
                }
            }
        }

        val wanted = remote.associateBy { it.entityId }

        // Deselected or removed in HA: drop the calendar (cascades to its events).
        for ((entityId, id) in existing) if (entityId !in wanted) deleteCalendar(id)

        val result = mutableMapOf<String, Long>()
        for (cal in remote) {
            val values = ContentValues().apply {
                put(Calendars.CALENDAR_DISPLAY_NAME, cal.name)
                put(Calendars.CALENDAR_ACCESS_LEVEL, if (cal.writable) Calendars.CAL_ACCESS_OWNER else Calendars.CAL_ACCESS_READ)
                put(Calendars.CAL_SYNC1, cal.features.toString())
                put(Calendars.SYNC_EVENTS, 1)
            }
            val id = existing[cal.entityId]
            if (id != null) {
                provider.update(calUri, values, "${Calendars._ID}=?", arrayOf(id.toString()))
                result[cal.entityId] = id
            } else {
                values.apply {
                    put(Calendars.ACCOUNT_NAME, account.name)
                    put(Calendars.ACCOUNT_TYPE, account.type)
                    put(Calendars.NAME, cal.entityId)
                    put(Calendars._SYNC_ID, cal.entityId)
                    put(Calendars.OWNER_ACCOUNT, account.name)
                    put(Calendars.CALENDAR_COLOR, colorFor(cal.entityId))
                    put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
                    put(Calendars.VISIBLE, 1)
                }
                val uri = provider.insert(calUri, values) ?: continue
                result[cal.entityId] = ContentUris.parseId(uri)
            }
        }
        return result
    }

    private fun deleteCalendar(id: Long) {
        provider.delete(calUri, "${Calendars._ID}=?", arrayOf(id.toString()))
    }

    private fun colorFor(entityId: String): Int {
        val palette = intArrayOf(
            0xFF039BE5.toInt(), 0xFF33B679.toInt(), 0xFFE67C73.toInt(), 0xFFF6BF26.toInt(),
            0xFF8E24AA.toInt(), 0xFFF4511E.toInt(), 0xFF7986CB.toInt(), 0xFF0B8043.toInt(),
        )
        return palette[Math.floorMod(entityId.hashCode(), palette.size)]
    }

    // ---------------------------------------------------------------- push (phone -> HA)

    private data class LocalEvent(
        val id: Long,
        val syncId: String?,
        val uid: String?,
        val recurrenceId: String?,
        val deleted: Boolean,
        val title: String?,
        val description: String?,
        val location: String?,
        val dtStart: Long,
        val dtEnd: Long?,
        val duration: String?,
        val allDay: Boolean,
        val timezone: String?,
        val rrule: String?,
    )

    private fun Cursor.str(col: String): String? =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.lng(col: String): Long? =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }

    private fun pushLocalChanges(cal: HaCalendar, calId: Long) {
        val projection = arrayOf(
            Events._ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.DELETED,
            Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND,
            Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.RRULE,
        )
        val rows = mutableListOf<LocalEvent>()
        provider.query(
            evUri, projection,
            "${Events.CALENDAR_ID}=? AND (${Events.DIRTY}=1 OR ${Events.DELETED}=1)",
            arrayOf(calId.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                rows += LocalEvent(
                    id = c.lng(Events._ID)!!,
                    syncId = c.str(Events._SYNC_ID),
                    recurrenceId = c.str(Events.SYNC_DATA1),
                    uid = c.str(Events.SYNC_DATA2),
                    deleted = c.lng(Events.DELETED) == 1L,
                    title = c.str(Events.TITLE),
                    description = c.str(Events.DESCRIPTION),
                    location = c.str(Events.EVENT_LOCATION),
                    dtStart = c.lng(Events.DTSTART) ?: continue,
                    dtEnd = c.lng(Events.DTEND),
                    duration = c.str(Events.DURATION),
                    allDay = c.lng(Events.ALL_DAY) == 1L,
                    timezone = c.str(Events.EVENT_TIMEZONE),
                    rrule = c.str(Events.RRULE),
                )
            }
        }

        for (ev in rows) {
            try {
                when {
                    // Created on the phone and deleted again before ever syncing.
                    ev.deleted && ev.syncId == null -> deleteRow(ev.id)

                    ev.deleted -> {
                        require(cal, Const.FEATURE_DELETE, "delete")
                        socket().call("calendar/event/delete", target(cal, ev))
                        deleteRow(ev.id)
                        syncResult.stats.numDeletes++
                    }

                    ev.syncId == null -> {
                        require(cal, Const.FEATURE_CREATE, "create")
                        socket().call(
                            "calendar/event/create",
                            JSONObject().put("entity_id", cal.entityId).put("event", toHaEvent(ev, includeRrule = true)),
                        )
                        // HA doesn't return the new uid, so drop the local draft;
                        // the pull below re-inserts the event with HA's identity.
                        deleteRow(ev.id)
                        syncResult.stats.numInserts++
                    }

                    else -> {
                        require(cal, Const.FEATURE_UPDATE, "update")
                        socket().call(
                            "calendar/event/update",
                            target(cal, ev).put("event", toHaEvent(ev, includeRrule = ev.recurrenceId == null)),
                        )
                        markCleanAndStale(ev.id)
                        syncResult.stats.numUpdates++
                    }
                }
            } catch (e: HaCommandException) {
                // HA refused the change. Discard it locally; the pull restores HA's version.
                DebugLog.w("Reverting local change to event ${ev.id}: ${e.message}")
                syncResult.stats.numSkippedEntries++
                if (ev.deleted || ev.syncId == null) deleteRow(ev.id) else markCleanAndStale(ev.id)
            }
        }
    }

    private fun require(cal: HaCalendar, feature: Int, what: String) {
        if (!cal.supports(feature)) throw HaCommandException("${cal.entityId} does not support $what")
    }

    private fun target(cal: HaCalendar, ev: LocalEvent): JSONObject {
        val uid = ev.uid ?: throw HaCommandException("event has no uid in HA; it can't be edited")
        return JSONObject().put("entity_id", cal.entityId).put("uid", uid).apply {
            ev.recurrenceId?.let { put("recurrence_id", it) }   // edits only this occurrence
        }
    }

    private fun toHaEvent(ev: LocalEvent, includeRrule: Boolean): JSONObject {
        val o = JSONObject().put("summary", ev.title?.takeIf { it.isNotBlank() } ?: "(No title)")
        var endMs = ev.dtEnd ?: (ev.dtStart + parseDurationMillis(ev.duration))

        if (ev.allDay) {
            val s = Instant.ofEpochMilli(ev.dtStart).atZone(ZoneOffset.UTC).toLocalDate()
            var e = Instant.ofEpochMilli(endMs).atZone(ZoneOffset.UTC).toLocalDate()
            if (!e.isAfter(s)) e = s.plusDays(1)   // both Android and HA use an exclusive end date
            o.put("dtstart", s.toString()).put("dtend", e.toString())
        } else {
            if (endMs <= ev.dtStart) endMs = ev.dtStart + 3_600_000L
            val zone = ev.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            o.put("dtstart", OffsetDateTime.ofInstant(Instant.ofEpochMilli(ev.dtStart), zone).format(fmt))
            o.put("dtend", OffsetDateTime.ofInstant(Instant.ofEpochMilli(endMs), zone).format(fmt))
        }
        ev.description?.takeIf { it.isNotBlank() }?.let { o.put("description", it) }
        ev.location?.takeIf { it.isNotBlank() }?.let { o.put("location", it) }
        if (includeRrule) ev.rrule?.takeIf { it.isNotBlank() }?.let { o.put("rrule", it.removePrefix("RRULE:")) }
        return o
    }

    /** Android stores recurring-event length as an RFC 2445 duration, e.g. "P3600S", "P1D", "PT1H". */
    private fun parseDurationMillis(d: String?): Long {
        if (d.isNullOrBlank()) return 3_600_000L
        var total = 0L
        var num = 0L
        for (ch in d.uppercase()) {
            when {
                ch.isDigit() -> num = num * 10 + (ch - '0')
                ch == 'W' -> { total += num * 604_800; num = 0 }
                ch == 'D' -> { total += num * 86_400; num = 0 }
                ch == 'H' -> { total += num * 3_600; num = 0 }
                ch == 'M' -> { total += num * 60; num = 0 }
                ch == 'S' -> { total += num; num = 0 }
            }
        }
        return if (total > 0) total * 1000 else 3_600_000L
    }

    private fun deleteRow(id: Long) {
        provider.delete(evUri, "${Events._ID}=?", arrayOf(id.toString()))
    }

    /** Clears DIRTY and blanks the hash so the next pull overwrites the row with HA's version. */
    private fun markCleanAndStale(id: Long) {
        val v = ContentValues().apply {
            put(Events.DIRTY, 0)
            put(Events.SYNC_DATA3, "")
        }
        provider.update(evUri, v, "${Events._ID}=?", arrayOf(id.toString()))
    }

    // ---------------------------------------------------------------- pull (HA -> phone)

    private fun pullEvents(cal: HaCalendar, calId: Long, start: Instant, end: Instant) {
        val remote = LinkedHashMap<String, HaEvent>()
        for (ev in client.events(cal.entityId, start, end)) remote[ev.key] = ev

        val ops = ArrayList<ContentProviderOperation>()
        val local = mutableMapOf<String, Pair<Long, String?>>()   // key -> (rowId, hash)

        provider.query(
            evUri, arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA3),
            "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID} IS NOT NULL",
            arrayOf(calId.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val key = c.getString(1) ?: continue
                if (key in local) ops += deleteOp(id) else local[key] = id to c.getString(2)
            }
        }

        for ((key, row) in local) {
            if (key !in remote) {
                ops += deleteOp(row.first)
                syncResult.stats.numDeletes++
            }
        }

        for ((key, ev) in remote) {
            val row = local[key]
            when {
                row == null -> {
                    ops += ContentProviderOperation.newInsert(evUri).withValues(eventValues(ev, calId)).build()
                    syncResult.stats.numInserts++
                }
                row.second != ev.contentHash -> {
                    ops += ContentProviderOperation.newUpdate(evUri)
                        .withSelection("${Events._ID}=?", arrayOf(row.first.toString()))
                        .withValues(eventValues(ev, calId))
                        .build()
                    syncResult.stats.numUpdates++
                }
            }
        }

        for (chunk in ops.chunked(200)) provider.applyBatch(ArrayList(chunk))
        syncResult.stats.numEntries += remote.size
    }

    private fun deleteOp(id: Long) = ContentProviderOperation.newDelete(evUri)
        .withSelection("${Events._ID}=?", arrayOf(id.toString()))
        .build()

    private fun eventValues(ev: HaEvent, calId: Long) = ContentValues().apply {
        put(Events.CALENDAR_ID, calId)
        put(Events.TITLE, ev.summary)
        put(Events.DESCRIPTION, ev.description)
        put(Events.EVENT_LOCATION, ev.location)
        put(Events.DTSTART, ev.startMillis)
        put(Events.DTEND, ev.endMillis)
        putNull(Events.DURATION)
        putNull(Events.RRULE)   // HA already expanded recurrences into instances
        put(Events.ALL_DAY, if (ev.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, if (ev.allDay) "UTC" else TimeZone.getDefault().id)
        put(Events.ORGANIZER, account.name)   // lets calendar apps treat events as editable
        put(Events._SYNC_ID, ev.key)
        put(Events.SYNC_DATA1, ev.recurrenceId)
        put(Events.SYNC_DATA2, ev.uid)
        put(Events.SYNC_DATA3, ev.contentHash)
        put(Events.DIRTY, 0)
    }
}
