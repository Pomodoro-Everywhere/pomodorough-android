package me.egigoka.pomodorough.domain

import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerIntent
import me.egigoka.pomodorough.data.TimerSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerConvergenceFixtureTest {
    @Test
    fun everyReducerMatchesPortableCanonicalCorpusForEveryArrivalOrder() {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("convergence-v1.json")) {
            "convergence-v1.json is missing"
        }.use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        assertEquals(
            "a293a679179f7f441a89b04f0260ee77fc0d810abc61e99501f9260a6ea9012e",
            digest,
        )

        val fixture = Json.decodeFromString<ConvergenceFixture>(bytes.decodeToString())
        assertEquals(2, fixture.version)
        val epoch = Instant.parse(fixture.epoch)
        fixture.cases.forEach { fixtureCase ->
            val commands = fixtureCase.commands.map { command ->
                TimerCommand(
                    id = command.id,
                    deviceSequence = command.sequence,
                    timerId = command.timerId,
                    taskId = command.taskId,
                    type = command.type,
                    phase = command.phase,
                    plannedDurationMs = command.durationMs,
                    occurredAt = epoch.plusMillis(command.atMs).toString(),
                    hlcWallMs = command.wallMs,
                    hlcCounter = command.counter,
                    observedElapsedMs = command.elapsedMs,
                )
            }
            listOf(commands, commands.reversed()).forEach { arrivalOrder ->
                val projection = TimerReducer.replay(null, emptyList(), arrivalOrder)
                assertEquals(
                    fixtureCase.name,
                    fixtureCase.expected,
                    normalize(projection.timer, projection.history, epoch),
                )
            }
        }
        fixture.projectionCases.forEach { fixtureCase ->
            val taskOperations = fixtureCase.taskOperations.map { operation ->
                TaskOperation(
                    id = operation.id,
                    taskId = operation.taskId,
                    type = operation.type,
                    title = operation.title,
                    occurredAt = epoch.plusMillis(operation.atMs).toString(),
                    hlcWallMs = operation.wallMs,
                    hlcCounter = operation.counter,
                )
            }
            permutations(taskOperations).forEach { arrivalOrder ->
                assertEquals(
                    fixtureCase.name,
                    fixtureCase.expected.tasks,
                    TaskReducer.replay(emptyList(), arrivalOrder),
                )
            }

            val durationOperations = fixtureCase.durationOperations.map { operation ->
                DurationOperation(
                    id = operation.id,
                    phase = operation.phase,
                    durationMs = operation.durationMs,
                    occurredAt = epoch.plusMillis(operation.atMs).toString(),
                    hlcWallMs = operation.wallMs,
                    hlcCounter = operation.counter,
                )
            }
            permutations(durationOperations).forEach { arrivalOrder ->
                assertEquals(
                    fixtureCase.name,
                    fixtureCase.expected.durationsMs,
                    SettingsReducer.replayDurations(TimerSettings(), arrivalOrder)
                        .effectiveDurationsMs(),
                )
            }

            val autoStartOperations = fixtureCase.autoStartOperations.map { operation ->
                AutoStartOperation(
                    id = operation.id,
                    deviceId = operation.deviceId,
                    enabled = operation.enabled,
                    occurredAt = epoch.plusMillis(operation.atMs).toString(),
                    hlcWallMs = operation.wallMs,
                    hlcCounter = operation.counter,
                )
            }
            permutations(autoStartOperations).forEach { arrivalOrder ->
                assertEquals(
                    fixtureCase.name,
                    fixtureCase.expected.autoStartBreaks,
                    SettingsReducer.replayAutoStart(false, arrivalOrder),
                )
            }
        }
        fixture.responseCases.forEach { fixtureCase ->
            val commands = fixtureCase.local.commands.map { command ->
                TimerCommand(
                    id = command.id,
                    deviceSequence = command.sequence,
                    timerId = command.timerId,
                    taskId = command.taskId,
                    type = command.type,
                    phase = command.phase,
                    plannedDurationMs = command.durationMs,
                    occurredAt = epoch.plusMillis(command.atMs).toString(),
                    hlcWallMs = command.wallMs,
                    hlcCounter = command.counter,
                    observedElapsedMs = command.elapsedMs,
                )
            }
            val taskOperations = fixtureCase.local.taskOperations.map { operation ->
                TaskOperation(
                    id = operation.id,
                    taskId = operation.taskId,
                    type = operation.type,
                    title = operation.title,
                    occurredAt = epoch.plusMillis(operation.atMs).toString(),
                    hlcWallMs = operation.wallMs,
                    hlcCounter = operation.counter,
                )
            }
            val durationOperations = fixtureCase.local.durationOperations.map { operation ->
                DurationOperation(
                    id = operation.id,
                    phase = operation.phase,
                    durationMs = operation.durationMs,
                    occurredAt = epoch.plusMillis(operation.atMs).toString(),
                    hlcWallMs = operation.wallMs,
                    hlcCounter = operation.counter,
                )
            }
            val autoStartOperations = fixtureCase.local.autoStartOperations.map { operation ->
                AutoStartOperation(
                    id = operation.id,
                    deviceId = operation.deviceId,
                    enabled = operation.enabled,
                    occurredAt = epoch.plusMillis(operation.atMs).toString(),
                    hlcWallMs = operation.wallMs,
                    hlcCounter = operation.counter,
                )
            }
            listOf(
                fixtureCase.sentIds.commands to fixtureCase.acknowledgements.commands,
                fixtureCase.sentIds.taskOperations to fixtureCase.acknowledgements.taskOperations,
                fixtureCase.sentIds.durationOperations to fixtureCase.acknowledgements.durationOperations,
                fixtureCase.sentIds.autoStartOperations to fixtureCase.acknowledgements.autoStartOperations,
            ).forEach { (sent, acknowledgements) ->
                assertEquals(sent.toSet(), acknowledgements.map(ConvergenceResponseAcknowledgement::id).toSet())
                assertEquals(
                    true,
                    acknowledgements.all { it.outcome in setOf("applied", "ignored", "rejected") },
                )
            }

            val retainedCommands = commands.filterNot {
                it.id in fixtureCase.acknowledgements.commands.map(ConvergenceResponseAcknowledgement::id)
            }
            val retainedTasks = taskOperations.filterNot {
                it.id in fixtureCase.acknowledgements.taskOperations.map(ConvergenceResponseAcknowledgement::id)
            }
            val retainedDurations = durationOperations.filterNot {
                it.id in fixtureCase.acknowledgements.durationOperations.map(ConvergenceResponseAcknowledgement::id)
            }
            val retainedAutoStart = autoStartOperations.filterNot {
                it.id in fixtureCase.acknowledgements.autoStartOperations.map(ConvergenceResponseAcknowledgement::id)
            }
            assertEquals(fixtureCase.expected.commandIds, retainedCommands.map(TimerCommand::id))
            assertEquals(fixtureCase.expected.taskOperationIds, retainedTasks.map(TaskOperation::id))
            assertEquals(
                fixtureCase.expected.durationOperationIds,
                retainedDurations.map(DurationOperation::id),
            )
            assertEquals(
                fixtureCase.expected.autoStartOperationIds,
                retainedAutoStart.map(AutoStartOperation::id),
            )

            val canonicalTimer = fixtureCase.canonical.timer?.let { timer ->
                CanonicalTimer(
                    id = timer.id,
                    taskId = timer.taskId,
                    phase = timer.phase,
                    status = timer.status,
                    plannedDurationMs = timer.durationMs,
                    elapsedAtAnchorMs = timer.elapsedMs,
                    anchorAt = epoch.plusMillis(timer.anchorMs).toString(),
                    lastIntent = TimerIntent(
                        type = "start",
                        commandId = timer.lastCommandId,
                        occurredAt = epoch.plusMillis(timer.anchorMs).toString(),
                    ),
                )
            }
            val timerProjection = TimerReducer.replay(canonicalTimer, emptyList(), retainedCommands)
            assertEquals(
                fixtureCase.name,
                ConvergenceExpected(fixtureCase.expected.timer, fixtureCase.expected.history),
                normalize(timerProjection.timer, timerProjection.history, epoch),
            )
            assertEquals(
                fixtureCase.name,
                fixtureCase.expected.tasks,
                TaskReducer.replay(fixtureCase.canonical.tasks, retainedTasks),
            )
            val canonicalSettings = TimerSettings().withDurations(fixtureCase.canonical.durationsMs)
            assertEquals(
                fixtureCase.name,
                fixtureCase.expected.durationsMs,
                SettingsReducer.replayDurations(canonicalSettings, retainedDurations)
                    .effectiveDurationsMs(),
            )
            assertEquals(
                fixtureCase.name,
                fixtureCase.expected.autoStartBreaks,
                SettingsReducer.replayAutoStart(
                    fixtureCase.canonical.autoStartBreaks,
                    retainedAutoStart,
                ),
            )
        }
    }

    private fun <T> permutations(values: List<T>): List<List<T>> = when (values.size) {
        0 -> listOf(emptyList())
        else -> values.indices.flatMap { index ->
            permutations(values.filterIndexed { candidate, _ -> candidate != index })
                .map { listOf(values[index]) + it }
        }
    }

    private fun normalize(
        timer: me.egigoka.pomodorough.data.CanonicalTimer?,
        history: List<me.egigoka.pomodorough.data.HistoryItem>,
        epoch: Instant,
    ) = ConvergenceExpected(
        timer = timer?.let {
            ConvergenceTimer(
                id = it.id,
                status = it.status,
                phase = it.phase,
                durationMs = it.plannedDurationMs,
                elapsedMs = it.elapsedAtAnchorMs,
                anchorMs = Instant.parse(it.anchorAt).toEpochMilli() - epoch.toEpochMilli(),
                lastCommandId = it.lastIntent?.commandId.orEmpty(),
                taskId = it.taskId,
            )
        },
        history = history.map {
            ConvergenceHistory(
                timerId = it.timerId,
                status = it.status,
                phase = it.phase,
                durationMs = it.plannedDurationMs,
                commandId = it.commandId,
                endedMs = Instant.parse(it.endedAt ?: it.completedAt).toEpochMilli() - epoch.toEpochMilli(),
                taskId = it.taskId,
            )
        },
    )
}

