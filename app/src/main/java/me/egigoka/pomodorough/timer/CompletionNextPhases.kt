package me.egigoka.pomodorough.timer

import me.egigoka.pomodorough.data.AppState

/**
 * The phases a finished timer leads to, as the timer screen shows them: Start
 * begins the selected phase and Skip moves to the core's skip destination. Both
 * come from published core state; a skip destination equal to the selected
 * phase offers no skip.
 */
internal data class CompletionNextPhases(val start: String, val skipTo: String?) {
    companion object {
        fun from(state: AppState) = CompletionNextPhases(
            start = state.settings.selectedPhase,
            skipTo = state.skipDestinationPhase?.takeIf { it != state.settings.selectedPhase },
        )
    }
}
