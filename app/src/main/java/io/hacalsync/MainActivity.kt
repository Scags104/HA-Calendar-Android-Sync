package io.hacalsync

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.ComponentName
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import io.hacalsync.ha.HaCalendar
import io.hacalsync.ha.HaClient
import io.hacalsync.ha.UrlPolicy
import org.json.JSONArray
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val am by lazy { AccountManager.get(this) }

    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var calendarList: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var openInHaSwitch: Switch
    private lateinit var openPathGroup: View
    private lateinit var openPathField: EditText
    private val checkboxes = mutableListOf<Pair<String, CheckBox>>()

    private val authority = CalendarContract.AUTHORITY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the token screen out of screenshots, screen recordings and the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        urlField = findViewById(R.id.url)
        tokenField = findViewById(R.id.token)
        calendarList = findViewById(R.id.calendar_list)
        statusView = findViewById(R.id.status)
        openInHaSwitch = findViewById(R.id.open_in_ha)
        openPathGroup = findViewById(R.id.open_path_group)
        openPathField = findViewById(R.id.open_path)
        openInHaSwitch.setOnCheckedChangeListener { _, on ->
            openPathGroup.visibility = if (on) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.connect).setOnClickListener { loadCalendars() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.sync_now).setOnClickListener {
            existingAccount()?.let { requestSync(it); status("Sync requested") } ?: status("Save an account first")
        }
        findViewById<Button>(R.id.remove).setOnClickListener { removeAccount() }

        existingAccount()?.let { acc ->
            urlField.setText(am.getUserData(acc, Const.KEY_BASE_URL))
            openInHaSwitch.isChecked = am.getUserData(acc, Const.KEY_OPEN_IN_HA) == "1"
            openPathField.setText(am.getUserData(acc, Const.KEY_OPEN_PATH).orEmpty())
            // The saved token is never shown again; leaving the field blank keeps it.
            tokenField.hint = "Saved - leave blank to keep the current token"
            loadCalendars()
        }

        if (!hasCalendarPermission()) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        showSyncState()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun existingAccount(): Account? = am.getAccountsByType(Const.ACCOUNT_TYPE).firstOrNull()

    private fun hasCalendarPermission() =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun status(text: String) {
        statusView.text = text
    }

    /**
     * The typed token, or the saved one if the field was left blank. The saved token is only
     * reused for the exact URL it was saved with, so editing the URL can never send it elsewhere.
     */
    private fun effectiveToken(url: String): String? {
        val typed = tokenField.text.toString().trim()
        if (typed.isNotEmpty()) return typed
        val acc = existingAccount() ?: return null
        val savedUrl = am.getUserData(acc, Const.KEY_BASE_URL)?.trim()?.trimEnd('/')
        return if (savedUrl == url.trim().trimEnd('/')) am.getPassword(acc)?.takeIf { it.isNotEmpty() } else null
    }

    private fun loadCalendars() {
        val url = urlField.text.toString().trim()
        val token = effectiveToken(url)
        if (url.isEmpty() || token == null) return status("Enter URL and token (a changed URL needs the token re-entered)")
        runCatching { UrlPolicy.check(url) }.onFailure { return status(it.message ?: "Invalid URL") }

        status("Connecting…")
        io.execute {
            val result = runCatching { HaClient(url, token).listCalendars() }
            runOnUiThread {
                result
                    .onSuccess { showCalendars(it); status("Found ${it.size} calendar(s)") }
                    .onFailure { status("Error: ${it.message ?: it.javaClass.simpleName}") }
            }
        }
    }

    private fun showCalendars(cals: List<HaCalendar>) {
        val saved = existingAccount()
            ?.let { am.getUserData(it, Const.KEY_SELECTED) }
            ?.let { s -> JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }

        calendarList.removeAllViews()
        checkboxes.clear()
        for (cal in cals) {
            val cb = CheckBox(this).apply {
                minHeight = (48 * resources.displayMetrics.density).toInt()
                textSize = 15f
                text = buildString {
                    append(cal.name)
                    append("  ·  ").append(cal.entityId)
                    if (!cal.writable) append("  ·  read-only")
                }
                isChecked = saved?.contains(cal.entityId) ?: true
            }
            calendarList.addView(cb)
            checkboxes += cal.entityId to cb
        }
    }

    private fun save() {
        if (!hasCalendarPermission()) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1)
            return status("Calendar permission is required")
        }
        if (checkboxes.isEmpty()) return status("Connect and load calendars first")

        val url = urlField.text.toString().trim().trimEnd('/')
        val token = effectiveToken(url) ?: return status("Enter the token (a changed URL needs it re-entered)")
        val host = runCatching { UrlPolicy.check(url).host }
            .getOrElse { return status(it.message ?: "Invalid URL") }
        val selected = checkboxes.filter { it.second.isChecked }.map { it.first }
        val openPath = if (openInHaSwitch.isChecked) {
            runCatching { UrlPolicy.checkPath(openPathField.text.toString()) }
                .getOrElse { return status("Page to open: ${it.message}") }
        } else null

        var account = existingAccount()
        if (account != null && account.name != host) {
            am.removeAccountExplicitly(account)
            account = null
        }
        if (account == null) {
            account = Account(host, Const.ACCOUNT_TYPE)
            if (!am.addAccountExplicitly(account, token, null)) return status("Could not create account")
        } else {
            am.setPassword(account, token)
        }
        tokenField.text.clear()
        tokenField.hint = "Saved - leave blank to keep the current token"
        am.setUserData(account, Const.KEY_BASE_URL, url)
        am.setUserData(account, Const.KEY_SELECTED, JSONArray(selected).toString())
        am.setUserData(account, Const.KEY_OPEN_IN_HA, if (openInHaSwitch.isChecked) "1" else "0")
        if (openPath != null) {
            am.setUserData(account, Const.KEY_OPEN_PATH, openPath)
            openPathField.setText(if (openPath == Const.DEFAULT_OPEN_PATH) "" else openPath)
        }
        setOpenInHaEnabled(openInHaSwitch.isChecked)

        ContentResolver.setIsSyncable(account, authority, 1)
        ContentResolver.setSyncAutomatically(account, authority, true)
        ContentResolver.addPeriodicSync(account, authority, Bundle(), Const.SYNC_INTERVAL_SECONDS)
        requestSync(account)

        status(
            "Saved ${selected.size} calendar(s). Syncing…" +
                if (!ContentResolver.getMasterSyncAutomatically()) {
                    "\n\nNote: system auto-sync is OFF, so periodic sync won't run. " +
                        "Enable it under Settings › Accounts."
                } else ""
        )
    }

    /** When off, the "Open in HA" screen is disabled so nothing can launch it. */
    private fun setOpenInHaEnabled(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, OpenInHaActivity::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun requestSync(account: Account) {
        ContentResolver.requestSync(account, authority, Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })
    }

    private fun removeAccount() {
        val acc = existingAccount() ?: return status("No account")
        // Removing the account makes the Calendar Provider delete all its calendars and events.
        am.removeAccountExplicitly(acc)
        setOpenInHaEnabled(false)
        openInHaSwitch.isChecked = false
        calendarList.removeAllViews()
        checkboxes.clear()
        status("Account removed; its calendars were deleted from the phone")
    }

    private fun showSyncState() {
        val acc = existingAccount() ?: return
        val last = am.getUserData(acc, Const.KEY_LAST_SYNC) ?: "never"
        val err = am.getUserData(acc, Const.KEY_LAST_ERROR)
        val active = ContentResolver.isSyncActive(acc, authority)
        status(buildString {
            append("Account: ${acc.name}\nLast successful sync: $last")
            if (active) append("\nSync in progress…")
            if (err != null) append("\nLast error: $err")
        })
    }
}
