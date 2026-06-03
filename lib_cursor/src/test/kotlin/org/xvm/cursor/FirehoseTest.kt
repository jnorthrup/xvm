package org.xvm.cursor

import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.MutationAction
import borg.trikeshed.lib.PointcutMutableSeries
import kotlin.test.Test

class FirehoseTest {
    @Test
    fun `firehose test - watch all mutablestate as ReduxMutableSeries flush while launching xvm unit tests`() {
        // 1. Establish the "firehose" event log (Redux style interceptor)
        val firehoseLog = mutableListOf<MutationAction<ClassFileTaxonomy.CoordinateRow>>()
        
        // 2. Establish the underlying storage and wrap it in the Pointcut harness
        val backingStore = ChunkedMutableSeries<ClassFileTaxonomy.CoordinateRow>()
        val pointcut = PointcutMutableSeries(backingStore) { action ->
            firehoseLog.add(action)
        }

        // 3. Inject the Pointcut harness into the Taxonomy registry
        val registry = ClassFileTaxonomy()
        registry.setBackingSeries(pointcut)

        // 4. Simulate an XVM initialization step (loading/registering a class)
        val dummyRow = ClassFileTaxonomy.CoordinateRow(
            symbolName = "Test.main",
            ownerType = "Test",
            methodOrField = "main",
            classfileCoord = "Test#main",
            cpIndex = 1,
            descriptor = "([Ljava/lang/String;)V",
            xvmTypeInfo = "org.xtc.evidence",
            pointcutKind = 0x10, // dummy opcode
            poolId = 42
        )
        registry.register(dummyRow)

        // 5. Verify the Firehose caught the mutation!
        kotlin.test.assertEquals(1, backingStore.size, "Backing store must have 1 element")
        kotlin.test.assertEquals(1, firehoseLog.size, "Firehose log must have caught 1 mutation action")
        
        val action = firehoseLog[0]
        kotlin.test.assertTrue(action is MutationAction.Add, "Action must be an Add")
        kotlin.test.assertEquals("Test.main", (action as MutationAction.Add).item.symbolName)
    }
}
