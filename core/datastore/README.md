# Data stores

`UserSettings` is credential-protected Proto DataStore state. Its defaults are:

| Setting | Default |
| --- | --- |
| Theme | System |
| Focus duration | 25 minutes |
| Break duration | 5 minutes |
| Reminders | Disabled at 09:00 |
| Storage quota | 1 GiB |
| Sync mode | Wi-Fi only |

On the first read it migrates values from the legacy `settings` SharedPreferences file. The
`ActiveTimerAnchor` DataStore instead uses device-protected storage and holds only the currently
running timer's session and dual-clock anchor. It is intentionally available during direct boot;
clear it when the timer stops or pauses.
