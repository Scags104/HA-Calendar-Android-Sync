# HA Calendar Android Sync

Two-way sync between **Home Assistant calendar entities** and **Android's system calendar**.

Once set up, your HA calendars show up in Google Calendar, Samsung Calendar, Etar, home-screen
widgets, launchers, Wear OS, and any other app that reads the Android calendar. Events you create,
edit, or delete in those apps are written back to Home Assistant.

> **Status:** experimental, personal project. Intended as a stopgap until the official
> Home Assistant Companion app supports this natively. Not affiliated with Home Assistant
> or Nabu Casa.

---

## Features

- Pick which `calendar.*` entities to sync
- Calendars appear as normal system calendars, visible to every calendar app and widget
- Two-way sync: create, edit, and delete events from the phone
- Calendars HA can't edit are shown as read-only on the phone
- Background sync every 30 minutes; phone-side edits are pushed within seconds
- No Home Assistant add-on or custom integration required; uses HA's built-in API

## Requirements

- Android 8.0 or newer
- A Home Assistant instance reachable from the phone (local URL, Nabu Casa, or your own domain)
- A long-lived access token (see setup below)

---

## Installation

### Option A: Download a release
1. Go to the [Releases](https://github.com/Scags104/HA-Calendar-Android-Sync/releases) page.
2. Download the latest `ha-calendar-sync-*.apk` to your phone.
3. Open it and allow installing from your browser or file manager when Android asks.

### Option B: Automatic updates with Obtainium
1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add this repository's URL as a new app.
3. Obtainium installs the latest release and notifies you of updates.

### Option C: Latest development build
Open the [Actions](https://github.com/Scags104/HA-Calendar-Android-Sync/actions) tab, select the
most recent successful **Build APK** run, and download the APK from **Artifacts**
(requires a GitHub login).

---

## Setup

### 1. Create a dedicated Home Assistant user (recommended)
The app stores a token that can do anything its HA user can do. Limit the damage if your phone
is ever lost:

1. In HA, go to **Settings › People › Users** and add a user, e.g. `phone-calendar-sync`.
2. Leave **Administrator** unchecked.
3. Log in as that user once.

### 2. Create a long-lived access token
1. While logged in as the sync user, open your **Profile › Security**.
2. Under **Long-lived access tokens**, select **Create token**, name it (e.g. "Pixel calendar"),
   and copy it. HA only shows it once.

### 3. Configure the app
1. Open **HA Calendar Sync** and grant calendar access when asked.
2. Enter your Home Assistant URL, e.g. `https://myhome.ui.nabu.casa` or
   `http://192.168.1.10:8123`. Use an address that works wherever you want sync to work;
   a LAN-only address only syncs at home. `http://` is only accepted for local-network and
   VPN addresses; anything reachable from the internet must use `https://`.
3. Paste the token and tap **Connect & load calendars**.
4. Tick the calendars you want on your phone and tap **Save & sync**.

### 4. Show the calendars in your calendar app
Most calendar apps hide newly added calendars. Open your calendar app's settings or calendar list
and enable the calendars under the account named after your HA host.

---

## Usage

| What you want | What to do |
|---|---|
| See HA events on the phone | Nothing; they sync automatically every 30 minutes |
| Add, edit, or delete an event | Do it in any calendar app; it reaches HA within seconds |
| Force a refresh | Open the app and tap **Sync now**, or use Android's account sync settings |
| Change which calendars sync | Open the app, tap **Connect**, change the ticks, tap **Save & sync** |
| Check whether sync is working | The app shows the last successful sync and the last error |
| Remove everything from the phone | Tap **Remove account**; HA is not affected |

### Optional: "Open in Home Assistant" on events
Turn on **Show "Open in Home Assistant" on events** in the app, optionally choose the
**Page to open**, and tap **Save & sync**. That page opens in the Home Assistant app, or on
your HA URL in the browser if the app isn't installed.

- **Launchers and widgets** (e.g. Niagara): when you tap an event and Android asks which app
  to use, choose **Open in Home Assistant** (tap **Always** to skip the question next time).
  Events from your HA calendars open Home Assistant; every other event is passed on to your
  usual calendar app.
- **Calendar apps** that support Android's custom-event links (such as Etar and AOSP
  Calendar) show an "Open in…" link on the details of HA events.

When the option is off, the app doesn't appear in the "which app" list at all.

**Page to open:** leave it empty for HA's built-in Calendar page, or enter a dashboard view
such as `/dashboard-family/calendar`. The easiest way is to open the view in a browser and
paste the full link; the app keeps only the page part and always opens it on your own HA. Many calendar apps (possibly
including Google and Samsung Calendar) ignore this link, so it's off by default.

---

## How it works

The app registers an Android account and a sync adapter, the same mechanism DAVx5 and Google use.
Each selected HA calendar becomes a calendar under that account.

- **HA → phone:** events are fetched with `GET /api/calendars/<entity_id>` over a window of
  30 days back to 365 days ahead, then compared with what's on the phone.
- **Phone → HA:** changes are sent over HA's WebSocket API using
  `calendar/event/create`, `calendar/event/update`, and `calendar/event/delete`.

Each sync sends phone changes first, then pulls from HA, so Home Assistant stays the source of
truth. If HA rejects a change, the phone version is discarded and HA's version is restored.

---

## Known limitations

- **Sync window:** only events from 30 days ago to 365 days ahead are on the phone.
- **Recurring events** arrive as individual occurrences. Editing or deleting one from the phone
  affects only that occurrence. Edit whole series in Home Assistant. Creating a new recurring
  event on the phone works.
- **New events created on the phone** are replaced after sync by HA's copy. Reminders added
  while creating the event are lost; add reminders afterwards.
- **Not synced:** attendees, per-event colours, attachments (HA has no fields for these).
- **Read-only calendars:** some integrations don't support editing; those calendars are
  read-only on the phone.
- **Not instant from HA:** changes made in HA appear on the phone at the next 30-minute sync
  or when you tap **Sync now**.

## Troubleshooting

| Problem | Fix |
|---|---|
| Calendars don't appear in my calendar app | Enable them in the calendar app's calendar list (see setup step 4) |
| Background sync never runs | Turn on Android's global auto-sync: **Settings › Accounts › Automatically sync data** |
| "rejected the token" error | The token was deleted or mistyped; create a new one and re-enter it |
| "http:// is only allowed for local network addresses" | Use your `https://` URL for remote access |
| Network errors away from home | Your URL is LAN-only; use your external or Nabu Casa URL |
| An edit snapped back to the old version | HA refused it; that calendar or event isn't editable |
| Battery saver delays sync | Exclude the app from battery optimization |

## Privacy & security

**Where your data goes:** only between your phone and the Home Assistant URL you enter.
The app has no analytics, crash reporting, ads, or any other network destination.

- The URL and token are stored on the phone in Android's AccountManager. They are never in
  the APK or this repository, are excluded from backups, and are never written to logs.
- After saving, the token is never shown again. Changing the URL requires re-entering the
  token, so it can't be sent to a different server by accident.
- The token field blocks autofill and keyboard learning, and the setup screen blocks
  screenshots and screen recording.
- HTTP redirects are refused, so the token only ever goes to the exact URL you entered.
- `http://` is only allowed for local-network/VPN addresses; everything else requires
  `https://` with a valid certificate.
- Synced events live in Android's calendar storage, where (like any calendar) apps you've
  granted calendar permission can read them. They are not uploaded to Google or anywhere
  else, because the calendars belong to this app's local account.

**Recommendations:**
- Use a **non-admin HA user** for the token (see setup). HA has no per-entity permissions,
  so the token can still control devices, but it can't change your HA configuration.
- If your phone is lost, **delete the token** in HA (or deactivate the sync user).
- Don't sync calendars that drive **security-sensitive automations** (locks, alarms).
  Any app with calendar write permission on your phone could add events to them.

Found a vulnerability? Please report it privately; see [SECURITY.md](SECURITY.md).

---

## Building from source

### Android Studio
1. Clone the repo and open it in Android Studio (Koala or newer).
2. Let Gradle sync, then **Run** on a device or use **Build › Build APK(s)**.

### GitHub Actions
Every push to `main` builds an APK (version shown as `dev.<build>`). Pushing a tag like
`v2026.10.3` (or `2026.10.3`) publishes a GitHub Release, and the app's version name is set
from the tag so Obtainium recognises it as installed.

For updates to install over previous versions, every build must be signed with the same key.
Create one once:

```sh
keytool -genkeypair -v -keystore release.jks -alias hacal -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks    # macOS: base64 -i release.jks
```

Then add these **repository secrets** under **Settings › Secrets and variables › Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the base64 output |
| `SIGNING_STORE_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | `hacal` |
| `SIGNING_KEY_PASSWORD` | the key password (usually the same as the keystore password) |

Keep `release.jks` backed up and **never commit it**. If it's lost, future builds can't be
installed over existing ones.

### Project layout

```
app/src/main/java/io/hacalsync/
├── MainActivity.kt          setup screen
├── Const.kt                 settings (sync interval, window, account type)
├── auth/                    Android account authenticator
├── ha/                      Home Assistant REST and WebSocket clients
└── sync/                    sync adapter and two-way sync engine (CalendarSync.kt)
```

---

## 🤖 AI disclosure: this project is vibecoded

This app was built through conversation with **Claude**, an AI assistant made by Anthropic.
The design, Kotlin source, build configuration, GitHub Actions workflow, and this README were
generated by AI from my descriptions of what I wanted, then assembled into this repository by me.

What that means for you:

- **The code has not been professionally reviewed or audited.** It may contain bugs, edge cases
  that lose or duplicate events, or security issues that an experienced Android developer would
  catch.
- **It handles a credential with broad access to your home.** Read the code (it's small) and use
  a non-admin HA user before trusting it.
- **Back up important calendars** before syncing them, especially if you plan to edit from the
  phone.
- Issues and pull requests from people who know Android sync adapters or the HA calendar API
  are very welcome.

Use at your own risk.

---

## License

Licensed under the [Apache License 2.0](LICENSE).
