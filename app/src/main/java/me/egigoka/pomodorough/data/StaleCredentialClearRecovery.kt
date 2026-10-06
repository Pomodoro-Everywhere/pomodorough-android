package me.egigoka.pomodorough.data

/**
 * R43-A11 stale-credential clear outcome.
 * A vault-clear failure keeps recovery-required state and reports one
 * bounded category. A clean clear stays silent with no recovery flag.
 */
internal data class StaleCredentialClearOutcome(
    val recoveryRequired: Boolean,
    val category: CorruptStateCategory?,
)

internal object StaleCredentialClearRecovery {
    fun resolve(clearError: Throwable?): StaleCredentialClearOutcome {
        if (clearError == null) return StaleCredentialClearOutcome(false, null)
        CorruptStateReporter.report(CorruptStateCategory.CredentialClear)
        return StaleCredentialClearOutcome(true, CorruptStateCategory.CredentialClear)
    }
}
