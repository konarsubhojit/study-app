# 4. Reminders use a scheduling decision matrix, not one API

- Status: accepted
- Date: 2026-09-16

## Context

"A to-do list configurable with alarm or notifications" spans two very different promises. "Remind
me to revise this evening" and "wake me up for the exam at 07:00" want opposite trade-offs, and
Android has spent a decade making the second one progressively harder for good battery reasons.

The available primitives all have real costs:

| Primitive | Precision | Cost |
|---|---|---|
| `WorkManager` / inexact alarm | may slide by minutes in Doze | free, batched |
| `setExactAndAllowWhileIdle` | to the minute | needs `SCHEDULE_EXACT_ALARM` on Android 12+, which the user can revoke; Play scrutinises the justification |
| `setAlarmClock` | to the minute, survives Doze, shows in the status bar | only appropriate for user-set alarms; full-screen intent is restricted to alarm/call apps on Android 14+ |

Using the strongest primitive everywhere drains the battery and risks a policy rejection. Using the
weakest means missed exams.

Recurrence adds a second trap. "Every day at 08:00" is a statement about the user's wall clock, not
a number of seconds. Adding 24 hours to an instant drifts by an hour at every DST boundary, and
pre-computing a table of future occurrences means every row is wrong the moment the user edits the
rule or changes timezone.

## Decision

**A decision matrix, expressed as data.** `ReminderPrecision` (`GENTLE` / `EXACT` / `ALARM`) states
what the user wants; `SchedulingCapabilities` states what the OS will currently allow;
`ReminderScheduler` maps the pair to a `ReminderDelivery`.

**Degradation is reported, never swallowed.** When exact alarms are denied, an `EXACT` reminder
falls back to inexact *and* the plan carries `EXACT_ALARMS_DENIED`, so the UI can tell the user their
reminder may arrive late and offer to open the settings screen. The same applies to blocked
notifications, denied full-screen intents and aggressive battery optimisation. A reminder that
silently does not fire is worse than no reminder.

**RRULE-lite, next occurrence only.** A deliberately small subset of the iCalendar grammar — daily /
weekly / monthly / yearly, an interval, weekday sets, a day-of-month, a counted weekday ("every 2nd
Tuesday", "the last Friday"), excluded dates, and an end condition — covers study schedules and is
small enough to test exhaustively. Only the next occurrence is ever materialised, so the rule stays
the single source of truth. Excluded dates are removed after the end condition has been applied, as
iCalendar's `EXDATE` is, so removing one occurrence cannot extend a "ten times" series.

**All recurrence arithmetic happens in local time.** Dates are advanced as `LocalDate` and only
resolved to an instant at the end, using the zone's rules for that specific date. A daily 08:00
reminder therefore stays at 08:00 all year; the underlying interval is 23 hours across the spring
forward and 25 across the autumn fall back, which is exactly right and is asserted in the tests.
"The 31st of every month" clamps to the last day of shorter months rather than skipping them.

**A recurring task is its next occurrence, and occurrences are consumed one at a time.** The task
row's due time is the occurrence currently outstanding; `TaskSeries.advance` moves it on by exactly
one occurrence when the user completes or skips it, decrementing a counted end and dropping
exceptions the series can no longer reach. Ticking Monday's task off on Wednesday moves the series
to Tuesday, not to Thursday: re-synchronising with "now" would silently drop what the user never
saw, and nothing re-materialises an occurrence already consumed, so a reminder cannot fire twice for
the same one.

**Editing a series needs two scopes, but only one split.** `RecurrenceEditScope.THIS_AND_FUTURE` is
an edit in place, because the materialised occurrence already *is* the first of the future ones and
the earlier occurrences were consumed rather than stored. `THIS_OCCURRENCE` detaches a copy of the
occurrence with its own identity and no rule, and advances the series past it.

**A reminder is relative or absolute, and nothing special-cases either.** `ReminderTrigger` is
`BeforeDue(leadTime)` or `AtInstant(instant, timeZone)`. "Remind me 10 minutes before" follows the
task's due time — and its next recurrence — without being rewritten; "ring at exactly 07:00" keeps
its own instant and is unaffected when the due date moves. A task may carry any number of reminders,
so the two live side by side rather than competing for one field.

**Snoozing postpones the firing, not the occurrence.** `SnoozeState` overrides a reminder's next
trigger and counts how often the user has pushed it back; the underlying trigger and recurrence are
untouched, so the following occurrence lands where the rule says it should. `lastFiredAt` records
what has already been delivered so a reschedule after reboot does not re-fire the past.

**Re-arm aggressively.** Alarms are dropped on reboot, on app update, and on time/timezone change.
`ReminderScheduler.planAll` recomputes every outstanding reminder, and a daily integrity worker
re-checks that what should be scheduled is scheduled.

**A late reminder still fires.** If the app was not running when the lead time elapsed, the plan
keeps a trigger in the past and `isOverdueAt` tells the platform layer to fire immediately. The
reminder is late, not irrelevant.

## Consequences

- Battery cost is proportional to what the user actually asked for.
- The exact-alarm permission is only requested when a user creates a reminder that needs it, which
  is both better UX and a defensible Play declaration.
- Every degraded state is visible in the UI, so "my reminder didn't fire" has an answer.
- `RecurrenceSummary` renders any rule as one English line ("Every weekday at 18:00"), built beside
  the engine that interprets the rule so description and behaviour cannot drift apart. It is not yet
  localised; a translation substitutes the same parts in its own grammar.
- The recurrence grammar is smaller than full RRULE. Imported calendar rules outside the subset are
  not supported; that is an accepted limitation for a study app.
