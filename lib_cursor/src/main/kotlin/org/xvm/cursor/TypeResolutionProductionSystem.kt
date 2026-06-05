package org.xvm.cursor

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * TypeResolutionProductionSystem — Singleton triple-tier coordinator
 * for the compiler's type-resolution state.
 *
 * Architecture (from LDA trigram analysis):
 *
 *   Tier 1: Branching Rules   — thread-local, unsynchronized
 *     Entry points: if→typeArg, if→typeRequired
 *     DSL coordinators: isA, union, intersection, calculateRelation, resolveTypedefs
 *
 *   Tier 2: Pool Context      — shared, journal-backed (TypedefResolutionSeries)
 *     ConstantPool cache, TypeConstant interning, resolveType bindings
 *
 *   Tier 3: Runtime Resolution — thread-local frame state
 *     frame→poolContext bounds, dynamic invocation context
 *
 * Mutability contract:
 *   - Tier 1 and 3 are thread-local (no synchronization)
 *   - Tier 2 mutations are recorded through TypedefResolutionSeries (journalled, WAL-ringed)
 *   - Every DSL coordination point transitions through record() before yielding
 */
object TypeResolutionProductionSystem {

    // ── Coordination Point Kinds ──────────────────────────────────────────

    /**
     * The five DSL coordination points identified by the LDA trigram analysis.
     * Each represents a mutability boundary where the type system transitions
     * from a mutable builder scope to an immutable TypeConstant.
     */
    enum class CoordinationPoint(val builderName: String) {
        IS_A("subsumption"),
        RESOLVE_TYPEDEFS("typedefResolution"),
        CALCULATE_RELATION("relationBuilder"),
        UNION("unionBuilder"),
        INTERSECTION("intersectionBuilder"),
    }

    // ── Tier 1: Branching Rules (Thread-Local) ────────────────────────────

    /**
     * Thread-local branching context.
     * Each compiler thread maintains its own stack of branching decisions
     * (the if→typeArg / if→typeRequired trigrams) without synchronization.
     */
    class BranchingContext {
        /** Active branch stack — pushed on entry, popped on resolution. */
        private val branchStack = ArrayDeque<BranchEntry>()

        /** Total branches evaluated in this context's lifetime. */
        var branchCount: Long = 0L
            private set

        fun pushBranch(coordination: CoordinationPoint, typeArg: String, typeRequired: String) {
            branchStack.addLast(BranchEntry(coordination, typeArg, typeRequired, System.nanoTime()))
            branchCount++
        }

        fun popBranch(): BranchEntry? = branchStack.removeLastOrNull()

        fun peekBranch(): BranchEntry? = branchStack.lastOrNull()

        val depth: Int get() = branchStack.size

        fun clear() {
            branchStack.clear()
            branchCount = 0L
        }
    }

    data class BranchEntry(
        val coordination: CoordinationPoint,
        val typeArg: String,
        val typeRequired: String,
        val entryNano: Long,
    )

    private val branchingContexts = ThreadLocal.withInitial { BranchingContext() }

    /** Current thread's Tier 1 branching context. */
    @JvmStatic
    fun branching(): BranchingContext = branchingContexts.get()

    // ── Tier 2: Pool Context (Shared, Journalled) ─────────────────────────

    /**
     * Pool-level resolution cache.
     * Every resolved TypeConstant binding is interned here by poolId.
     * Mutations are journalled through [TypedefResolutionSeries].
     */
    private val resolvedBindings = ConcurrentHashMap<Int, ResolvedBinding>()
    private val bindingSeq = AtomicLong(0)

    data class ResolvedBinding(
        val bindingId: Long,
        val poolId: Int,
        val coordination: CoordinationPoint,
        val resolvedTypeName: String,
        val resolvedTypePoolId: Int,
        val nano: Long,
    )

    /**
     * Record a successful type resolution into the pool context.
     * This is the Tier 2 synchronization point — the only place where
     * shared mutable state is modified.
     *
     * @return the journalled factId from TypedefResolutionSeries
     */
    @JvmStatic
    fun resolveBinding(
        poolId: Int,
        siteOrd: Int,
        coordination: CoordinationPoint,
        className: String,
        resolvedTypeName: String,
        resolvedTypePoolId: Int,
    ): Long {
        val bindingId = bindingSeq.getAndIncrement()
        val nano = System.nanoTime()

        // Journal the event through the existing WAL infrastructure
        val factId = TypedefResolutionSeries.record(
            poolId, siteOrd, className, coordination.builderName, true
        )

        // Cache the resolved binding
        resolvedBindings[poolId] = ResolvedBinding(
            bindingId, poolId, coordination, resolvedTypeName, resolvedTypePoolId, nano
        )

        return factId
    }

