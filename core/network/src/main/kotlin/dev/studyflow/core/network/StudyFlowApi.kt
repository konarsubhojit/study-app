package dev.studyflow.core.network

import dev.studyflow.core.network.model.AccountDeletionReceiptDto
import dev.studyflow.core.network.model.AuthTokensDto
import dev.studyflow.core.network.model.PasskeyRegistrationChallengeDto
import dev.studyflow.core.network.model.PasskeyRegistrationDto
import dev.studyflow.core.network.model.PasskeyRegistrationRequestDto
import dev.studyflow.core.network.model.SignInChallengeDto
import dev.studyflow.core.network.model.SignInCredentialDto
import dev.studyflow.core.network.model.StudySessionDto
import dev.studyflow.core.network.model.SubjectDto
import dev.studyflow.core.network.model.SyncDeltaDto
import dev.studyflow.core.network.model.SyncPushRequestDto
import dev.studyflow.core.network.model.SyncPushResponseDto
import dev.studyflow.core.network.model.TaskDto

/**
 * The typed contract between the app and the StudyFlow backend (issue #63).
 *
 * One function per operation in `docs/api/openapi.yaml`. Repositories depend on this interface, so
 * a feature test swaps in a fake without a socket in sight, and no layer above the data layer ever
 * sees an [io.ktor.client.HttpClient].
 */
public interface StudyFlowApi {
    /** `POST /v1/auth/signin/challenge` — creates the short-lived WebAuthn challenge. */
    public suspend fun beginSignIn(): ApiResult<SignInChallengeDto>

    /** `POST /v1/auth/signin` — exchanges a server-verifiable credential for a token pair. */
    public suspend fun signIn(credential: SignInCredentialDto): ApiResult<AuthTokensDto>

    /**
     * `POST /v1/auth/passkey/registration/challenge` — creation options for a new passkey
     * (issue #158).
     *
     * Requires a signed-in caller: the passkey is attached to the account the bearer token names,
     * because account creation is the Google sign-in path, not this one.
     */
    public suspend fun beginPasskeyRegistration(): ApiResult<PasskeyRegistrationChallengeDto>

    /**
     * `POST /v1/auth/passkey/registration` — attaches the created passkey to that account.
     *
     * Not idempotent: replaying it answers `credential_already_registered` rather than storing a
     * second copy, because the challenge it carries is spent by the first attempt.
     */
    public suspend fun registerPasskey(request: PasskeyRegistrationRequestDto): ApiResult<PasskeyRegistrationDto>

    /** `GET /v1/subjects` — every subject the signed-in user owns. */
    public suspend fun subjects(): ApiResult<List<SubjectDto>>

    /**
     * `GET /v1/tasks` — the user's study tasks.
     *
     * @param subjectId restricts the result to one subject when given.
     */
    public suspend fun tasks(subjectId: String? = null): ApiResult<List<TaskDto>>

    /** `POST /v1/sessions` — uploads a finished study session and returns the stored copy. */
    public suspend fun uploadSession(session: StudySessionDto): ApiResult<StudySessionDto>

    /**
     * `POST /v1/sync/sessions` — sends a batch of local session changes (issue #55).
     *
     * Idempotent by contract: the server resolves each change by last-write-wins, so replaying a
     * batch it already applied changes nothing. That is what lets a client acknowledge its queue
     * only after the response arrives, without risking a duplicate session if it never does.
     */
    public suspend fun pushSessionChanges(request: SyncPushRequestDto): ApiResult<SyncPushResponseDto>

    /**
     * `GET /v1/sync/sessions` — the session changes recorded after [cursor], oldest first.
     *
     * @param cursor `null` on a first sync, which asks for the whole history.
     * @param limit maximum number of changes to return; the server may return fewer.
     */
    public suspend fun sessionChanges(
        cursor: String?,
        limit: Int,
    ): ApiResult<SyncDeltaDto>

    /**
     * `DELETE /v1/account` — deletes the account, its rows and its stored objects (issue #78).
     *
     * Idempotent by contract, so a client that never learned whether its request arrived may send
     * it again; the client only clears its local data once this succeeds.
     */
    public suspend fun deleteAccount(): ApiResult<AccountDeletionReceiptDto>
}
