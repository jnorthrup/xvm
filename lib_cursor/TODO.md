# lib_cursor TODO


rebooting the TODO:

 [x] vm timeseries capture
 [x] vm live taxonomy capture
 [x] JsElement lazy taxonomy navigation with joined facetted RowVec and ColumnMeta.child (Blackboard Cursor)
 [x] lazy firehose Redux and Synapse delegates
 [x] jitconnector facet
 [x] vm pointcut taxonomy delegate facets  == join( Series<RowVec> , /*Facet*/ Series<RowVec>)
 [ ] .x lang typedef table-based typesystem
 [ ] .x lang port of typealias+mixin vtable algebra

tdd red -> green as below

    Use facets to decide how a RowVec cell becomes navigable, reifiable, and/or timeseries-capturable.

    The useful algebra:

    text
    XSrcFile / Classfile / Method / Field / Constant / Edge / Event
        each is a virtual domain

    ColumnMetaRef
        left identity factory for cell shape

    Facet
        role tag on a ColumnMetaRef

    MetaSeries
        lazy codec/filter/projection from source domain to Cursor/RowVec

    Confix
        iteration/reification facade over JSON-ish material

    CRMS
        recursive materialized view:
        Cursor<RowVec>, where cells may lazy-reify into child Cursor branches


    Core move:

    text
    facet does not eagerly compute data
    facet tells Confix how to reify a branch when touched


    So a reflected XVM classfile hierarchy becomes:

    text
    Root CRMS Cursor
      └── XSrcFile facet
            └── ClassfileTaxonomy child Cursor
                  ├── constants child Cursor
                  ├── methods child Cursor
                  ├── fields child Cursor
                  ├── edges child Cursor
                  └── events child Cursor / firehose timeseries


    A RowVec cell carries:

    text
    value: Any?
    meta: () -> ColumnMeta


    The trick is to let value be lazy:

    text
    value = Lazy<Cursor>
    meta.facet = ClassfileTaxonomy / XSrcFile / ChildRows / Wireproto / ...


    or:

    text
    value = SymbolId
    meta.facet = SymbolName / TypeInfo / XvmCoordinate


    or:

    text
    value = MemSegment
    meta.facet = Wireproto


    Then Confix iteration can be:

    text
    for row in cursor:
      for cell in row:
        inspect cell.meta().facet
        if scalar facet:
          read/reify scalar
        if child facet:
          open child cursor lazily
        if wire facet:
          decode through MetaSeries codec
        if event facet:
          attach/read firehose timeseries


    Practical facet roles:

    text
    XSrcFile
      source file boundary
      e.g. JitConnector.java

    ClassfileTaxonomy
      virtual classfile node:
      class name, source path, package, bytecode location

    SymbolName
      interned runtime symbol id

    TypeInfo
      descriptor/signature/type identity

    ClassfileCoordinate
      cp index / method index / field index / bytecode offset

    XvmCoordinate
      XVM module/type/method/field coordinate

    EdgeTaxonomy
      relation rows:
      contains, invokes, loads, resolves, emits, observes

    Wireproto
      packed event payload / lazy decode source

    ChildRows
      branch marker: value is child Cursor

    ConfixMeta
      JSON/reifiable facade metadata

    VmStats
      aggregate/timeseries counters

    ReduxPhilum / SynapsePhilum
      journal/pulse stream surfaces

    ObserverDelegateRegistration
      observable hook / subscription edge


    For virtual reflected classfiles, each classfile node can be a CRMS domain row:

    text
    RowVec[
      symbolName          -> SymbolId             facet SymbolName
      typeInfo            -> SymbolId             facet TypeInfo
      classfileCoordinate -> SymbolId             facet ClassfileCoordinate
      xvmCoordinate       -> SymbolId             facet XvmCoordinate
      constants           -> Lazy<Cursor>         facet ChildRows + ClassfileTaxonomy
      methods             -> Lazy<Cursor>         facet ChildRows + ClassfileTaxonomy
      fields              -> Lazy<Cursor>         facet ChildRows + ClassfileTaxonomy
      edges               -> Lazy<Cursor>         facet ChildRows + EdgeTaxonomy
      events              -> Lazy<Cursor>         facet ChildRows + ReduxPhilum/SynapsePhilum
      wireproto           -> MemSegment           facet Wireproto
    ]


    But since current ColumnMetaRef has only one facet, the near-term implementation can model combined roles by convention:

    text
    children column has facet ChildRows
    child cursor rows themselves carry ClassfileTaxonomy / EdgeTaxonomy / XSrcFile


    So instead of multi-facet cells:

    text
    constants: ChildRows + ClassfileTaxonomy


    do:

    text
    constants: facet ChildRows
      child rows: facet ClassfileTaxonomy


    That keeps the algebra clean.

    Lazy-but-reifiable design:

    kotlin
    data class LazyFacetCell(
        val ref: ColumnMetaRef,
        val value: Any?,
        val reify: () -> Any? = { value },
    )


    But to stay compatible with current RowVec shape:

    kotlin
    Join<Any?, () -> ColumnMeta>


    use value as:

    kotlin
    Lazy<Cursor>
    Lazy<MemSegment>
    Lazy<RowVec>
    SymbolId
    Int
    Long


    Then Confix reification knows:

    kotlin
    when (metaFacet) {
        ChildRows -> (value as Lazy<Cursor>).value
        Wireproto -> codec.decode((value as MemSegment).bytes)
        SymbolName, TypeInfo, XvmCoordinate -> StringPool.resolve(value as Int)
        else -> value
    }


    Firehose event timeseries fits as another lazy child branch:

    text
    Classfile node
      └── events child Cursor
            rows are event samples:
              nanoTime
              opcode
              xvmCoordinate
              symbolId
              wireproto
              reducerState / synapse pulse id


    The event rows should be packed/appendable:

    text
    RingSeries hot path
      -> MemSegment wireproto
      -> Redux/Journal lossless branch
      -> CRMS lazy event cursor reifies when inspected


    So the live path is not:

    text
    event -> JSON object -> RowVec


    It is:

    text
    event -> packed/wireproto -> Ring/Journal


    Then inspection path is:

    text
    Confix iterates virtual JSON facade
      -> sees Wireproto facet
      -> decodes lazily
      -> emits RowVec


    This gives both:

    text
    firehose throughput


    and:

    text
    reifiable JSON-like introspection


    Algebraically:

    text
    MetaSeries<Input, Output>
      filter: Input -> Boolean
      codec: Input -> Output
      refs: Series<ColumnMetaRef>
      cursor: InputDomain -> Cursor


    For classfiles:

    text
    MetaSeries<ClassfileBytes, RowVec>
      filter = package startsWith org.xtc / org.xvm / org.xtclang
      codec = Classfile API parser
      refs = classfile taxonomy refs


    For events:

    text
    MetaSeries<MemSegment, RowVec>
      filter = event kind / coordinate / opcode
      codec = wireproto decoder
      refs = event row refs


    For Confix JSON facade:

    text
    MetaSeries<JsonCell, RowVec>
      filter = path/glob/query
      codec = JSON cell -> RowVec
      refs = ConfixMeta refs


    The leveraging pattern:

    1. Build classfile hierarchy as lazy CRMS domains
       - root
       - source file
       - classfile
       - method/field/constant
       - edge
       - event stream

    2. Attach ColumnMetaRef to every cell
       - names/types interned
       - facet role explicit

    3. Store child branches as lazy cursor values
       - do not eagerly materialize classfile tree
       - Confix opens branch on demand

    4. Keep firehose events packed
       - Wireproto facet is the durable representation
       - RowVec is a view, not the hot-path object

    5. Let Confix iterate as JSON facade
       - path/glob filters are MetaSeries domains
       - reification happens only at selected branches

    6. Use facets as dispatch keys
       - not as objects with behavior
       - they select codec/reifier/navigation behavior

    A useful dispatch table:

    text
    Facet                  Reifier

    SymbolName             StringPool.resolve(Int)
    TypeInfo               StringPool.resolve(Int)
    ClassfileCoordinate    StringPool.resolve(Int)
    XvmCoordinate          StringPool.resolve(Int)
    ChildRows              Lazy<Cursor>.value
    Wireproto              WireCodec.decode(MemSegment)
    ClassfileTaxonomy      ClassfileMetaSeries.reify(row)
    EdgeTaxonomy           EdgeMetaSeries.reify(row)
    XSrcFile               XSrcFileMetaSeries.reify(row)
    ConfixMeta             JsonFacade.reify(row)
    ReduxPhilum            JournalCursor.reify(row)
    SynapsePhilum          RingPulseCursor.reify(row)
    VmStats                CounterSnapshot.reify(row)


    So the short answer:

    Use facets as lazy reification dispatch labels on ColumnMetaRef.

    The virtual XVM reflected classfile hierarchy is a CRMS cursor tree. Each RowVec cell has a facet-bearing meta identity. Child branches are lazy Cursor cells. Firehose timeseries remains packed as wireproto/journal/ring data, and Confix provides a JSON-like iterable facade that only reifies a branch when a facet demands it.

    That preserves:

    text
    fast capture
    lazy inspection
    symbol identity
    classfile coordinates
    XVM runtime coordinates
    cursor-tree navigation
    JSON facade reifiability
