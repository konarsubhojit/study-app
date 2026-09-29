package dev.studyflow.feature.auth

/**
 * Whether sign-in offers a passkey alongside Google.
 *
 * Off by default: a passkey only works once the relying-party domain serves a correct
 * `assetlinks.json` for this build's signing certificate, and until then the passkey option is at
 * best noise in the sheet. A release opts in with `-Pstudyflow.passkeySignInEnabled=true` (or
 * `STUDYFLOW_PASSKEY_SIGN_IN_ENABLED=true`, which CI sets from the repository variable of the same
 * name). While it is off, sign-in skips the passkey challenge entirely and asks for Google only.
 */
public data class PasskeySignInConfig(
    val enabled: Boolean,
)
