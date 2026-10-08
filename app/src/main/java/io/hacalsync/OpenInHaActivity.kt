package io.hacalsync

import android.accounts.AccountManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.hacalsync.ha.UrlPolicy

/**
 * Launched by calendar apps when the user taps "Open in…" on one of our events.
 *
 * Security: the incoming intent (event URI, extras) is deliberately ignored. This screen can
 * only ever open the Home Assistant app, or the user's own saved HA URL in the browser, so
 * another app can't use it to open arbitrary links.
 */
class OpenInHaActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val am = AccountManager.get(this)
        val account = am.getAccountsByType(Const.ACCOUNT_TYPE).firstOrNull()
        if (account != null && am.getUserData(account, Const.KEY_OPEN_IN_HA) == "1") {
            val path = runCatching { UrlPolicy.checkPath(am.getUserData(account, Const.KEY_OPEN_PATH)) }
                .getOrDefault(Const.DEFAULT_OPEN_PATH)
            openHomeAssistant(am.getUserData(account, Const.KEY_BASE_URL), path)
        }
        finish()   // Theme.NoDisplay requires finishing before onResume
    }

    /** [path] has already been validated by UrlPolicy.checkPath (always "/..." on the user's HA). */
    private fun openHomeAssistant(baseUrl: String?, path: String) {
        // 1) The Home Assistant Companion app, on the chosen page.
        if (tryStart(Uri.parse("homeassistant://navigate$path"))) return

        // 2) Fallback: the same page on the user's own HA URL, in the browser.
        val root = baseUrl?.let { runCatching { UrlPolicy.check(it) }.getOrNull() } ?: return
        val pathPart = path.substringBefore('?').trim('/')
        val query = path.substringAfter('?', "").ifEmpty { null }
        val b = root.newBuilder()
        if (pathPart.isNotEmpty()) b.addEncodedPathSegments(pathPart)
        b.encodedQuery(query)
        tryStart(Uri.parse(b.build().toString()))
    }

    private fun tryStart(uri: Uri): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
