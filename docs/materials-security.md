# Materials security checklist

This checklist records the materials controls and maps them to the relevant MASVS privacy, network,
storage, and cryptography areas. The backend controls are implemented by the storage BFF and its
database authorization suite; this file is the feature checklist linked from issue #9.

| Control | Status |
| --- | --- |
| TLS only for remote presigned URLs; release has no cleartext exception | Complete |
| Presigned URLs are limited to 15 minutes and are never included in storage exceptions | Complete |
| The BFF authenticates every operation and derives the object key from the current user before signing, statting, completing, or deleting | Complete |
| Database authorization tests prove that one user cannot read, update, or delete another user's material or use their storage-key prefix | Complete |
| Imports stream into app-private storage, enforce per-kind size caps, and inspect content signatures | Complete |
| Object and archive keys reject traversal sequences | Complete |
| Material rows are tombstoned for sync; the BFF deletes remote originals and aborts in-flight uploads | Complete |
| Private vault encryption is an optional capability. When enabled, it must use AES-GCM keys in Android Keystore (StrongBox where available); key loss makes ciphertext unrecoverable, so recovery is limited to an unencrypted backup | Not enabled |
| No material is written to shared storage except through an explicit user-selected SAF export | Complete |

Certificate pinning is optional. If enabled, ship at least one backup pin, overlap old and new pins
through a release cycle, monitor pin failures without recording URLs or paths, and remove an expired
pin only after the replacement is deployed.
