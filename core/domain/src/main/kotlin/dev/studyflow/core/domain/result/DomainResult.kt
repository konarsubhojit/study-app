package dev.studyflow.core.domain.result

import java.io.IOException

/** A value returned by domain work, or a failure that is safe to present to a user. */
public sealed interface DomainResult<out T> {
    public data class Success<T>(
        public val value: T,
    ) : DomainResult<T>

    public data class Failure(
        public val error: DomainError,
    ) : DomainResult<Nothing>
}

/** The closed set of failures a screen can render. */
public sealed interface DomainError {
    public val message: UserMessage

    public data object Network : DomainError {
        override val message: UserMessage = UserMessage.Network
    }

    public data object Storage : DomainError {
        override val message: UserMessage = UserMessage.Storage
    }

    public data object Permission : DomainError {
        override val message: UserMessage = UserMessage.Permission
    }

    public data object Validation : DomainError {
        override val message: UserMessage = UserMessage.Validation
    }

    public data object Unknown : DomainError {
        override val message: UserMessage = UserMessage.Unknown
    }
}

/** Stable copy keys for UI resource lookup; exception details are deliberately excluded. */
public enum class UserMessage {
    Network,
    Storage,
    Permission,
    Validation,
    Unknown,
}

/** Marks a failure while reading or writing app-managed persisted data. */
public class StorageException(
    message: String? = null,
) : Exception(message)

/** Converts implementation exceptions to the domain's user-safe error vocabulary. */
public fun Throwable.toDomainError(): DomainError =
    when (this) {
        is StorageException -> DomainError.Storage
        is SecurityException -> DomainError.Permission
        is IllegalArgumentException -> DomainError.Validation
        is IOException -> DomainError.Network
        else -> DomainError.Unknown
    }
