package org.xvm.cursor

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import java.lang.classfile.*
import java.lang.classfile.instruction.*

/**
 * Maps the standard JEP 466 (ClassFile API) onto the Cursor Matryoshka aliases.
 * It resolves CompoundElements into ChildCursors and SimpleElements into RowVecs.
 */
object Jep466Cursor {

    // Helper alias for simplicity
    private typealias ClassElementList = List<ClassElement>

    /**
     * Parses a raw byte array representing a ClassFile into a JEP 466 Virtual Cursor.
     */
    fun parse(bytes: ByteArray): Cursor {
        // Uses JEP 466 ClassFile.of() to parse
        val classModel = ClassFile.of().parse(bytes)
        
        // The root cursor is simply the top-level elements (Fields, Methods, Attributes)
        return buildElementCursor(classModel.elements())
    }

    private fun buildElementCursor(elements: List<ClassfileElement>): Cursor {
        val total = elements.size
        return total j { i ->
            elementToRow(elements[i])
        }
    }

    private fun elementToRow(element: ClassfileElement): RowVec {
        // A RowVec expects 4 columns (open, close, tag, kids) based on our Confix alignment
        // Since JEP 466 abstracts byte offsets heavily unless queried specifically, 
        // we'll emulate the geometry for now or use the virtual blackboard approach.
        
        val kidsCursor: Cursor = if (element is CompoundElement<*>) {
            buildElementCursor(element.elements())
        } else {
            0 j { error("No children") }
        }

        // We return a simple RowVec matching the Confix structure
        // 0: open (dummy 0 for now)
        // 1: close (dummy 0 for now)
        // 2: tag (IoObject as a proxy)
        // 3: kids (ChildCursor)
        @Suppress("UNCHECKED_CAST")
        return (4 j { c: Int ->
            when(c) {
                0 -> 0 as Any?
                1 -> 0 as Any?
                2 -> IOMemento.IoObject as Any?
                3 -> kidsCursor as Any?
                else -> error("Out of bounds")
            }
        }) as RowVec
    }
}
