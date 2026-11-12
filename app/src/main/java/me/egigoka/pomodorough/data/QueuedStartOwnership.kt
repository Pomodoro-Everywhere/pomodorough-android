package me.egigoka.pomodorough.data

/**
 * R54-A19: the delivery-safe projection omits a Start that is still
 * retained (unsent, or sent and unacknowledged), so the projected timer
 * cannot decide ownership yet. Keep the ownership that Start recorded
 * while it stays queued. Once it is acknowledged or dropped, the canonical
 * timer decides again. Sync application and local initialization share
 * this rule so a response and a restart keep the same owner.
 */
internal fun ownStartQueued(ownedTimerId: String?, retained: List<TimerCommand>): Boolean =
    ownedTimerId != null && retained.any {
        it.type == CommandType.Start && it.timerId == ownedTimerId
    }
