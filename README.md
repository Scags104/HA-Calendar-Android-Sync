# HA Calendar Sync

Android app that exposes Home Assistant `calendar.*` entities as **system calendars**
(CalendarContract) with two-way sync, so any calendar app, widget, launcher or watch
can show and edit them. No HA add-on is needed; it talks to the HA API directly.

## Build & install
1. Open this folder in Android Studio (Koala or newer). Let it sync Gradle
   (it creates the Gradle wrapper on first open).
2. Run on your device, or *Build › Build APK(s)* and sideload.

## Setup
1. In HA: Profile › Security › **Long-lived access tokens** › Create.
2. Open the app, enter your HA URL (Nabu Casa / external URL if you want sync off-LAN)
   and the token, then tap **Connect**.
3. Tick the calendars to sync, then tap **Save & sync**.
4. In your calendar app, enable the new calendars (they appear under an account
   named after your HA host).

## How it works
| Direction | Mechanism |
|---|---|
| HA → phone | `GET /api/calendars/<entity>?start=&end=` every 30 min, plus on demand |
| phone → HA | WebSocket `calendar/event/create`, `calendar/event/update`, `calendar/event/delete` |

Android triggers an upload sync immediately whenever another app edits one of these
events (`supportsUploading=true`), so phone edits reach HA within seconds.
Each sync pushes local changes first, then pulls and diffs (insert/update/delete).

Write access per calendar follows HA's `supported_features`: calendars that can't be
edited (e.g. many integration-provided ones) are marked read-only on the phone.
If HA rejects a change, the local edit is discarded and HA's version is restored.

## Known limitations
- **Sync window:** 30 days back to 365 days ahead (`Const.kt`).
- **Recurring events** come down as individual instances. Editing or deleting one on the
  phone affects **only that occurrence**; to change a whole series, use HA.
  Creating a new recurring event on the phone works (the RRULE is sent to HA).
- **Events created on the phone** are re-inserted after sync with HA's identity. Any
  reminders added in the calendar app at creation time are lost; set reminders afterwards.
- Attendees, colors per event and attachments aren't synced (HA has no fields for them).
- The token has full HA admin rights. It is stored in Android's AccountManager.
- `usesCleartextTraffic="true"` allows `http://` LAN URLs; remove it if you use https only.

## Building on GitHub
Every push to `main` builds an APK (download it from the run's **Artifacts**).
Pushing a tag like `v0.1.0` also attaches the APK to a GitHub Release.

For updates to install over the previous version, every build must be signed with
the same key. Create one once, locally:

```sh
keytool -genkeypair -v -keystore release.jks -alias hacal \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # macOS: base64 -i release.jks
```

Then add these repository secrets (Settings › Secrets and variables › Actions):
`KEYSTORE_BASE64` (the base64 output), `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS` (`hacal`), `SIGNING_KEY_PASSWORD`.
Keep `release.jks` backed up and out of the repo.
