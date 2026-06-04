package org.xvm.cursor

class TypeResolutionProductionSystem {
    private var internalCorrelationCount = 0

    fun correlationCount(): Int {
        return internalCorrelationCount
    }

    fun correlateTaxonomyRow(tax: ClassFileTaxonomy, rowIdx: Int) {
        val row = tax.rowAt(rowIdx)
        val facts = TypedefResolutionSeries.factsByPool(row.poolId)
        internalCorrelationCount += facts.size
    }

    fun state(): State {
        return State(TypedefResolutionSeries.size())
    }

    class State(
        @JvmField val totalFacts: Int
    )
}
