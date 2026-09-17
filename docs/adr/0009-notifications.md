# 9. Notifications are one layer with documented channels and a deferred permission

- Status: accepted
- Date: 2026-09-17

## Context

Three unrelated features need to reach the user outside the app: the study timer (an ongoing
session), reminders and alarms (a moment in time), and uploads (background progress). Left to
themselves, three call sites produce three channel ids, three opinions about importance, and three
different behaviours when the user says no.

Android has also turned "post a notification" into a compound question. Since Android 8 the channel
decides how loud a notification is and only the user can change it afterwards; since Android 13
posting needs the `POST_NOTIFICATIONS` runtime permission, and `notify` throws without it; since
Android 14 full-screen intents are restricted to alarm and calling apps. Meanwhile the platform
cannot tell an app whether it has ever shown the permission dialog:
`shouldShowRequestPermissionRationale` is `false` both before the first request and after a
permanent denial.

The easy failure modes are all invisible. A notification posted to a channel the user deleted
disappears without an error. A permission check the app forgets becomes a crash in the field. A
prompt at cold start — before the user has done anything a notification could be about — is denied
by reflex, and Android then never shows that dialog again.

## Decision

**`:core:notifications` is the only way the app posts a notification.**
`StudyFlowNotificationChannel` enumerates every notification the app can post, with its group and
its default importance, and the builders take that type rather than a channel id string. An
undocumented channel is therefore not expressible.

**The app proposes an importance; the user owns it.** Registration is idempotent and never rewrites
an existing channel, so re-registering restores a channel the user deleted without undoing a
channel the user merely turned down. Renaming means a new id plus an explicit legacy id to delete.

**The permission is asked for at a moment of value.** `NotificationMoment` names the moments, and
`APP_LAUNCH` is one of them precisely so that "never at cold start" is asserted by a test.
`NotificationPermissionPolicy` maps (state, moment) to one of: ask, explain then ask, or go to
system settings. A private record of "we have shown the dialog" supplies the fact Android will not.

**Denial is a return value, not an exception and not silence.** `StudyFlowNotifier.post` returns
`POSTED`, `PERMISSION_DENIED` or `CHANNEL_DISABLED`, and every moment carries a message key naming
what the user loses. This mirrors the reminder scheduler's rule (ADR 0004): a degraded state is
reported, never swallowed.

**Every `PendingIntent` is immutable.** They are all created by `StudyFlowPendingIntents`, which
offers no mutable variant, and action intents must be explicit. The audit for "no mutable intents
without justification" is a grep that finds nothing.

## Consequences

- One channel list to reason about, one place to change importance, one screen that explains the
  current state to the user.
- Features handle three outcomes instead of assuming success; that is slightly more code at each
  call site and the reason notifications no longer fail invisibly.
- The permission request is tied to a feature the user just used, which is both better UX and a
  defensible Play declaration.
- Channel *names and descriptions* are English strings in the enum today. Localising them means
  moving to string resources, which changes the enum's shape but not this decision.
