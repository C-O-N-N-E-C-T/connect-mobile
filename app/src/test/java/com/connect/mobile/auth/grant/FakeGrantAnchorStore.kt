package com.connect.mobile.auth.grant

/** Bellekte çalışan sahte çapa deposu. */
class FakeGrantAnchorStore : GrantAnchorStore {
    var anchor: GrantAnchor? = null
    var readError: Exception? = null
    var writes = 0
    var clears = 0

    override suspend fun read(): GrantAnchor? {
        readError?.let { throw it }
        return anchor
    }

    override suspend fun write(anchor: GrantAnchor) {
        writes++
        this.anchor = anchor
    }

    override suspend fun clear() {
        clears++
        anchor = null
    }
}
