# 10. Storage provider: Supabase Storage behind a provider-agnostic `ObjectStore`

- Status: accepted
- Date: 2026-09-17

## Context

[ADR 0005](0005-object-storage.md) fixed the *client contract* for materials — no cloud credentials
in the app, short-lived presigned URLs, chunked resumable content-addressed uploads — and left the
provider "proposed, to be confirmed". The [backend-platform ADR](0007-backend-platform.md) then
chose Supabase as the backend platform for auth and Postgres, but a backend platform and a *bytes*
provider are not the same decision: storage is the part of the bill that scales with egress, and
the part most likely to be re-pointed at a cheaper bucket once real usage exists.

So there are two questions, and they need different answers:

1. Which provider stores the bytes for the first cloud milestone?
2. What has to be true for answer 1 to be changeable later without a rewrite?

The second question is the important one. A lecture recording library is exactly the workload where
egress pricing bites, and the point at which that becomes visible is precisely the point at which
the codebase is large enough for a provider swap to be terrifying. The cost of being wrong must be
bounded *now*, while nothing depends on the answer.

## Decision

### The abstraction comes first

`:core:storage` defines `ObjectStore`: `initUpload`, `uploadPart`, `completeUpload`,
`getDownloadUrl`, `delete`, `stat` — stated entirely in terms of opaque `ObjectKey`s and expiring
`PresignedUrl`s. No bucket name, region, SDK type, provider enum or credential appears in the
signature of any of them, and none may appear above the module. Every provider-specific decision
lives behind one of two adapters:

| Adapter | Used by | What it is |
|---|---|---|
| `PresignedObjectStore` | the cloud build | plain HTTP `PUT` to whatever URL our BFF signs — the one behaviour Supabase Storage, R2, S3 and MinIO share exactly |
| `InMemoryObjectStore` | tests and the offline-only variant | a real implementation of the contract, including part boundaries, URL expiry and digest verification |

The provider-specific half is `PresignedUrlSource`, which is implemented *by the BFF* (#7), not by
the app. Signing is the only operation that needs a cloud credential, which is why it is the only
operation the client delegates — and why an extracted APK yields nothing.

### Comparison

Scored 1 (worst) to 5 (best) for a solo project, pre-revenue, with a media-heavy workload.

| Criterion | Supabase Storage | R2 / S3-compatible behind our BFF | Firebase Storage | Local-only |
|---|---|---|---|---|
| Egress cost | 3 — billed per GB beyond a small included allowance; the line item that grows with a video library | 5 — R2 charges zero egress, which is the whole reason it is on this list | 2 — GCS egress rates, and the "just use the SDK" path makes the cost hard to bound | 5 — none |
| Presigned & resumable uploads | 4 — S3-compatible multipart plus signed URLs; resumable via TUS | 5 — S3 multipart is the reference implementation of exactly what ADR 0005 describes | 2 — resumable uploads assume the Firebase SDK *in the client*, which is the model we rejected | 3 — trivially resumable, but there is nothing to resume to |
| Auth / authorisation model | 5 — the same Postgres RLS policies that guard the metadata guard the objects; one identity, one rule | 3 — authorisation is ours to write in the BFF, which is flexible and is also one more thing to get wrong | 2 — a second identity system and a second rules language beside the one the backend-platform ADR already chose | 1 — no accounts at all |
| Quota control | 4 — per-bucket file-size limits and MIME allow-lists, with per-user quota enforced in the BFF | 4 — same, plus lifecycle rules for cold data | 3 — rules can cap size per object; spend caps are a billing alert, not a limit | 5 — bounded by the device |
| Vendor lock-in | 4 — an S3-compatible API over a bucket; objects are fetchable with any S3 client | 5 — nothing proprietary to leave | 2 — the SDK-shaped client is the lock-in, not the bucket | 3 — "exit" means building the whole sync path |
| Operational burden | 5 — included in the platform already chosen | 2 — a bucket, credentials, CORS, lifecycle rules and a signing service we operate | 4 — managed | 5 — none |
| Cohesion with the backend-platform ADR | 5 — one project, one dashboard, one local `supabase start` stack | 3 — a second provider to configure in dev, CI and prod | 1 — a second platform entirely, duplicating auth | 2 — contradicts the epic's exit criterion |
| **Total** | **30** | **27** | **16** | **24** |

**Firebase Storage is rejected** on the criterion that matters most: its resumable-upload story
assumes its SDK runs in the client, which is the architecture ADR 0005 explicitly ruled out, and it
would add a second identity system next to the one the backend-platform ADR already chose.

**Local-only is not a provider**, it is a *mode*. It stays permanently supported through
`InMemoryObjectStore`, because offline-first is a product promise — but it cannot answer "sign in on
a second device and find your materials", so it is not an answer to this ADR.

**R2 is the better bucket and the wrong first step.** Zero egress is genuinely compelling and is the
reason it wins the cost row outright. But adopting it now means running a second provider, a second
set of credentials and a bespoke signing service before a single user has uploaded a single file —
paying the operational cost of an optimisation whose benefit is proportional to traffic we do not
yet have.

**Decision: Supabase Storage for the first cloud milestone**, behind `ObjectStore`, with R2 as the
documented exit. Because `PresignedObjectStore` speaks only "PUT these bytes at this signed URL",
moving to R2 is a change to `PresignedUrlSource` in the BFF and `StorageModule`'s one `@Provides`
line — not a change to the materials feature, the upload worker, the cache or the previewers.

### The exit path, written down while it is cheap

1. Point a new `PresignedUrlSource` implementation at R2 (S3 multipart; the same signing verbs).
2. Copy objects with `rclone`; keys are SHA-256 digests, so a copy is verifiable and idempotent, and
   a partial migration is safe — a key either matches its digest or is re-copied.
3. Flip the `@Provides` in `:core:storage`'s `StorageModule`.

The trigger is a number, not a feeling: when monthly storage egress exceeds the cost of operating a
bucket ourselves, migrate.

## Consequences

- The provider decision costs one file to reverse. Nothing outside `:core:storage` names a provider.
- The materials feature (#37 onwards) can be built and tested in full before the BFF serves its
  first request: `InMemoryObjectStore` implements the same contract, failures included.
- The hard rule survives by construction — there is nowhere in `ObjectStore` to put a credential,
  and `PresignedObjectStore` keeps signed URLs out of exception messages so they cannot leak into a
  log or a crash report.
- We accept Supabase's egress pricing for now, and accept that the migration above is work we may
  have to do later. The trade is a working sync months earlier.
- `InMemoryObjectStore` is not durable across process death, so the offline-only variant relies on
  the catalogue's on-disk staging copy (ADR 0005) for durability. A file-backed local adapter is a
  drop-in replacement if the offline-only variant ever needs to stand alone.
