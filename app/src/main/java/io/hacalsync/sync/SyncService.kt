package io.hacalsync.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder

class SyncService : Service() {
    override fun onCreate() {
        synchronized(lock) {
            if (adapter == null) adapter = SyncAdapter(applicationContext)
        }
    }

    override fun onBind(intent: Intent): IBinder = adapter!!.syncAdapterBinder

    companion object {
        private val lock = Any()
        private var adapter: SyncAdapter? = null
    }
}