@Serializable
private data class ConvergenceFixture(
    val version: Int,
    val epoch: String,
    val cases: List<ConvergenceCase>,
    val projectionCases: List<ConvergenceProjectionCase>,
    val responseCases: List<ConvergenceResponseCase>,
)

@Serializable
private data class ConvergenceCase(
    val name: String,
    val nowMs: Long,
    val commands: List<ConvergenceCommand>,
    val expected: ConvergenceExpected,
)

@Serializable
private data class ConvergenceCommand(
    val id: String,
    val sequence: Long,
    val deviceId: String,
    val timerId: String,
    val taskId: String? = null,
    val type: String,
    val phase: String,
    val durationMs: Long,
    val atMs: Long,
    val wallMs: Long,
    val counter: Long,
    val elapsedMs: Long,
)

@Serializable
private data class ConvergenceExpected(
    val timer: ConvergenceTimer? = null,
    val history: List<ConvergenceHistory>,
)

@Serializable
private data class ConvergenceTimer(
    val id: String,
    val status: String,
    val phase: String,
    val durationMs: Long,
    val elapsedMs: Long,
    val anchorMs: Long,
    val lastCommandId: String,
    val taskId: String? = null,
)

@Serializable
private data class ConvergenceHistory(
    val timerId: String,
    val status: String,
    val phase: String,
    val durationMs: Long,
    val commandId: String? = null,
    val endedMs: Long,
    val taskId: String? = null,
)

