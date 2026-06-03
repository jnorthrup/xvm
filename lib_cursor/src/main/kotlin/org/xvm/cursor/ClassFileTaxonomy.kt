package org.xvm.cursor

/**
 * ClassFileTaxonomy — Confix-based Facetted ClassFile browse/registry.
 *
 * Ingests minimal coordinate rows from classfile scans and exposes them as:
 *   - a typed registry (register / rowAt / lookupByPoolId / filterBy*)
 *   - a TaxonomyCursor projection with PointcutFacet-tagged columns for
 *     Confix/TrikeShed lazy navigation.
 *
 * Boundary rule: this class is the extraction/projection surface only.
 * TrikeShed owns cursor algebra; ConfixCursor owns format parsing.
 * Java emitters feed coordinate rows here; Kotlin navigates via asCursor().
 */
class ClassFileTaxonomy {

    /**
     * Minimal wire-friendly coordinate row.
     * Fields are primitive at the batch boundary — strings live in the pool.
     */
    data class CoordinateRow(
        val symbolName: String,       // "owner.method" or "owner.field"
        val ownerType: String,        // class/type name
        val methodOrField: String,    // method or field name
        val classfileCoord: String,   // "owner#method" or similar coordinate
        val cpIndex: Int,             // constant-pool index (-1 if unavailable)
        val descriptor: String,       // JVM descriptor or signature
        val xvmTypeInfo: String,      // XVM type / org.xtc evidence, or ""
        val pointcutKind: Int,        // opcode byte (0x10..0xA8)
        val poolId: Int,              // stable intern-pool / hash id
    )

    // ── Registry ──────────────────────────────────────────────────────────

    private val rows = ArrayList<CoordinateRow>()

    val size: Int get() = rows.size

    fun register(row: CoordinateRow) {
        rows.add(row)
    }

    fun rowAt(index: Int): CoordinateRow = rows[index]

    fun lookupByPoolId(poolId: Int): CoordinateRow? =
        rows.firstOrNull { it.poolId == poolId }

    fun filterByKind(kind: Int): ClassFileTaxonomy {
        val sub = ClassFileTaxonomy()
        rows.filter { it.pointcutKind == kind }.forEach(sub::register)
        return sub
    }

    fun filterByOwner(owner: String): ClassFileTaxonomy {
        val sub = ClassFileTaxonomy()
        rows.filter { it.ownerType == owner }.forEach(sub::register)
        return sub
    }

    // ── Cursor projection ─────────────────────────────────────────────────

    fun asCursor(): TaxonomyCursor = TaxonomyCursor(rows.toList())

    /**
     * TaxonomyCursor — lazily-navigable ConfixRow-style view of taxonomy rows.
     *
     * Column schema (9 columns, with PointcutFacet tags):
     *   0  symbolName       — SymbolName
     *   1  ownerType        — TypeInfo
     *   2  methodOrField    — SymbolName
     *   3  classfileCoord   — ClassfileCoordinate
     *   4  cpIndex          — XvmCoordinate
     *   5  descriptor       — Unfaceted
     *   6  xvmTypeInfo      — XvmCoordinate
     *   7  pointcutKind     — Unfaceted
     *   8  poolId           — StringPool
     */
    class TaxonomyCursor(private val rows: List<CoordinateRow>) {

        val size: Int get() = rows.size

        fun rowAt(index: Int): TaxonomyRow = TaxonomyRow(rows[index])

        fun columnMeta(name: String): TaxonomyColumnMeta =
            SCHEMA[name] ?: error("unknown column: $name")

        companion object {
            val SCHEMA: Map<String, TaxonomyColumnMeta> = mapOf(
                "symbolName"     to TaxonomyColumnMeta("symbolName",     PointcutFacet.SymbolName),
                "ownerType"      to TaxonomyColumnMeta("ownerType",      PointcutFacet.TypeInfo),
                "methodOrField"  to TaxonomyColumnMeta("methodOrField",  PointcutFacet.SymbolName),
                "classfileCoord" to TaxonomyColumnMeta("classfileCoord", PointcutFacet.ClassfileCoordinate),
                "cpIndex"        to TaxonomyColumnMeta("cpIndex",        PointcutFacet.XvmCoordinate),
                "descriptor"     to TaxonomyColumnMeta("descriptor",     PointcutFacet.Unfaceted),
                "xvmTypeInfo"    to TaxonomyColumnMeta("xvmTypeInfo",    PointcutFacet.XvmCoordinate),
                "pointcutKind"   to TaxonomyColumnMeta("pointcutKind",   PointcutFacet.Unfaceted),
                "poolId"         to TaxonomyColumnMeta("poolId",         PointcutFacet.StringPool),
            )
        }
    }

    data class TaxonomyColumnMeta(val name: String, val facet: PointcutFacet)

    /**
     * TaxonomyRow — map-style access to a CoordinateRow.
     */
    class TaxonomyRow(private val row: CoordinateRow) {
        operator fun get(column: String): Any? = when (column) {
            "symbolName"     -> row.symbolName
            "ownerType"      -> row.ownerType
            "methodOrField"  -> row.methodOrField
            "classfileCoord" -> row.classfileCoord
            "cpIndex"        -> row.cpIndex
            "descriptor"     -> row.descriptor
            "xvmTypeInfo"    -> row.xvmTypeInfo
            "pointcutKind"   -> row.pointcutKind
            "poolId"         -> row.poolId
            else             -> null
        }

        val symbolName: String    get() = row.symbolName
        val pointcutKind: Int     get() = row.pointcutKind
        val poolId: Int           get() = row.poolId
    }
}
