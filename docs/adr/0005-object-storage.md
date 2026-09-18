# 5. Object storage: presigned URLs, content addressing, untrusted archives

- Status: accepted for the client contract; provider chosen in [ADR 0010](0010-storage-provider.md)
- Date: 2026-09-16

## Context

Users upload arbitrary study materials — images, video, PDFs, Office documents, spreadsheets,
archives — over student wi-fi and phone tethering, and expect them back later, ideally offline.

Three things make this harder than "call an upload API":

1. **Credentials.** Anything shipped in the APK is extractable. A cloud storage key in the app is a
   key in the hands of anyone who cares to look.
2. **Unreliable networks.** A single `PUT` of a 700 MB lecture recording will be interrupted, and
   restarting from zero on a metered connection is how an app gets uninstalled.
3. **Untrusted input.** Archives are attacker-controlled data structures. Zip slip overwrites
   arbitrary files; a 42 KB zip bomb expands to petabytes.

## Decision

### Client contract

**The app never holds cloud credentials.** It authenticates to a thin BFF and receives short-lived
presigned URLs for individual object parts. Compromising the APK yields nothing but the ability to
be a normal user.

**Uploads are chunked, resumable and content-addressed.** The pipeline is:

```
pick (Photo Picker / OPEN_DOCUMENT / share sheet)
  → stage locally + SHA-256
  → Room row as PENDING            (the catalogue is offline-first from the first instant)
  → WorkManager chunked upload against presigned part URLs
  → verify the stored object against the digest
  → SYNCED
```

Part boundaries are a pure function of file size, so a plan computed today matches one computed
after a restart and only the missing parts are resent. The SHA-256 doubles as the storage key, which
makes uploads idempotent, lets identical files share one blob, and turns "did this arrive intact?"
into a comparison rather than a hope.

**Archives are read-only, validated before a byte is written.** `ArchiveSafety` normalises every
entry name and rejects absolute paths, drive letters, `..` escapes, Windows separators, null bytes
and entries that collide on a normalised path. Aggregate caps bound entry count, per-entry size,
total size and compression ratio. Declared sizes are attacker-controlled, so the streaming reader
must enforce the same caps as it goes.

**The cache is LRU with two things it must never evict**: files the user pinned for offline use, and
files that have not finished uploading — where the local copy is the *only* copy, so eviction is
data loss, not cache pressure. When the budget cannot be met without breaking one of those promises,
`EvictionPlan.shortfallBytes` says so and the UI asks the user, rather than the app quietly
exceeding its budget or quietly destroying a file.

**Browsing reads thumbnails, never originals.** The catalogue grid draws a small bitmap rendered on
the device — the image itself, a video's first sync frame, a PDF's first page — cached under a key
derived from the file's SHA-256 (`<digest>@<edge>`), so two catalogue rows holding the same bytes
are rendered once. That cache is separate from the material cache and has its own small quota:
thumbnails are always regenerable, so unlike an original they can all be evicted freely. Generation
runs off the main thread and is cancelled when a cell scrolls away, and a deterministic
digest-derived placeholder is drawn until there is a bitmap — cheaper than a stored blur-hash, and
stable across recompositions. Formats no device can rasterise fall back to a server-rendered
thumbnail under `thumbnails/<digest>/<edge>`, requested at upload completion (#7).

**Previewers are per type**, with Office formats handed to installed viewers via intents rather than
reimplemented, and an optional Keystore-backed AES-GCM vault for material the user marks private.

### Provider

Proposed, to be confirmed before P3 (#7):

| Option | For | Against |
|---|---|---|
| **Supabase** | auth, Postgres, S3-compatible storage and presigned URLs out of the box; fastest path to a working sync | less control over the API surface; vendor coupling |
| **Ktor + Postgres + S3-compatible (R2 / MinIO)** | full control, cheap egress on R2, self-hostable | a backend to write, deploy and operate |
| **Local-only** | zero infrastructure, no privacy questions | no multi-device, no backup — fails a core promise |

**Recommendation: Supabase for the first cloud milestone**, behind the same repository interfaces
the local-only implementation already satisfies. Confirmed, with Firebase Storage also considered
and the exit path written down, in [ADR 0010](0010-storage-provider.md). The client contract above
is provider-agnostic, so switching later is a data-layer change rather than an architectural one.

## Consequences

- An interrupted 700 MB upload costs one part, not the whole file.
- Duplicate uploads cost nothing in storage or egress: the key is the digest, so an upload whose
  object the store already holds is re-linked after one `stat` instead of being sent again.
- Browsing a catalogue of hundred-megabyte recordings costs kilobytes: only thumbnails are read.
- No cloud credential ever ships in the APK.
- Everything works offline; the network is an optimisation, not a prerequisite.
- The cost is a BFF to build and operate, and a client upload path with more moving parts than a
  single `PUT`.
- Archive limits will occasionally reject a legitimate but unusual file. The rejection names the
  specific reason so the user is not left guessing.
