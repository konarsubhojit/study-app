package dev.studyflow.core.common.network

import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/** Engines may wrap DNS failures; inspect types only, never request-bearing exception text. */
public fun Throwable.isHostResolutionFailure(): Boolean {
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (current is UnknownHostException || current is UnresolvedAddressException) return true
        current = current.cause
    }
    return false
}
