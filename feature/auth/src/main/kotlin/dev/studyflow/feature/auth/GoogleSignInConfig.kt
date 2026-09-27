package dev.studyflow.feature.auth

/**
 * The Google OAuth client id [CredentialManagerSignInClient] asks for an ID token with.
 *
 * A type rather than a bare `String` so the graph cannot hand the Credential Manager request some
 * other configured string by accident. [serverClientId] is the **Web application** client id, not
 * the Android one: the server verifies the ID token's audience against the same value, and the two
 * must match or every sign-in is rejected as an invalid credential.
 *
 * The value is public — it is part of the request Google receives — and ships in the APK by
 * design.
 */
public data class GoogleSignInConfig(
    val serverClientId: String,
) {
    init {
        require(serverClientId.isNotBlank()) { "GoogleSignInConfig.serverClientId must not be blank" }
    }
}
