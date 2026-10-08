package io.hacalsync

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.hacalsync.ha.HaCalendar
import io.hacalsync.ha.HaClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val am by lazy { AccountManager.get(this) }

    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var calendarList: LinearLayout
    private lateinit var statusView: TextView
    private val checkboxes = mutableListOf<Pair<String, CheckBox>>()

    private val authority = CalendarContract.AUTHORITY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlField = findViewById(R.id.url)
        tokenField = findViewById(R.id.token)
        calendarList = findViewById(R.id.calendar_list)
        statusView = findViewById(R.id.status)

        findViewById<Button>(R.id.connect).setOnClickListener { loadCalendars() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.sync_now).setOnClickListener {
            existingAccount()?.let { requestSync(it); status("Sync requested") } ?: status("Save an account first")
        }
        findViewById<Button>(R.id.remove).setOnClickListener { removeAccount() }

        existingAccount()?.let { acc ->
            urlField.setText(am.getUserData(acc, Const.KEY_BASE_URL))
            tokenField.setText(am.getPassword(acc))
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

    private fun loadCalendars() {
        val url = urlField.text.toString().trim()
        val token = tokenField.text.toString().trim()
        if (url.isEmpty() || token.isEmpty()) return status("Enter URL and token")

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
        val token = tokenField.text.toString().trim()
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return status("Invalid URL")
        val selected = checkboxes.filter { it.second.isChecked }.map { it.first }

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
        am.setUserData(account, Const.KEY_BASE_URL, url)
        am.setUserData(account, Const.KEY_SELECTED, JSONArray(selected).toString())

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
