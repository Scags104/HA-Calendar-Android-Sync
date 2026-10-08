package io.hacalsync

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import io.hacalsync.ha.UrlPolicy

/**
 * Opens Home Assistant for events from our calendars. Reached two ways:
 *
 * 1. ACTION_HANDLE_CUSTOM_EVENT: the "Open in…" link calendar apps like Etar show on our events.
 * 2. ACTION_VIEW on a calendar event: what launchers (e.g. Niagara) and widgets send when you
 *    tap an event. Events from our HA calendars open Home Assistant; every other event is
 *    passed on unchanged to the user's normal calendar app.
 *
 * Only enabled while the "Open in Home Assistant" option is on (MainActivity toggles the
 * component), so when it's off this app never appears in the "Open with" list.
 *
 * Security: the incoming intent is only used to look up which calendar the event belongs to.
 * This screen can only open the user's own HA (app or saved URL) or forward a genuine
 * calendar event link to another calendar app. It never opens arbitrary links.
 */
class OpenInHaActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val am = AccountManager.get(this)
        val account = am.getAccountsByType(Const.ACCOUNT_TYPE).firstOrNull()
        val enabled = account != null && am.getUserData(account, Const.KEY_OPEN_IN_HA) == "1"

        val openHa = enabled && (
            intent.action == CalendarContract.ACTION_HANDLE_CUSTOM_EVENT || isOurEvent(intent.data)
        )

        if (openHa && account != null) {
            val path = runCatching { UrlPolicy.checkPath(am.getUserData(account, Const.KEY_OPEN_PATH)) }
                .getOrDefault(Const.DEFAULT_OPEN_PATH)
            openHomeAssistant(am.getUserData(account, Const.KEY_BASE_URL), path)
        } else if (intent.action == Intent.ACTION_VIEW) {
            forwardToCalendarApp()
        }
        finish()   // Theme.NoDisplay requires finishing before onResume
    }

    // ---------------------------------------------------------------- which calendar?

    /** Event id from content://com.android.calendar/events/<id>, or null for anything else. */
    private fun eventId(uri: Uri?): Long? {
        if (uri == null || uri.scheme != "content" || uri.authority != CalendarContract.AUTHORITY) return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "events") return null
        return segments[1].toLongOrNull()
    }

    private fun isOurEvent(uri: Uri?): Boolean {
        val id = eventId(uri) ?: return false
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            contentResolver.query(
                ContentUris.withAppendedId(Events.CONTENT_URI, id),
                arrayOf(Events.ACCOUNT_TYPE), null, null, null,
            )?.use { c -> c.moveToFirst() && c.getString(0) == Const.ACCOUNT_TYPE } ?: false
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- open Home Assistant

    /** [path] has already been validated by UrlPolicy.checkPath (always "/..." on the user's HA). */
    private fun openHomeAssistant(baseUrl: String?, path: String) {
        // 1) The Home Assistant Companion app, on the chosen page.
        if (tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("homeassistant://navigate$path")))) return

        // 2) Fallback: the same page on the user's own HA URL, in the browser.
        val root = baseUrl?.let { runCatching { UrlPolicy.check(it) }.getOrNull() } ?: return
        val pathPart = path.substringBefore('?').trim('/')
        val query = path.substringAfter('?', "").ifEmpty { null }
        val b = root.newBuilder()
        if (pathPart.isNotEmpty()) b.addEncodedPathSegments(pathPart)
        b.encodedQuery(query)
        tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(b.build().toString())))
    }

    // ---------------------------------------------------------------- everything else

    /** Hands a non-HA event to the user's normal calendar app, never back to us. */
    private fun forwardToCalendarApp() {
        val uri = intent.data
        if (eventId(uri) == null) return   // only forward genuine calendar event links

        // Fresh intent: only the event link and its time extras, nothing else from the caller.
        val forward = Intent(Intent.ACTION_VIEW, uri)
        for (key in listOf(CalendarContract.EXTRA_EVENT_BEGIN_TIME, CalendarContract.EXTRA_EVENT_END_TIME)) {
            if (intent.hasExtra(key)) forward.putExtra(key, intent.getLongExtra(key, 0L))
        }
        if (intent.hasExtra(CalendarContract.EXTRA_EVENT_ALL_DAY)) {
            forward.putExtra(
                CalendarContract.EXTRA_EVENT_ALL_DAY,
                intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false),
            )
        }

        val others = packageManager.queryIntentActivities(forward, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }
            .filter { it.packageName != packageName }

        when (others.size) {
            0 -> return
            1 -> {
                forward.setClassName(others[0].packageName, others[0].name)
                tryStart(forward)
            }
            else -> {
                val chooser = Intent.createChooser(forward, "Open event with")
                    .putExtra(
                        Intent.EXTRA_EXCLUDE_COMPONENTS,
                        arrayOf(ComponentName(this, OpenInHaActivity::class.java)),
                    )
                tryStart(chooser)
            }
        }
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