    /**
     * Lookup a previously resolved binding by poolId.
     */
    @JvmStatic
    fun lookupBinding(poolId: Int): ResolvedBinding? = resolvedBindings[poolId]

    /**
     * Query all resolved bindings for a given coordination point.
     */
    @JvmStatic
    fun bindingsByCoordination(coordination: CoordinationPoint): List<ResolvedBinding> =
        resolvedBindings.values.filter { it.coordination == coordination }.sortedBy { it.bindingId }

    // ── Tier 3: Runtime Resolution (Thread-Local Frame) ───────────────────

    /**
     * Runtime frame context — thread-local state for dynamic type resolution.
     * Represents the frame→poolContext→isA breadcrumb trail.
     */
    class FrameContext {
        private val frameStack = ArrayDeque<FrameEntry>()
        var frameCount: Long = 0L
            private set

        fun pushFrame(poolContextId: Int, invokerName: String) {
            frameStack.addLast(FrameEntry(poolContextId, invokerName, System.nanoTime()))
            frameCount++
        }

        fun popFrame(): FrameEntry? = frameStack.removeLastOrNull()

        fun peekFrame(): FrameEntry? = frameStack.lastOrNull()

        val depth: Int get() = frameStack.size

        /**
         * Delegates the current frame's type check to Tier 2 pool context.
         * This is the Tier3→Tier2 delegation edge from the migration diagram.
         */
        fun delegateToPool(poolId: Int): ResolvedBinding? = lookupBinding(poolId)

        fun clear() {
            frameStack.clear()
            frameCount = 0L
        }
    }

    data class FrameEntry(
        val poolContextId: Int,
        val invokerName: String,
        val entryNano: Long,
    )

    private val frameContexts = ThreadLocal.withInitial { FrameContext() }

    /** Current thread's Tier 3 runtime frame context. */
    @JvmStatic
    fun frame(): FrameContext = frameContexts.get()

    // ── Relational Join Views ─────────────────────────────────────────────

    /**
     * Cursor view over the Tier 2 resolved bindings.
     * Columns: bindingId, poolId, coordination, resolvedTypeName, resolvedTypePoolId, nano
     */
    @JvmStatic
    fun poolContextCursor(): Cursor {
        val bindings = resolvedBindings.values.sortedBy { it.bindingId }
        return bindings.size j { idx: Int ->
            val b = bindings[idx]
            bindingRowVec(b)
        }
    }

    /**
     * Relational join: pool context bindings × taxonomy coordinate rows.
     * Joins on poolId, producing a cursor where each row contains
     * both the resolved binding fields and the taxonomy coordinate fields.
     */
    @JvmStatic
    fun poolTaxonomyJoin(taxonomy: ClassFileTaxonomy): Cursor {
        val joined = mutableListOf<Pair<ResolvedBinding, ClassFileTaxonomy.CoordinateRow>>()
        for (binding in resolvedBindings.values) {
            val coordRow = taxonomy.lookupByPoolId(binding.poolId)
            if (coordRow != null) {
                joined.add(binding to coordRow)
            }
        }
        joined.sortBy { it.first.bindingId }
        return joined.size j { idx: Int ->
            val (b, c) = joined[idx]
            joinedRowVec(b, c)
        }
    }

    /**
     * Coordination histogram: count of resolved bindings per coordination point.
     * Returns a Cursor with columns: coordination, builderName, count.
     */
    @JvmStatic
    fun coordinationHistogram(): Cursor {
        val counts = resolvedBindings.values.groupBy { it.coordination }
        val entries = CoordinationPoint.entries.map { cp ->
            Triple(cp.name, cp.builderName, counts[cp]?.size ?: 0)
        }
        return entries.size j { idx: Int ->
            val (name, builder, count) = entries[idx]
            histogramRowVec(name, builder, count)
        }
    }

    // ── Aggregate State ───────────────────────────────────────────────────

    /**
     * Snapshot of the full production system state.
     */
    data class State(
        val totalJournalFacts: Int,
        val resolvedBindingCount: Int,
        val coordinationCounts: Map<CoordinationPoint, Int>,
        val stringPoolSize: Int,
    )

