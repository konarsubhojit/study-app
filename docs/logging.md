# Logging and diagnostics

Two kinds of log line leave this app, and they are treated differently on purpose.

| | Debug build | Release build | Ring buffer / export |
|---|---|---|---|
| Free text (`AppLogger.info/warning/error`) | scrubbed of emails, paths and file names, then printed with the caller's tag | **discarded**: only `level=…` and a sanitised `throwable=…` survive, under the fixed `StudyFlow` tag | the release form |
| Structured diagnostic (`AppLogger.diagnostic`) | printed verbatim | printed verbatim | verbatim; MaterialUpload failure messages are sanitized before recording |

## Why free text is thrown away in release

`LogSanitizer.scrubReleaseMessage()` keeps the level and an optional sanitised throwable type and
drops everything else, and `LogSanitizer.RELEASE_TAG` replaces the caller's tag so a tag cannot leak
a file name either. Logcat is a shared, world-readable buffer on many devices; a message
interpolating a document title, an email address or a storage path ends up somewhere the user never
agreed to. The trade is deliberate and `LogSanitizerTest` pins it.

Free-text logging is still worth writing — it is what a developer reads on a connected device — but
it is not what a production failure is diagnosed from.

## The structured diagnostic API

`dev.studyflow.core.common.logging` provides a second channel that survives release sanitisation
because it is *structurally* unable to carry user content:

```kotlin
logger.diagnostic(
    diagnosticEvent(DiagnosticCode.SyncFailed) {
        put(DiagnosticKey.Trigger, trigger)          // an enum
        put(DiagnosticKey.Stage, outcome.stage)      // an enum
        put(DiagnosticKey.Reason, failure.reason)    // an enum
        put(DiagnosticKey.Retryable, failure.retryable)
    },
)
// code=SyncFailed trigger=SCHEDULED stage=PULL reason=OFFLINE retryable=true
```

The guarantee is the type system, not a filter:

- `DiagnosticCode` and `DiagnosticKey` are enums declared in `:core:common`, so a code and a key are
  always constants of this repository's source.
- `DiagnosticFields` has overloads for `Boolean`, `Int`, `Long` and `Enum<*>`, plus `putThrowableType`.
  Material upload failures use a fixed `DiagnosticThrowableKind` and a `SanitizedDiagnosticMessage`;
  the latter can only be made through `LogSanitizer.sanitizeDiagnosticFailureMessage`.
- That sanitizer removes URLs/query strings, headers, token-shaped values, emails, paths and
  filenames. Object keys and content hashes remain because material keys are opaque
  `materials/<sha256>` identifiers, not names or paths.
- A regex that inspected values would be a filter that fails open on the first case its author did
  not imagine. `DiagnosticEventTest` asserts that `DiagnosticFields` has no raw text overload.

For `MaterialUpload`, the logged `throwable=` value is a stable source-defined kind such as
`TRANSIENT_STORAGE`, not a throwable class name; `throwableMessage=` carries the sanitized exception
message. The engine exception and its cause are never attached to the diagnostic.

### What is instrumented today

| Code | Where | What it tells you |
|---|---|---|
| `SyncSkippedSignedOut` | `SyncWorker` | the run ended **before any network call** because no account is signed in — the case where the sync card shows a queue that never drains and no error |
| `SyncFailed` | `SyncWorker` | which half of the pass failed (`stage`), why (`reason`), and whether it will be retried |
| `SyncCrashed` | `SyncWorker` | the pass threw; the throwable type is named |
| `SyncInboundRecordDropped` | `ApiSyncTransport` | an inbound row could not be parsed and was skipped so the cursor could advance |
| `MaterialUpload` | `MaterialUploadEngine` | stage, retryability, material UUID, and—on failure—a stable kind and sanitized message |
| `ReminderIntegrityOk` / `ReminderIntegrityAnomaly` | `ReminderIntegrityCoordinator` | reconciliation counters |
| `LogsExported` | `LogExporter` | the user exported the buffer, and how many entries it held |

`SyncFailure.reason` is a `SyncFailureReason` enum mapped from `ApiError` in `ApiSyncTransport`. It
is separate from `SyncFailure.message`, which is `UserFacingMessage` copy: per
[ADR 0014](adr/0014-api-contract-and-network-client.md) the card the user sees carries no status
code and no exception text, while the log carries the named cause.

## The in-memory ring buffer

`RingLogBuffer` (300 entries, oldest evicted, every operation under one lock) is written to by
`AndroidAppLogger` alongside logcat. It holds **the release form** of every line in every build
type, so the export is never a way around the sanitiser. It is cleared on sign-out, with the rest of
the account-scoped state reset by `RemoteCacheCleaner`.

300 entries is a few tens of kilobytes — enough to cover a failing background run and the activity
around it, which is the window a bug report is written about.

## Exporting logs

Settings → **Diagnostics** → *Export logs* writes `studyflow-logs-<date>-<time>.txt` to the user's
Downloads folder through `MediaStore.Downloads`, and offers a share chooser for the same file.

- No permission is requested. Scoped storage lets an app insert its own file into Downloads on
  Android 10+ without `WRITE_EXTERNAL_STORAGE`; below that, the file goes to app-private storage and
  is shared through the existing `${applicationId}.files` `FileProvider`.
- The header names the app version, the Android version, the device model and the **host** of the
  API base URL — never the full URL, which identifies the backend project, and never a token.
- The body is the ring buffer, already sanitised. Nothing is uploaded: the export is local and
  user-initiated, and an empty buffer is reported rather than written.

`LogExporter` assembles the content and `LogExportFileWriter` writes it, so the document can be
asserted on in a plain unit test (`LogExporterTest`) without a content resolver.
