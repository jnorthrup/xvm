package org.xvm.cursor

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

/**
 * Maps the standard JEP 466 (ClassFile API) onto the Cursor Matryoshka aliases.
 *
 * NOTE: JEP 466 `elements()` / `ClassfileElement` API is NOT available in
 * JDK 25 — the actual release uses ClassFileBuilder/ClassReader directly.
 * This file is a stub pending the real implementation.
 * Build passes when this file is present; JEP 466 ClassFile API integration
 * requires a separate implementation pass.
 */
object Jep466Cursor {

    /**
     * Parses a raw byte array representing a ClassFile into a JEP 466 Virtual Cursor.
     *
     * STUB: actual implementation requires ClassFileBuilder + ClassReader traversal.
     * For now returns an empty cursor — real integration is a separate task.
     */
    fun parse(bytes: ByteArray): Cursor {
        // TODO: implement with actual ClassFileBuilder/ClassReader
        return 0 j { _: Int -> throw IndexOutOfBoundsException("Jep466Cursor stub") }
    }
}