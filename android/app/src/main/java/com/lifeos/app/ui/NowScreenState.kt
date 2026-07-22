package com.lifeos.app.ui

import com.lifeos.app.data.Block
import com.lifeos.app.data.DayDeviation
import com.lifeos.app.data.NowResponse
import com.lifeos.app.data.WorkSessionInfo

sealed class NowUiState {
    data class Ready(val block: Block, val minutesLate: Int) : NowUiState()
    data class Active(val block: Block, val session: WorkSessionInfo) : NowUiState()
    data class Paused(val block: Block, val session: WorkSessionInfo) : NowUiState()
    data class Completed(val block: Block) : NowUiState()
    data class Idle(val nextBlock: Block?) : NowUiState()
}

fun NowResponse.toUiState(): NowUiState {
    val block = current_block
    val session = active_work_session

    if (block != null && session != null && session.schedule_block_id == block.id) {
        return if (session.status == "paused") NowUiState.Paused(block, session) else NowUiState.Active(block, session)
    }

    if (block == null) {
        return NowUiState.Idle(next_block)
    }

    return when (block.status) {
        "ready" -> NowUiState.Ready(block, day_deviation.minutesLateForCurrentBlock())
        "completed" -> NowUiState.Completed(block)
        else -> NowUiState.Idle(next_block)
    }
}

private fun DayDeviation.minutesLateForCurrentBlock(): Int = minutes_late_starting
