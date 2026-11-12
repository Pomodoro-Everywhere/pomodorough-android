package me.egigoka.pomodorough.data.time

import me.egigoka.pomodorough.data.SyncProtocolException

/**
 * Why the trusted clock refused a time sample. Callers classify by this
 * reason; [detail] is diagnostic text and is never shown to the user.
 */
internal enum class TrustedClockRejectionReason(val detail: String) {
    ServerClockOffsetOutOfRange("Server clock offset is outside supported range"),
    LocalReceiptTimingOutOfRange("Local receipt timing is outside supported range"),
    BootstrapClockSampleOutOfRange("Bootstrap clock sample is outside supported range"),
    ClockSampleUncertaintyTooHigh("Server clock sample uncertainty exceeds 30000ms"),
    InvalidServerTimestamp("Server returned an invalid server timestamp"),
    ServerTimestampOutOfRange("Server timestamp is outside supported range"),
    ServerHlcDisagreesWithTimestamp("Server HLC disagrees with server timestamp"),
}

/** Still a [SyncProtocolException], so every existing protocol-failure path keeps handling it. */
internal class TrustedClockRejectedException(
    val reason: TrustedClockRejectionReason,
) : SyncProtocolException(reason.detail)

// After a restart the device clock is too far ahead of the last time this
// device recorded. Only a new server time sample makes the clock usable again.
internal class FreshServerTimeRequiredException :
    IllegalArgumentException("Trusted time requires a fresh server sample")
