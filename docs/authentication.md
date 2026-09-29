# Authentication

StudyFlow starts in **local-only** mode. Sessions, tasks, subjects, and materials remain in the
single local database and every feature except cross-device sync is available without an account.
Creating an account does not copy or reset that database: sync uploads the existing rows using
their stable IDs, so an upgrade cannot create a second local copy. Completing sign-in requests a
sync straight away, so a second device fills with the account's sessions, tasks and materials
without waiting for the periodic run ([ADR 0018](adr/0018-task-material-sync.md)). While nobody is
signed in the sync worker does nothing, so local-only mode never touches the network.

## Sign-in

The app requests credentials through Android Credential Manager. With passkey sign-in enabled it
lists a passkey first and Google second; with it disabled — the default, see
[Passkey sign-in is opt-in](#passkey-sign-in-is-opt-in) — it offers Sign in with Google alone. The app sends the resulting WebAuthn assertion or Google ID token only to
the StudyFlow API, which verifies it and returns the access/refresh pair. Neither proof nor token
may be logged.

Sign-in is optional from the Account card in Settings; launching the app never requires an
account. With passkeys enabled, the client requests a challenge, offers passkey and Google together
through Credential Manager, then exchanges the selected proof for a persisted session. If no
matching passkey or account is available, it retries with Google alone so a new user can select a
Google account. With passkeys disabled, it skips the challenge and goes straight to that
Google-only request.

The two Google options are not interchangeable. The combined sheet must use `GetGoogleIdOption`:
the pinned `credentials-play-services-auth` rejects a `GetSignInWithGoogleOption` that shares a
request with any other option (`GetSignInWithGoogleOption cannot be combined with other options`).
`GetGoogleIdOption` is the One Tap flow, however, and it can drop itself from the sheet without
raising anything — no Google account on the device, earlier dismissals, stale Play services, or a
missing Android OAuth client (below). Every Google-only request therefore uses
`GetSignInWithGoogleOption`, which always shows the account chooser. Both return a
`GoogleIdTokenCredential`; the client accepts its plain and Sign-in-with-Google type strings.

A dropped option leaves no exception behind, so `SignInCoordinator` logs through `AppLogger` what
it can see: a warning when `GoogleSignInConfig` cannot be provided (with the exception naming the
missing build property), a warning with the Credential Manager exception *type* when a request
fails, and a debug line naming the *kind* of credential chosen (`passkey` or `google`). None of
these lines carries a proof, token, or account; the user-facing messages are unchanged. Cancellation leaves the app in local-only mode; rejection and network failure are shown
separately. Sign-out removes the encrypted tokens but keeps local study content.

`POST /v1/auth/signin` verifies a passkey assertion server-side: it claims the challenge in a
single database statement (so a replay finds nothing left to claim), looks the credential id up in
`passkey_credentials`, and checks the signature, the relying-party id hash, the app's
APK-signing-certificate origin, the user-presence flag and the signature counter before minting a
session. A user handle in the assertion is only ever compared against the stored credential's
owner; it never selects the account. Every failure answers the same `401 invalid_credentials`, so
the endpoint cannot be used to discover which accounts exist.

### Passkey sign-in is opt-in

Passkey sign-in is **off unless a build turns it on**. It only works once the relying-party domain
serves a correct `assetlinks.json` for the build's signing certificate (below). Until that is in
place, the passkey entry is at best noise in the sheet, and at worst it is the only entry shown.
The switch is a build property, surfaced as `BuildConfig.PASSKEY_SIGN_IN_ENABLED`:

* locally: `-Pstudyflow.passkeySignInEnabled=true` (or `STUDYFLOW_PASSKEY_SIGN_IN_ENABLED=true`);
* in GitHub Actions: set the `STUDYFLOW_PASSKEY_SIGN_IN_ENABLED` **repository variable** (not a
  secret) to `true`. `ci.yml` and `release.yml` pass it to the production build and default it to
  `false`.

Any value other than `true` or `false` fails the build. While it is off, the Account card reads
"Sign in with Google" and no `POST /v1/auth/signin/challenge` is made. The server is unchanged: the
`api` function still requires `PASSKEY_RP_ID` and `PASSKEY_ANDROID_ORIGIN` at boot, so deploy it with
both even before the client opts in.

If Credential Manager or an eligible provider is unavailable, use the API's browser-based
WebAuthn/OIDC fallback. Do not add a password fallback; students can continue in local-only mode.

## Google Cloud and Digital Asset Links setup

Every value below is a placeholder. Do not commit a real domain, client id, fingerprint, or project
ref.

### Google Cloud OAuth clients

Sign in with Google needs **two** OAuth clients in the same Google Cloud project:

| Client type | Registered with | Used for |
| --- | --- | --- |
| **Android** | The app's `applicationId` (`dev.studyflow.app`; the mock flavour adds `.mock`) and the signing certificate's **SHA-1** fingerprint — one client per signing certificate (debug, upload, Play app signing) | Nothing is configured with its id. Google uses it to recognise the calling app. |
| **Web application** | Nothing app-specific | Its client id is `GOOGLE_SERVER_CLIENT_ID`: both the app build property (`studyflow.googleServerClientId`) and the `api` function secret. The server checks the ID token's audience against it. |

A missing or mistyped **Android** client doesn't produce an error. The Google option simply never
appears in the Credential Manager sheet, and a combined sheet shows the passkey alone. That is the
failure this setup exists to prevent. When the Google option is missing, check that an Android client
exists for the exact package name and the SHA-1 of the certificate that signed the installed APK.
Also check that a Google account is signed in on the device.

### Relying-party id and `assetlinks.json`

`PASSKEY_RP_ID` is a **bare domain**: no scheme, no path, no port (`auth.example.com`, not
`https://auth.example.com/`). That domain must serve
`https://<rp-id>/.well-known/assetlinks.json` at its root, over HTTPS, with **no redirects** and
`Content-Type: application/json`. A `*.supabase.co` project URL cannot be the RP id, because the
root of that domain is not ours to serve files from.

A worked example, served at `https://auth.example.com/.well-known/assetlinks.json`:

```json
[
  {
    "relation": [
      "delegate_permission/common.handle_all_urls",
      "delegate_permission/common.get_credentials"
    ],
    "target": {
      "namespace": "android_app",
      "package_name": "dev.studyflow.app",
      "sha256_cert_fingerprints": [
        "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
      ]
    }
  }
]
```

`delegate_permission/common.get_credentials` is the relation Credential Manager checks for passkeys.
`handle_all_urls` is optional here. List one fingerprint per signing certificate that ships the app.

**The RP id is permanent.** Every passkey is bound to the RP id it was created under. Changing
`PASSKEY_RP_ID` later invalidates **every** registered passkey, and those users must sign in with
Google and register a new one. Choose a domain you will keep.

### One certificate, three fingerprint encodings

The same signing certificate appears in three places, each in a different encoding:

| Where | Hash | Encoding | Example shape |
| --- | --- | --- | --- |
| `assetlinks.json` `sha256_cert_fingerprints` | SHA-256 | colon-separated **uppercase hex** | `AA:BB:…:99` (32 bytes) |
| `PASSKEY_ANDROID_ORIGIN` | SHA-256 | **base64url**, unpadded, after `android:apk-key-hash:` | `android:apk-key-hash:` + 43 characters |
| Google Cloud **Android** OAuth client | **SHA-1** | colon-separated hex | `AA:BB:…:DD` (20 bytes) |

Mixing them up is easy and fails quietly: the passkey or Google option disappears, or every passkey
assertion gets a `401`. `keytool -list -v -keystore <keystore> -alias <alias>` prints both the
SHA-1 and the colon-hex SHA-256. To produce the base64url form:

```bash
# From a keystore you hold (debug or upload key):
keytool -exportcert -keystore <keystore> -alias <alias> \
  | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '='

# From a colon-hex SHA-256, for example the Play app signing key shown in Play Console:
printf '%s' 'AA:BB:...:99' | tr -d ':' | xxd -r -p | openssl base64 -A | tr '+/' '-_' | tr -d '='
```

Both print 43 characters. Prefix the result with `android:apk-key-hash:`.

### Play App Signing

With Play App Signing, Google re-signs the app with the **Play app signing key**. Store installs
therefore report a different certificate from builds signed with your upload key, including the CI
testing APK and sideloaded tagged releases. During a rollout, both must be accepted:

* `PASSKEY_ANDROID_ORIGIN` takes a comma-separated list; include the upload-key hash **and** the
  Play-key hash:
  `android:apk-key-hash:<upload-key-base64url>,android:apk-key-hash:<play-key-base64url>`.
* List both SHA-256 fingerprints in `assetlinks.json`.
* Register an Android OAuth client for both SHA-1 fingerprints.

The Play key's fingerprints are in Play Console → *Test and release* → *App integrity* → *App signing*.

## Passkey registration

**A passkey is added to an account that already exists.** Google sign-in is the account-creation
path; registration attaches an additional authenticator to the account the caller is already
signed in as. Both registration endpoints are therefore authenticated (`auth: "jwt"` in the `api`
Edge Function's route table):

| Operation | Endpoint | Purpose |
| --- | --- | --- |
| `beginPasskeyRegistration` | `POST /v1/auth/passkey/registration/challenge` | Returns WebAuthn creation options and their expiry |
| `completePasskeyRegistration` | `POST /v1/auth/passkey/registration` | Accepts the `registrationResponseJson` Android produced and stores the credential |

The alternative — an unauthenticated registration endpoint that names its account — was rejected:
it would let a stranger bind their own authenticator to any account they could name, which is
account takeover with extra steps. The consequence is that a passkey cannot be the first
credential on a new account, and that is deliberate.

The challenge issued by the first call records the account it was issued to, and the second call
refuses a challenge belonging to anybody else, so a challenge cannot be passed between users.
Registration is not idempotent: a repeat answers `409 credential_already_registered` rather than
storing a second copy.

Credentials live in `public.passkey_credentials`, which has row-level security enabled and no
policy at all — not even the owning user may read their own row through PostgREST, because the
public key and signature counter are inputs to an authentication decision. All access is through
the Edge Function's service role.

## Session lifecycle

`TokenStore.authState` is the single `StateFlow` for the app shell. Features must not inspect
tokens or add their own authentication checks. Access and refresh tokens live only in
`EncryptedSharedPreferences`, whose key is Android Keystore-backed; Android backups are disabled.
The HTTP client silently refreshes access tokens and clears an invalid refresh token.

Sign-out clears credentials and account-scoped remote replicas, but retains local study content.
If a future product flow offers deletion, it must ask for explicit confirmation and delete only
then. Remote replicas must be cleared before a different account can sync: sign-out calls
`SyncStore.resetForAccountChange()`, which drops both sync cursors and re-queues every syncable
row, so the next account reads its own history from the start and receives this device's data.
