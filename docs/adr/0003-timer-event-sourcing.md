# 3. The timer is event-sourced and clock-derived

- Status: accepted
- Date: 2026-09-16

## Context

The core requirement is "a stopwatch the user starts while studying and stops afterwards, and the
timer must survive app closure". That sentence hides most of the difficulty in the product.

On modern Android the app will be killed while the timer runs. The device will reboot. It will spend
hours in Doze. The clock will jump when the network syncs or the user changes the date, and again
twice a year for DST. Any of these can silently corrupt a naive stopwatch.

The naive implementations and how they fail:

| Approach | Failure |
|---|---|
| A ticking counter in memory | dies with the process |
| `System.currentTimeMillis()` deltas | an NTP correction adds or erases hours |
| `SystemClock.uptimeMillis()` deltas | stops during deep sleep; undercounts every idle session |
| `CountDownTimer` / a 1 Hz coroutine loop | keeps the CPU awake for a display that is already the system's job |
| A wake lock | drains the battery to accomplish nothing |
| Storing an `elapsedMillis` column | one missed update and the number is wrong forever, with no way to detect it |

## Decision

**Store events, derive time.**

A session is an append-only log of `STARTED` / `PAUSED` / `RESUMED` / `STOPPED` rows. There is no
`elapsedMillis` column anywhere in the app. Elapsed time is computed by folding the log at read
time, so it cannot drift, cannot be forgotten, and cannot be lost with the process.

**Every event records two clocks plus a boot id** (`TimeAnchor`):

- `elapsedRealtime` — monotonic, counts deep sleep, immune to adjustment, meaningless across a
  reboot;
- wall clock — survives reboots, jumps freely;
- boot id — tells you whether two monotonic readings are comparable at all.

One measurement rule is applied uniformly, to closed intervals during a fold and to the open
interval at read time:

- **same boot** → trust the monotonic delta, ignore the wall clock entirely;
- **different boot** → the monotonic clocks are incomparable, so record the wall-clock gap as
  *unverified* and count nothing.

**Unverified time is never silently counted.** If the device rebooted mid-session the app genuinely
cannot distinguish "studied for two hours" from "phone was off in a bag". Folding that into the
total fabricates study data; discarding it erases real work. `SessionElapsed` therefore carries
`counted` and `unverified` separately and the user is asked. This is the only case where the app
asks, and it is the only honest answer.

**The visible ticking is the system's job.** A foreground service posts a notification with a
chronometer anchored to a single instant; the platform renders the counting digits. The app does not
need to be running, scheduled or awake for the display to stay correct.

**Explicitly banned:** `CountDownTimer`, any tick loop, and wake locks.

## Consequences

- Process death is a non-event: replay the log.
- Reboots are detected rather than guessed at, via `TimerEngine.reconcile` on start and on
  `BOOT_COMPLETED`.
- Clock changes cannot affect a measurement. They are surfaced as a diagnostic
  (`TimerEngine.wallClockSkew`) so the UI can reassure the user rather than leave them puzzled.
- Battery cost while running is one notification, no CPU.
- The whole engine is a pure function, so every one of these scenarios is a deterministic JVM test
  (`FakeDevice.reboot()`, `.adjustWallClock()`, `.advance()`) instead of a manual device ritual.
- The cost is a slightly larger write path — one row per state change rather than an update — and a
  UI that must be able to explain an unverified gap. Both are cheap next to a timer that lies.

## Open follow-ups

- Direct-boot support: persist enough state in device-protected storage so recovery works before
  first unlock.
- A durable representation of the user's decision about an unverified gap (accept / reject / edit),
  which is currently outside the four event types.
