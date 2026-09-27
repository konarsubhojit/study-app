# Authentication

StudyFlow starts in **local-only** mode. Sessions, tasks, subjects, and materials remain in the
single local database and every feature except cross-device sync is available without an account.
Creating an account does not copy or reset that database: sync uploads the existing rows using
their stable IDs, so an upgrade cannot create a second local copy.

## Sign-in

The app requests credentials through Android Credential Manager, listing a passkey first and Sign
in with Google second. The app sends the resulting WebAuthn assertion or Google ID token only to
the StudyFlow API, which verifies it and returns the access/refresh pair. Neither proof nor token
may be logged.

`POST /v1/auth/signin` verifies a passkey assertion server-side: it claims the challenge in a
single database statement (so a replay finds nothing left to claim), looks the credential id up in
`passkey_credentials`, and checks the signature, the relying-party id hash, the app's
APK-signing-certificate origin, the user-presence flag and the signature counter before minting a
session. A user handle in the assertion is only ever compared against the stored credential's
owner; it never selects the account. Every failure answers the same `401 invalid_credentials`, so
the endpoint cannot be used to discover which accounts exist.

If Credential Manager or an eligible provider is unavailable, use the API's browser-based
WebAuthn/OIDC fallback. Do not add a password fallback; students can continue in local-only mode.

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
then. Remote replicas must be cleared before a different account can sync.