    @JvmStatic
    fun state(): State {
        val counts = resolvedBindings.values.groupBy { it.coordination }
            .mapValues { it.value.size }
        return State(
            totalJournalFacts = TypedefResolutionSeries.size(),
            resolvedBindingCount = resolvedBindings.size,
            coordinationCounts = counts,
            stringPoolSize = StringPool.size(),
        )
    }

    /**
     * Correlate a taxonomy row — records the coordination event and
     * caches the binding. Backward-compatible with the original stub API.
     */
    @JvmStatic
    fun correlateTaxonomyRow(tax: ClassFileTaxonomy, rowIdx: Int) {
        val row = tax.rowAt(rowIdx)
        resolveBinding(
            poolId = row.poolId,
            siteOrd = row.pointcutKind,
            coordination = CoordinationPoint.IS_A,
            className = row.ownerType,
            resolvedTypeName = row.xvmTypeInfo.ifEmpty { row.descriptor },
            resolvedTypePoolId = StringPool.intern(row.xvmTypeInfo.ifEmpty { row.descriptor }),
        )
    }

    @JvmStatic
    fun correlationCount(): Int = resolvedBindings.size

    // ── Reset ─────────────────────────────────────────────────────────────

    @JvmStatic
    fun reset() {
        resolvedBindings.clear()
        bindingSeq.set(0)
        TypedefResolutionSeries.reset()
        branchingContexts.get().clear()
        frameContexts.get().clear()
    }

    // ── RowVec Factories (private) ────────────────────────────────────────

    private fun bindingRowVec(b: ResolvedBinding): RowVec {
        val cells = arrayOf<Any?>(
            b.bindingId, b.poolId, b.coordination.name,
            b.resolvedTypeName, b.resolvedTypePoolId, b.nano
        )
        val metas = arrayOf(
            ColumnMeta("bindingId", IOMemento.IoLong),
            ColumnMeta("poolId", IOMemento.IoInt),
            ColumnMeta("coordination", IOMemento.IoString),
            ColumnMeta("resolvedTypeName", IOMemento.IoString),
            ColumnMeta("resolvedTypePoolId", IOMemento.IoInt),
            ColumnMeta("nano", IOMemento.IoLong),
        )
        return cells.size j { col: Int ->
            LocalJoinCell(cells[col]) { metas[col] }
        }
    }

    private fun joinedRowVec(b: ResolvedBinding, c: ClassFileTaxonomy.CoordinateRow): RowVec {
        val cells = arrayOf<Any?>(
            b.bindingId, b.poolId, b.coordination.name,
            b.resolvedTypeName, b.resolvedTypePoolId, b.nano,
            c.symbolName, c.ownerType, c.methodOrField, c.descriptor,
            c.pointcutKind, c.xvmTypeInfo
        )
        val metas = arrayOf(
            ColumnMeta("bindingId", IOMemento.IoLong),
            ColumnMeta("poolId", IOMemento.IoInt),
            ColumnMeta("coordination", IOMemento.IoString),
            ColumnMeta("resolvedTypeName", IOMemento.IoString),
            ColumnMeta("resolvedTypePoolId", IOMemento.IoInt),
            ColumnMeta("nano", IOMemento.IoLong),
            ColumnMeta("symbolName", IOMemento.IoString),
            ColumnMeta("ownerType", IOMemento.IoString),
            ColumnMeta("methodOrField", IOMemento.IoString),
            ColumnMeta("descriptor", IOMemento.IoString),
            ColumnMeta("pointcutKind", IOMemento.IoInt),
            ColumnMeta("xvmTypeInfo", IOMemento.IoString),
        )
        return cells.size j { col: Int ->
            LocalJoinCell(cells[col]) { metas[col] }
        }
    }

    private fun histogramRowVec(name: String, builder: String, count: Int): RowVec {
        val cells = arrayOf<Any?>(name, builder, count)
        val metas = arrayOf(
            ColumnMeta("coordination", IOMemento.IoString),
            ColumnMeta("builderName", IOMemento.IoString),
            ColumnMeta("count", IOMemento.IoInt),
        )
        return cells.size j { col: Int ->
            LocalJoinCell(cells[col]) { metas[col] }
        }
    }
}

/**
 * Minimal local Join cell — avoids importing the full TrikeShed cell() factory.
 */
private class LocalJoinCell<A, B>(override val a: A, override val b: B) : Join<A, B>
