package com.ifpr.androidapptemplatear.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

class SharedModelViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    val selectedModel = savedState.getLiveData("selectedModel", "coracao.glb")
    val collected = savedState.getLiveData("collected", 0)
    private val mutableCleaning = MutableLiveData(
        CleaningState(
            savedState["progress"] ?: 0f,
            savedState["damage"] ?: 0f,
            CleaningStatus.valueOf(savedState["status"] ?: CleaningStatus.CLEANING.name)
        )
    )
    val cleaning: LiveData<CleaningState> = mutableCleaning
    val cleaningModel: String get() = savedState["cleaningModel"] ?: "coracao.glb"
    val sessionId: String? get() = savedState["sessionId"]
    val isGameOver: Boolean get() = cleaning.value?.status == CleaningStatus.BROKEN

    fun isCollected(id: String): Boolean =
        (savedState.get<ArrayList<String>>("collectedIds") ?: arrayListOf()).contains(id)

    fun selectModel(modelPath: String) {
        savedState["selectedModel"] = modelPath
    }

    fun beginCleaning(model: String, id: String): Boolean {
        if (isGameOver) return false
        // A rendered object can only be collected once, including after view recreation.
        if ((savedState.get<ArrayList<String>>("collectedIds") ?: arrayListOf()).contains(id)) {
            return false
        }
        if (sessionId != id) {
            savedState["cleaningModel"] = model
            savedState["sessionId"] = id
            updateCleaning(CleaningState())
        }
        return true
    }

    fun rub(distanceDp: Float, elapsedMs: Long) {
        if (sessionId == null) return
        val before = cleaning.value ?: return
        val after = CleaningRules.rub(before, distanceDp, elapsedMs)
        if (after == before) return
        if (before.status == CleaningStatus.CLEANING && after.status == CleaningStatus.COLLECTED) {
            val ids = ArrayList(savedState.get<ArrayList<String>>("collectedIds") ?: arrayListOf())
            sessionId?.let(ids::add)
            savedState["collectedIds"] = ids
            savedState["collected"] = (collected.value ?: 0) + 1
        }
        updateCleaning(after)
    }

    fun restart() {
        savedState["collected"] = 0
        savedState["collectedIds"] = arrayListOf<String>()
        savedState["sessionId"] = null
        updateCleaning(CleaningState())
    }

    private fun updateCleaning(state: CleaningState) {
        savedState["progress"] = state.progress
        savedState["damage"] = state.damage
        savedState["status"] = state.status.name
        mutableCleaning.value = state
    }
}

enum class CleaningStatus { CLEANING, COLLECTED, BROKEN }

data class CleaningState(
    val progress: Float = 0f,
    val damage: Float = 0f,
    val status: CleaningStatus = CleaningStatus.CLEANING
)

/** Distance in density-independent pixels; speed is independent of screen density. */
object CleaningRules {
    const val SAFE_SPEED_DP_PER_SECOND = 700f

    fun rub(state: CleaningState, distanceDp: Float, elapsedMs: Long): CleaningState {
        if (state.status != CleaningStatus.CLEANING ||
            !distanceDp.isFinite() || distanceDp <= 0f || elapsedMs <= 0L
        ) return state

        val speed = distanceDp * 1000f / elapsedMs
        if (speed > SAFE_SPEED_DP_PER_SECOND) {
            val multiplier = (speed / SAFE_SPEED_DP_PER_SECOND).coerceAtMost(4f)
            val damage = (state.damage + distanceDp / 8f * multiplier).coerceAtMost(100f)
            return state.copy(
                damage = damage,
                status = if (damage >= 100f) CleaningStatus.BROKEN else state.status
            )
        }

        val progress = (state.progress + distanceDp / 18f).coerceAtMost(100f)
        return state.copy(
            progress = progress,
            status = if (progress >= 100f) CleaningStatus.COLLECTED else state.status
        )
    }
}