@Serializable
private data class ConvergenceProjectionCase(
    val name: String,
    val taskOperations: List<ConvergenceTaskOperation>,
    val durationOperations: List<ConvergenceDurationOperation>,
    val autoStartOperations: List<ConvergenceAutoStartOperation>,
    val expected: ConvergenceProjectionExpected,
)

@Serializable
private data class ConvergenceTaskOperation(
    val id: String,
    val deviceId: String,
    val taskId: String,
    val type: String,
    val title: String? = null,
    val atMs: Long,
    val wallMs: Long,
    val counter: Long,
)

@Serializable
private data class ConvergenceDurationOperation(
    val id: String,
    val deviceId: String,
    val phase: String,
    val durationMs: Long,
    val atMs: Long,
    val wallMs: Long,
    val counter: Long,
)

@Serializable
private data class ConvergenceAutoStartOperation(
    val id: String,
    val deviceId: String,
    val enabled: Boolean,
    val atMs: Long,
    val wallMs: Long,
    val counter: Long,
)

@Serializable
private data class ConvergenceProjectionExpected(
    val tasks: List<FocusTask>,
    val durationsMs: DurationsMs,
    val autoStartBreaks: Boolean,
)

@Serializable
private data class ConvergenceResponseCase(
    val name: String,
    val local: ConvergenceResponseLocal,
    val sentIds: ConvergenceResponseIds,
    val acknowledgements: ConvergenceResponseAcknowledgements,
    val canonical: ConvergenceResponseCanonical,
    val expected: ConvergenceResponseExpected,
)

@Serializable
private data class ConvergenceResponseLocal(
    val commands: List<ConvergenceCommand>,
    val taskOperations: List<ConvergenceTaskOperation>,
    val durationOperations: List<ConvergenceDurationOperation>,
    val autoStartOperations: List<ConvergenceAutoStartOperation>,
)

@Serializable
private data class ConvergenceResponseIds(
    val commands: List<String>,
    val taskOperations: List<String>,
    val durationOperations: List<String>,
    val autoStartOperations: List<String>,
)

@Serializable
private data class ConvergenceResponseAcknowledgements(
    val commands: List<ConvergenceResponseAcknowledgement>,
    val taskOperations: List<ConvergenceResponseAcknowledgement>,
    val durationOperations: List<ConvergenceResponseAcknowledgement>,
    val autoStartOperations: List<ConvergenceResponseAcknowledgement>,
)

@Serializable
private data class ConvergenceResponseAcknowledgement(
    val id: String,
    val outcome: String,
    val reason: String,
)

@Serializable
private data class ConvergenceResponseCanonical(
    val timer: ConvergenceTimer? = null,
    val history: List<ConvergenceHistory>,
    val tasks: List<FocusTask>,
    val durationsMs: DurationsMs,
    val autoStartBreaks: Boolean,
)

@Serializable
private data class ConvergenceResponseExpected(
    val commandIds: List<String>,
    val taskOperationIds: List<String>,
    val durationOperationIds: List<String>,
    val autoStartOperationIds: List<String>,
    val timer: ConvergenceTimer? = null,
    val history: List<ConvergenceHistory>,
    val tasks: List<FocusTask>,
    val durationsMs: DurationsMs,
    val autoStartBreaks: Boolean,
)
