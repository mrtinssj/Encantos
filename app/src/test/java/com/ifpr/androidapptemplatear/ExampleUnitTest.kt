package com.ifpr.androidapptemplatear

import org.junit.Assert.*
import org.junit.Test
import com.ifpr.androidapptemplatear.ui.CleaningRules
import com.ifpr.androidapptemplatear.ui.CleaningState
import com.ifpr.androidapptemplatear.ui.CleaningStatus

class ExampleUnitTest {
    @Test fun slowRubbingCleansWithoutDamage() {
        var state = CleaningState()
        repeat(100) { state = CleaningRules.rub(state, 18f, 100) }
        assertEquals(CleaningStatus.COLLECTED, state.status)
        assertEquals(100f, state.progress, 0.001f)
        assertEquals(0f, state.damage, 0f)
    }

    @Test fun aggressiveRubbingBreaksBeforeCollecting() {
        var state = CleaningState()
        repeat(10) { state = CleaningRules.rub(state, 100f, 40) }
        assertEquals(CleaningStatus.BROKEN, state.status)
        assertEquals(0f, state.progress, 0f)
        assertEquals(100f, state.damage, 0f)
    }

    @Test fun terminalStatesStopAcceptingActions() {
        for (status in listOf(CleaningStatus.BROKEN, CleaningStatus.COLLECTED)) {
            val state = CleaningState(75f, 100f, status)
            assertEquals(state, CleaningRules.rub(state, 100f, 1))
            assertEquals(state, CleaningRules.rub(state, 100f, 1000))
        }
    }

    @Test fun damageDoesNotDependOnEventFrequency() {
        val single = CleaningRules.rub(CleaningState(), 100f, 100)
        var batched = CleaningState()
        repeat(10) { batched = CleaningRules.rub(batched, 10f, 10) }
        assertEquals(single.damage, batched.damage, 0.001f)
    }

    @Test fun invalidSamplesDoNotChangeState() {
        val state = CleaningState()
        assertEquals(state, CleaningRules.rub(state, Float.NaN, 16))
        assertEquals(state, CleaningRules.rub(state, 20f, 0))
        assertEquals(state, CleaningRules.rub(state, -1f, 16))
    }
}
