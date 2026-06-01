# lib_cursor TODO

## Delivered

### Pointcut infrastructure (lib_cursor only, zero new files in javatools)
- ServiceContext.PointcutHook (inner interface) decouples javatools from lib_cursor at compile time
- VmPointcutPublisher static init wires FieldSynapse into ServiceContext.PointcutHook
- **28 javatools pointcut tests green** — PointcutEndToEndTest(4), ReduxListPointcutTest(8), TypedefCascadeDagReificationTest(5), TypedefCascadeParityTest(11)
- **131 total lib_cursor tests green** — XvmLifecycleTest(30), PointcutDrainTest(14), VmPointcutEmitterTest(21), PointcutEventSemanticsTest(9), PointcutSubscribeTest(6), PointcutReviseTest(5), PointcutObservationTest(3), ClassfilePointcutRewriterTest(11), VmPointcutFirehoseTest(1), ListCtorCowPointcutTest(8), ListDetourTest(2), PointcutCmdlineTest(3), VM Event CRUD(18)
- Tag: C1036794-2071-4E97-BD4A-D9BC1CD001BA

### Cascading stat rollups — 4-tier cascade
- T1 leafScan — per-siteOrd 256-bin counters
- T2 kindMerge — per-kind 9-bin histogram (CALL/ALLOC/RETURN/FIELD/TYPE/ASSERT/LOOP/SYNC/GAP)
- T3 scopeRollup — per-scope 4-bin aggregation (MODULE/PACKAGE/CLASS/METHOD)
- T4 jointHistogram — kind×scope co-occurrence (9×4 flat array)
- TierSnapshot flat-array DTO, no per-row object allocation

### Mapreduce lattice — CascadeLattice
- mapByKind / mapAllKinds — partition snapshots by kind (9 bins)
- mapByScope / mapAllScopes — partition snapshots by scope (4 bins)
- reduceKind / reduceScope — element-wise TierSnapshot merge
- latticeCells — 9×4 long[][] co-occurrence matrix from T4 joint histogram
- reduceLatticeCells — lattice merge via element-wise addition
- routeKind / routeScope / routeCell — column-router name→ordinal lookup

### Lazy table inference
- LazyTypedefCascadeTable wraps eager TypedefCascadeTable
- 6 SoA columns: depth, kind, scope, success, opcode(siteOrd), addr(poolId)
- LazyColumn<T> — Supplier-based deferred compute, GC supplier after materialize
- Hot path (fold/routeOpcode) delegates to backing table, zero lazy overhead
- project(int...) — unmodifiable column view, no copy
- columnRouter(String) — name → LazyColumn lookup
- infer() — field candidates + AVX2 lane assignment (8 int lanes per 256-bit vector)

### Confix facade — json/csv lazy cursor
- ConfixFormat sealed: JSON, YAML, CBOR, CSV
- ConfixRow — lazy per-column values, parsed on first access, cached
- ConfixCursor — lazy Sequence<ConfixRow>, no bulk load
  - columns() — schema detection (CSV header, JSON first-object)
  - rows() — lazy iteration
  - facet(vararg) — column projection → new cursor
  - join(other, on) — faceted hash-join
- JsonParser — zero-dep, handles escapes/nested/numbers
- CsvParser — RFC 4180, quoted fields, type inference
- YAML/CBOR stubbed for TrikeShed parsing

### BlackboardTimeseries → ConfixCursor integration
- parsePcodeJson replaced with ConfixCursor-based parsing
- Top-level function array parsed via ConfixCursor.rows()
- Nested pcode ops parsed via second ConfixCursor on extracted raw array string
- PcodeOp/PcodeVarnode/PcodeFunction data classes unchanged
- runBlackboardTimeseries histograms/hot-functions/layers unchanged
- javatools erodes kotlin code over time. we're working on pure stubs

### .x typedef vtable options table
- VtableLayout enum: VIRTUAL, INTERFACE, INLINE, BOXED
- VtableOptions: layout + vtableSlots + mixinCompat bitmask + nullSafe
- VTABLE_OPTIONS[21] array indexed by XvmPrimitive.ordinal()
- mixinCompat bits: 0x01=field access, 0x02=method forwarding, 0x04=type unification, 0x08=type intersection
- Lookup: vtableOptions(XvmPrimitive), vtableSlots(String), mixinCompat(XvmPrimitive, int)

### ToSeriesMacro typedef parameterization rewrites
- JAVA_TYPEDEF_REWRITES: class fields, interface methods, generic method returns → Series<T>
- KOTLIN_TYPEDEF_REWRITES: typedef→typealias, class constructor List→Series
- TYPEDEF_EXCLUSIONS: skip already-rewritten typealias=Series lines
- Third pass in processPath after basic and redux passes

### TypedefResolutionPublisher — live pointcut → cascade → lattice spine
- ServiceContext.PointcutHook registration in static init
- TypedefCascadeTable(2048) as cascade backing store
- CascadeRollup.cascadeRollup() every ROLLUP_INTERVAL=2048 events
- subscribe() — idempotent, drains ring + rollup on first call
- Query API: snapshot(), tableRowCount(), table()
- fieldPublish() handles field opcodes alongside regular publish

### xvm lifecycle enum
- XvmLifecycle.java: INIT -> RUNNING -> DRAINING -> SHUTDOWN, no reverse transitions
- Invalid transition throws IllegalStateException
- 30 tests in XvmLifecycleTest.kt

### xvm drain -> pointcut drain -> file artifacts
- Snapshot-based file dumps for cascade.csv, joint_histogram.csv, and table_dump.csv on shutdown
- Bounded timing assertions verified within System.nanoTime() bounds [t0, t1] in JUnit test suite

### Kotlin benchmark blocks & CLI wrapped runner
- Wrapper command-line interface wrapped via Gradle for benchmarking Redux timeseries vs Synapse models
- Full JSON/CSV dump reification executed between high-precision nano timer blocks

## Not started

- VM shutdown reification
- Column-router: partitioned lazy column scan for SIMD histogram accumulation
- .x source reverse engineering for typedef port with parameterized unification
- Ported Cursor shapes to java, xtclang .x vtable and dsl builder creation
- Vtable production from cascade rules → .x typedef parameterized mixins

# next level-up

 * adaptive Event rate speculation burst -> grow /shrink estimate MutableSeriesRingsize  -> more like Units/TimeUnits simulation ticker
