package org.xvm.cursor

import org.junit.jupiter.api.Test
import org.xvm.asm.constants.TypedefResolutionPublisher.TypedefCallsite
import org.xvm.runtime.TypedefStaircaseTranscriptOracle
import org.xvm.runtime.XvmPrimitiveTranslationTable

class TypedefStaircaseTranscriptOracleKotlinRequireTest {
    @Test
    fun `redux transcript verification accepts non zero typedef params`() {
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
        require(state.allowB() == 1) { "branch B must accept parameterized typedef" }
        require(state.blockB() == 0) { "branch B must not block parameterized typedef" }
        require(oracle.branchAllowed(TypedefStaircaseTranscriptOracle.Branch.B)) { "branch B should remain allowed" }
        require(oracle.snapshot().single().reason().contains("parameterized")) { "reason must explain the verified path" }
        require(oracle.verifierReport().failures() == 0) { "pointcut verifier must pass" }
    }
}
