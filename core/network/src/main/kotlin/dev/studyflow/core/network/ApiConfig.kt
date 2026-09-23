package dev.studyflow.core.network

import dev.studyflow.core.network.retry.RetryPolicy
import dev.studyflow.core.network.version.ClientVersion
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Every operation the client is allowed to call, spelled the way `docs/api/openapi.yaml` spells it
 * (issue #63).
 *
 * Endpoints are values rather than string literals scattered through the client so that
 * `OpenApiContractTest` can compare this list against the specification. A path typo, or an
 * operation nobody documented, then fails the build instead of returning 404 on a user's phone.
 */
public enum class ApiEndpoint(
    public val method: String,
    public val path: String,
) {
    RefreshTokens("post", "/$API_VERSION/auth/refresh"),
    ListSubjects("get", "/$API_VERSION/subjects"),
    ListTasks("get", "/$API_VERSION/tasks"),
    UploadSession("post", "/$API_VERSION/sessions"),

    /** Sends a batch of local session changes; see `SyncEngine` in `:core:domain`. */
    PushSessionChanges("post", "/$API_VERSION/sync/sessions"),

    /** Reads the session changes recorded after a cursor. */
    PullSessionChanges("get", "/$API_VERSION/sync/sessions"),

    /** Deletes the signed-in account, its rows and its stored objects (issue #78). */
    DeleteAccount("delete", "/$API_VERSION/account"),
    ;

    /** Only a request that can be replayed without duplicating the user's data may be retried. */
    public val isIdempotent: Boolean get() = method == "get"
}

/** The major API version this build speaks; a breaking change ships as a new one alongside it. */
public const val API_VERSION: String = "v1"

/** Header carrying this build's version, so the server can measure its supported population. */
public const val CLIENT_VERSION_HEADER: String = "X-Client-Version"

/** Header carrying the oldest client version the server still serves. */
public const val MINIMUM_CLIENT_VERSION_HEADER: String = "X-Minimum-Client-Version"

/**
 * Everything the client needs that differs between a developer's laptop and the Play Store build
 * (issue #63).
 *
 * [baseUrl] is the reason "run against a local mock backend with no code change" works: the value
 * comes from the build, so pointing the app at `http://10.0.2.2:8080` is a Gradle property, not an
 * edit.
 *
 * The timeouts are deliberately finite. A request without one hangs until the user force-quits,
 * which looks like the app has frozen rather than like the network is slow.
 */
public data class ApiConfig(
    val baseUrl: String,
    val clientVersion: ClientVersion,
    val connectTimeout: Duration = 10.seconds,
    val socketTimeout: Duration = 20.seconds,
    val requestTimeout: Duration = 30.seconds,
    val retry: RetryPolicy = RetryPolicy(),
) {
    init {
        require(baseUrl.isNotBlank()) { "ApiConfig.baseUrl must not be blank" }
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "ApiConfig.baseUrl must be an absolute http(s) URL, was $baseUrl"
        }
        require(connectTimeout > Duration.ZERO && socketTimeout > Duration.ZERO && requestTimeout > Duration.ZERO) {
            "ApiConfig timeouts must be positive"
        }
    }

    /** [baseUrl] without a trailing slash, so joining an [ApiEndpoint.path] never doubles it. */
    public val normalizedBaseUrl: String = baseUrl.trimEnd('/')

    /** Absolute URL of [endpoint]. */
    public fun urlOf(endpoint: ApiEndpoint): String = normalizedBaseUrl + endpoint.path
}
