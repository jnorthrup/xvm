@file:Suppress("TOPLEVEL_TYPEALIASES_ONLY")
package org.xvm.cursor

// Re-export TrikeShed cursor types so org.xvm.cursor code and tests resolve them
// without explicit borg.trikeshed.cursor imports.
typealias Cursor = borg.trikeshed.cursor.Cursor
typealias RowVec = borg.trikeshed.cursor.RowVec
