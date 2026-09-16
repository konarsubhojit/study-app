# 7. API contract and network client

- Status: accepted
- Date: 2026-09-16

## Context

The app and the backend are built and released separately, so every assumption one makes about the
other is a guess until something fails in production. The failure modes that matter are all
user-visible and all late:

- A field the server adds breaks parsing on a phone that installed the app three months ago.
- A raw `503` reaches the UI, and the user is told a number they cannot act on.
- An access token expires mid-session and every screen shows "sign in again" until the app is
  restarted.
- A server hiccup makes every installed client retry at the same instant and knocks the recovering
  server over again.
- A build that the server no longer supports keeps calling and keeps failing, with no way out.

These are all contract problems, and a contract nobody wrote down cannot be checked.

## Decision

**`docs/api/openapi.yaml` is the source of truth.** It is checked in, it carries the versioning and
deprecation policy in its own description, and `OpenApiContractTest` fails the build when the client
calls an operation the document does not describe. Endpoints are values (`ApiEndpoint`) rather than
string literals so that comparison is possible at all.

**Models are hand-written with kotlinx.serialization, not generated.** The generated-code route
costs a code-generation step in every build and produces models nobody can read in review, for four
small DTOs. `StudyFlowJson` sets `ignoreUnknownKeys = true`, which is the forward-compatibility
promise: an additive server change cannot break an installed client, and `ForwardCompatibilityTest`
proves it.

**Ktor, with the engine injected.** The app passes OkHttp, tests pass `MockEngine`, so the contract
tests exercise the production client — plugins, headers, retries and all — rather than a stand-in.
That also means `:core:network` is a plain JVM module and its whole test suite runs in seconds.

**Every failure becomes an `ApiError` carrying a `UserFacingMessage`.** `expectSuccess` stays off so
that Ktor's own status exception cannot bypass `ApiErrorMapper`, which is the only place in the app
allowed to know what a status code means. No enum entry contains a digit, so an HTTP code cannot
reach the UI even by accident.

**Retries are jittered, capped, and limited to idempotent requests.** Replaying a `POST /v1/sessions`
that already succeeded would duplicate the user's study data, which is worse than showing an error;
jitter exists so that a fleet of phones does not retry in lockstep.

**Token refresh lives in the client, once.** Ktor's `Auth` plugin refreshes on a 401 and replays the
request. The refresh call uses its own bare client, so it can never recurse into its own 401
handling, and a spent refresh token clears the store rather than looping.

**The minimum supported client is checked on every response.** The server advertises it in
`X-Minimum-Client-Version` before it starts answering `426`, so the app can offer a friendly upgrade
prompt rather than a dead end.

## Consequences

- A contract change starts in the specification. Forgetting it fails the build, not the user.
- Feature tests use `FakeStudyFlowBackend` from `:core:testing`, the same fake the contract tests
  use, so a repository test covers real serialization and error mapping.
- The base URL comes from `BuildConfig`, so running against a local mock backend is
  `-Pstudyflow.apiBaseUrl=http://10.0.2.2:8080` and not an edit. Cleartext for that host is allowed
  in the debug source set only, so the exception cannot ship.
- Tokens currently live in memory; the encrypted store arrives with `:core:datastore` (issue #20)
  and only `NetworkModule` changes when it does.
- `:core:testing` now depends on `:core:network`, so the fake and the client cannot drift apart.
