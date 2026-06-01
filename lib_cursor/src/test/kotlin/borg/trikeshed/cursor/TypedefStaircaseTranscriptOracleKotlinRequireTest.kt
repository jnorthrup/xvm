package borg.trikeshed.cursor

import org.junit.jupiter.api.Test
import org.xvm.asm.constants.TypedefResolutionPublisher.TypedefCallsite
import org.xvm.runtime.TypedefStaircaseTranscriptOracle
import org.xvm.runtime.XvmPrimitiveTranslationTable

class TypedefStaircaseTranscriptOracleKotlinRequireTest {
    @Test
    fun `redux transcript verification blocks non zero typedef params`() {
        val oracle = TypedefStaircaseTranscriptOracle()

        oracle.record(
            TypedefStaircaseTranscriptOracle.Branch.B,
            0x66,
            TypedefCallsite.PTC_Param,
            "dsl.step1().step2().step3()",
            3,
            XvmPrimitiveTranslationTable.XvmPrimitive.Dec64,
        )

        val state = oracle.state()
        require(state.blockB() == 1) { "branch B must block params!=0 staircase" }
        require(!oracle.branchAllowed(TypedefStaircaseTranscriptOracle.Branch.B)) { "branch B should be blocked" }
        require(oracle.snapshot().single().reason().contains("params!=0")) { "reason must explain the block" }
    }
}
