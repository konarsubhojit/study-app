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

If Credential Manager or an eligible provider is unavailable, use the API's browser-based
WebAuthn/OIDC fallback. Do not add a password fallback; students can continue in local-only mode.

## Session lifecycle

`TokenStore.authState` is the single `StateFlow` for the app shell. Features must not inspect
tokens or add their own authentication checks. Access and refresh tokens live only in
`EncryptedSharedPreferences`, whose key is Android Keystore-backed; Android backups are disabled.
The HTTP client silently refreshes access tokens and clears an invalid refresh token.

Sign-out clears credentials and account-scoped remote replicas, but retains local study content.
If a future product flow offers deletion, it must ask for explicit confirmation and delete only
then. Remote replicas must be cleared before a different account can sync.
