# Security Policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

Report them privately through GitHub:
**Security** tab › **Report a vulnerability**.

Include what you found, how to reproduce it, and what an attacker could do with it.
I'll acknowledge reports as soon as I can. This is a hobby project, so please allow
reasonable time for a fix before disclosing publicly.

## Supported versions

Only the latest release receives fixes.

## Scope and design

What this app does with your data, by design:

- Your Home Assistant URL and long-lived access token are entered on the phone and stored
  in Android's AccountManager. They are never included in the APK, the repository,
  app backups, or logs.
- The app talks **only** to the Home Assistant URL you enter. It contains no analytics,
  crash reporting, ads, or other network destinations.
- The token is sent only in the `Authorization` header (REST) and the WebSocket auth
  message to that URL. HTTP redirects are refused, so it can't be forwarded elsewhere.
- `http://` is only accepted for local-network and VPN addresses; anything else must use
  `https://` with a valid certificate. There is no option to disable certificate checks.
- Synced events are written to Android's calendar storage on the device. Like every
  calendar on your phone, they are readable by other apps you have granted calendar
  permission to.

Known residual risks (accepted):

- On a rooted or compromised device, the token can be extracted from AccountManager.
- Android lets any app you've granted **calendar write** permission edit any calendar on
  the phone, including these. Such edits are synced to Home Assistant. If you have
  automations triggered by calendar events (e.g. "unlock the door when event X starts"),
  don't sync that calendar to the phone, or keep security-sensitive automations off
  calendar triggers.
- Home Assistant tokens cannot be scoped to specific entities. Use a dedicated
  non-admin HA user for this app and revoke its token if your phone is lost.
