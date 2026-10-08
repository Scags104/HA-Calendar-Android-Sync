package io.hacalsync

object Const {
    /** Must match android:accountType in res/xml/authenticator.xml and syncadapter.xml. */
    const val ACCOUNT_TYPE = "io.hacalsync.account"

    // AccountManager user-data keys (the token itself is stored as the account password)
    const val KEY_BASE_URL = "base_url"
    const val KEY_SELECTED = "selected"     // JSON array of calendar entity_ids
    const val KEY_LAST_SYNC = "last_sync"
    const val KEY_LAST_ERROR = "last_error"
    const val KEY_OPEN_IN_HA = "open_in_ha"   // "1" = show "Open in Home Assistant" on events
    const val KEY_OPEN_PATH = "open_path"     // HA page to open, e.g. "/dashboard-family/calendar"
    const val DEFAULT_OPEN_PATH = "/calendar"

    /** Periodic background sync. Android enforces a 15 min minimum. */
    const val SYNC_INTERVAL_SECONDS = 30L * 60

    /** Sync window relative to now. Events outside it are not on the phone. */
    const val PAST_DAYS = 30L
    const val FUTURE_DAYS = 365L

    // Home Assistant CalendarEntityFeature bit flags (supported_features attribute)
    const val FEATURE_CREATE = 1
    const val FEATURE_DELETE = 2
    const val FEATURE_UPDATE = 4
}
