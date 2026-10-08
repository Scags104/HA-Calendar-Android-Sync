package io.hacalsync.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.util.Log
import io.hacalsync.Const
import io.hacalsync.ha.HaAuthException
import io.hacalsync.ha.HaClient
import org.json.JSONArray
import java.io.IOException
import java.time.Instant

class SyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true, false) {

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
    ) {
        val am = AccountManager.get(context)
        val baseUrl = am.getUserData(account, Const.KEY_BASE_URL) ?: return
        val token = am.getPassword(account) ?: return
        val selected = am.getUserData(account, Const.KEY_SELECTED)
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
            ?: emptySet()

        fun record(error: String?) {
            if (error == null) am.setUserData(account, Const.KEY_LAST_SYNC, Instant.now().toString())
            am.setUserData(account, Const.KEY_LAST_ERROR, error)
        }

        try {
            CalendarSync(account, provider, HaClient(baseUrl, token), syncResult).run(selected)
            record(null)
        } catch (e: HaAuthException) {
            syncResult.stats.numAuthExceptions++
            record(e.message)
        } catch (e: IOException) {
            syncResult.stats.numIoExceptions++   // Android retries with backoff
            record("Network: ${e.message}")
        } catch (e: Exception) {
            Log.e("HaCalSync", "Sync failed", e)
            syncResult.databaseError = true
            record("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
