package dev.studyflow.core.network.version

/**
 * A `major.minor.patch` client version, compared the way humans expect (issue #63).
 *
 * String comparison gets this wrong — `"1.10.0" < "1.9.0"` — and getting it wrong means either
 * locking out users who are up to date or letting a client the server no longer supports keep
 * calling. So the comparison is numeric and the parser is total: an unparseable version is `null`
 * rather than a guess.
 */
public data class ClientVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ClientVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "ClientVersion components must not be negative, was $major.$minor.$patch"
        }
    }

    override fun compareTo(other: ClientVersion): Int =
        compareValuesBy(this, other, ClientVersion::major, ClientVersion::minor, ClientVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    public companion object {
        /**
         * Parses `major.minor.patch`, tolerating a missing minor or patch and a build suffix.
         *
         * @return the version, or `null` when [raw] is not a version at all.
         */
        public fun parseOrNull(raw: String?): ClientVersion? {
            val trimmed = raw?.trim()?.substringBefore('-')?.substringBefore('+') ?: return null
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split('.')
            if (parts.size > 3) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            if (numbers.any { it < 0 }) return null
            return ClientVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
            )
        }
    }
}

/**
 * Decides whether this build may keep talking to the server.
 *
 * The server advertises the oldest client it still serves in `X-Minimum-Client-Version` on every
 * response, so the app learns it is about to be cut off *before* it starts getting
 * `426 Upgrade Required`, and can offer a friendly upgrade path rather than a dead end.
 */
public object MinimumClientPolicy {
    /**
     * @param current the version of this build.
     * @param minimumSupported the header value the server sent, if any.
     * @return true when the app must stop calling the API and show the upgrade screen.
     */
    public fun isUpgradeRequired(current: ClientVersion, minimumSupported: ClientVersion?): Boolean =
        minimumSupported != null && current < minimumSupported
}
