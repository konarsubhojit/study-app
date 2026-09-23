package dev.studyflow.core.storage.lifecycle

import dev.studyflow.core.domain.lifecycle.DataEraser
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore

/** The keys an account deletion has to remove from the object store. */
public fun interface StoredObjectKeys {
    /** Every key this device knows the user owns, materials and their thumbnails alike. */
    public suspend fun keys(): List<ObjectKey>
}

/**
 * Deletes the user's stored objects (issue #78).
 *
 * The server erases the objects it owns when it accepts the deletion, so this is not the only
 * defence — but a key the server somehow missed is a copy of the user's lecture notes outliving
 * their account, and the client knows exactly which keys those are. `ObjectStore.delete` is
 * documented to succeed on a key that is already gone, which is what makes doing both safe.
 */
public class ObjectStoreEraser(
    private val objectStore: ObjectStore,
    private val storedKeys: StoredObjectKeys,
) : DataEraser {
    override val name: String = "stored files"

    override suspend fun erase() {
        storedKeys.keys().forEach { key -> objectStore.delete(key) }
    }
}
