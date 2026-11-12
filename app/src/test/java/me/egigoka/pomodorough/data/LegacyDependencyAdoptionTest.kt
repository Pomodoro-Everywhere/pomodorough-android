package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.local.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyDependencyAdoptionTest {
    private val now = Instant.parse("2026-08-31T12:00:00Z")
    private val zone = ZoneId.of("UTC")

    @Test
    fun realPersistedRowsDecodeExactlyAndCoreOwnsCompleteReturn() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        val core = SharedCore.fromAssets(context.assets)
        try {
            seed(database.timerDao())
            val snapshot = database.timerDao().legacyDependencySnapshot()
            val decision = LegacyDependencyAdoption(core::dispatch).plan(snapshot, now, zone)
            val input = decision.input
            val rows = input.getValue("workspace").jsonObject.getValue("local").jsonObject.getValue("commands").jsonArray
            assertEquals(snapshot.commands.map { it.id }, rows.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            val decoded = rows.map { row ->
                val objectRow = row.jsonObject
                Json.decodeFromJsonElement<TimerCommand>(JsonObject(objectRow.filterKeys {
                    it !in setOf("generatedByFinishCommandId", "neverSent")
                })).copy(physicalOccurredAt = objectRow["physicalOccurredAt"]?.jsonPrimitive?.contentOrNull)
            }
            assertEquals(snapshot.commands.map { it.toModel() }, decoded)
            assertEquals("finish-1", rows[0].jsonObject.getValue("generatedByFinishCommandId").jsonPrimitive.content)
            assertEquals(snapshot.commands[0].physicalOccurredAt, rows[0].jsonObject["physicalOccurredAt"]?.jsonPrimitive?.contentOrNull)
            assertEquals(JsonNull, input.getValue("workspace").jsonObject.getValue("displayContext").jsonObject["projectionPending"])
            assertEquals(JsonNull, input["outgoing"])
            assertEquals(JsonArray(emptyList()), input["sourceAcknowledgements"])
            assertEquals(core.dispatch(LegacyDependencyAdoption.Operation, input.toString()), decision.result)
            assertEquals("blocked", decision.plan.outcome)
            val local = LegacyDependencyStartup(database.timerDao(), core::dispatch).adopt(now, zone)
            assertTrue(LegacyDependencyStartup.blocked(local))
            assertEquals(snapshot.commands, database.timerDao().pendingCommands())
        } finally {
            database.close()
        }
    }

    @Test
    fun blockedDecisionSurvivesReopenWithoutReinterpretingEvidence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "dependency-adoption.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, PomodoroughDatabase::class.java, name).build()
        val core = SharedCore.fromAssets(context.assets)
        seed(database.timerDao())
        val saved = LegacyDependencyStartup(database.timerDao(), core::dispatch).adopt(now, zone)
        database.close()
        val reopened = Room.databaseBuilder(context, PomodoroughDatabase::class.java, name).build()
        try {
            val result = LegacyDependencyStartup(reopened.timerDao()) { _, _ -> error("must not rerun") }.adopt(now, zone)
            assertEquals(saved, result)
            assertTrue(LegacyDependencyStartup.blocked(result))
        } finally {
            reopened.close(); context.deleteDatabase(name)
        }
    }

    @Test
    fun sharedRawSourceCorpusMatchesEveryCompleteNativeReturn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val core = SharedCore.fromAssets(context.assets)
        try {
            // Sibling checkout locally; CI checks the pinned Core out as pomodorough-core-source.
            val fixture = "fixtures/legacy-dependencies-v1.json"
            val file = sequenceOf("../../pomodorough-core", "../pomodorough-core", "../pomodorough-core-source")
                .map { File(it, fixture) }.firstOrNull { it.isFile }
                ?: error("Core fixture $fixture not found next to the android checkout")
            val receipts = Json.parseToJsonElement(file.readText()).jsonObject.getValue("receipts").jsonArray
            for (row in receipts) {
                val receipt = row.jsonObject
                val raw = receipt.getValue("inputRaw").jsonPrimitive.content
                assertEquals(receipt.getValue("input"), Json.parseToJsonElement(raw))
                assertEquals(receipt.getValue("name").toString(), receipt.getValue("completeNativeReturn"),
                    core.dispatch(LegacyDependencyAdoption.Operation, raw))
                assertEquals(receipt.getValue("completeNativeReturn"),
                    LegacyDependencyAdoption(core::dispatch).plan(Json.parseToJsonElement(raw).jsonObject).result)
            }
        } finally { /* SharedCore owns no closeable host resource. */ }
    }

    private suspend fun seed(dao: TimerDao) {
        dao.insertState(LocalStateEntity(deviceId = "device-1", settingsJson = "{}",
            legacyDependencyResultJson = LegacyDependencyAdoptionPending))
        // Missing source is an actual legacy row observation, not a fabricated source ACK.
        dao.insertCommand(PendingCommandEntity("break-1", 2, "timer-break", "start", "short_break",
            300_000, now.toString(), now.toEpochMilli(), 1, 0,
            generatedByFinishCommandId = "finish-1", physicalOccurredAt = now.toString(), neverSent = true))
    }

    @Test
    fun staleSnapshotAndWriteFailureNeverPartiallyInstall() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        try {
            seed(database.timerDao())
            val before = database.timerDao().legacyDependencySnapshot()
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER refuse_adoption BEFORE UPDATE ON local_state BEGIN SELECT RAISE(ABORT, 'refused'); END",
            )
            assertTrue(runCatching {
                database.timerDao().installLegacyDependencies(before,
                    before.local.copy(legacyDependencyResultJson = "{}"))
            }.isFailure)
            assertEquals(before, database.timerDao().legacyDependencySnapshot())
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER refuse_adoption")
            database.timerDao().updateState(before.local.copy(deviceSequence = 10))
            assertTrue(runCatching {
                database.timerDao().installLegacyDependencies(before, before.local)
            }.isFailure)
            assertEquals(before.commands, database.timerDao().pendingCommands())
            assertEquals(10L, database.timerDao().localState()?.deviceSequence)
        } finally { database.close() }
    }

    @Test
    fun corruptEvidenceFailsClosedAndCancellationDoesNotBecomeRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        try {
            seed(database.timerDao())
            val before = database.timerDao().legacyDependencySnapshot()
            val cancelled = runCatching {
                LegacyDependencyStartup(database.timerDao()) { _, _ -> throw kotlinx.coroutines.CancellationException() }
                    .adopt(now, zone)
            }.exceptionOrNull()
            assertTrue(cancelled is kotlinx.coroutines.CancellationException)
            assertEquals(before, database.timerDao().legacyDependencySnapshot())
            val blocked = LegacyDependencyStartup(database.timerDao()) { _, _ -> JsonObject(emptyMap()) }.adopt(now, zone)
            assertTrue(LegacyDependencyStartup.blocked(blocked))
            assertEquals(before.commands, database.timerDao().pendingCommands())
            assertTrue(LegacyDependencyStartup.blocked(blocked.copy(legacyDependencyResultJson = "bad json")))
        } finally { database.close() }
    }

    @Test
    fun provenDirectParentIsRetainedWithoutWireChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        val core = SharedCore.fromAssets(context.assets)
        try {
            seed(database.timerDao())
            val original = database.timerDao().pendingCommands().single()
            val start = original.copy(id = "start-1", deviceSequence = 1,
                generatedByFinishCommandId = null, type = "start", hlcCounter = 0)
            val pause = original.copy(type = "pause", generatedByFinishCommandId = start.id)
            database.timerDao().deleteAllCommands()
            database.timerDao().insertCommands(listOf(start, pause))
            val before = database.timerDao().legacyDependencySnapshot()
            val decision = LegacyDependencyAdoption(core::dispatch).plan(before, now, zone)
            assertEquals("planned", decision.plan.outcome)
            assertEquals(listOf(CoreTimerDependency(pause.id, start.id)), decision.plan.timerDependencies)
            val result = LegacyDependencyStartup(database.timerDao(), core::dispatch).adopt(now, zone)
            assertFalse(LegacyDependencyStartup.blocked(result))
            assertEquals(before.commands, database.timerDao().pendingCommands())
            assertEquals(decision.result, Json.parseToJsonElement(checkNotNull(result.legacyDependencyResultJson)))
        } finally { database.close() }
    }

    @Test
    fun repositoryStartupPreservesMissingSourceInsteadOfDeletingChild() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        try {
            seed(database.timerDao())
            val before = database.timerDao().pendingCommands()
            val repo = offlineRepository(context, database.timerDao(), null)
            try {
                repo.initialize()
                assertEquals(before, database.timerDao().pendingCommands())
                assertTrue(checkNotNull(database.timerDao().localState()).legacyDependencyResultJson != null)
                assertFalse(repo.addTask("must remain blocked"))
                repo.toggleTimer()
                repo.changeDuration(TimerPhase.Focus, 1)
                repo.selectTask(null)
                repo.refresh()
                assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
                assertEquals(before, database.timerDao().pendingCommands())
                assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
            } finally { repo.shutdownForTest() }
        } finally { database.close() }
    }

    @Test
    fun persistedOwnerMismatchIsDeniedWithoutInventedClaimEvidence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        val core = SharedCore.fromAssets(context.assets)
        try {
            seed(database.timerDao())
            val state = checkNotNull(database.timerDao().localState())
            database.timerDao().updateState(state.copy(ownerUserId = "owner-a", userJson = "{\"id\":\"owner-b\"}"))
            val before = database.timerDao().legacyDependencySnapshot()
            val input = LegacyDependencyObservations(before, Json { encodeDefaults = true; explicitNulls = true }).request(now, zone)
            assertEquals(JsonPrimitive("owner-a"), input.getValue("ownership").jsonObject["ownerId"])
            assertEquals(JsonPrimitive("owner-b"), input.getValue("ownership").jsonObject["expectedOwnerId"])
            assertTrue(runCatching { core.dispatch(LegacyDependencyAdoption.Operation, input.toString()) }.isFailure)
            val result = LegacyDependencyStartup(database.timerDao(), core::dispatch).adopt(now, zone)
            assertTrue(LegacyDependencyStartup.blocked(result))
            assertEquals(before.commands, database.timerDao().pendingCommands())
        } finally { database.close() }
    }

    @Test
    fun savedBootstrapQueuesStayRawAndMissingBodyRemainsMissing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        try {
            seed(database.timerDao())
            val saved = PendingBootstrapResolutionEntity(requestId = "saved-request", deviceId = "device-1",
                expectedRevision = 7, strategy = "merge", commandsJson = "[ ]", taskOperationsJson = "[]",
                durationOperationsJson = "[]", ownerUserId = "owner-a", userJson = "{\"id\":\"owner-a\"}")
            database.timerDao().upsertBootstrapResolution(saved)
            val snapshot = database.timerDao().legacyDependencySnapshot()
            val input = LegacyDependencyObservations(snapshot, Json { encodeDefaults = true; explicitNulls = true }).request(now, zone)
            val outgoing = input.getValue("outgoing").jsonObject
            assertFalse(outgoing.containsKey("body"))
            assertEquals(Json.parseToJsonElement(saved.commandsJson), outgoing.getValue("payload").jsonObject["commands"])
            assertEquals(saved, database.timerDao().pendingBootstrapResolution())
        } finally { database.close() }
    }

    @Test
    fun ambiguousParentsAndContradictoryEvidenceNeverRewriteRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        val core = SharedCore.fromAssets(context.assets)
        try {
            seed(database.timerDao())
            val child = database.timerDao().pendingCommands().single()
            database.timerDao().updateCommands(listOf(child.copy(generatedByFinishCommandId = child.id)))
            val before = database.timerDao().legacyDependencySnapshot()
            val result = LegacyDependencyStartup(database.timerDao(), core::dispatch).adopt(now, zone)
            assertTrue(LegacyDependencyStartup.blocked(result))
            assertEquals(before.commands, database.timerDao().pendingCommands())
        } finally { database.close() }
    }

    @Test
    fun parseablePartialOrContradictoryReadyMarkersNeverReopenAdmission() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        val core = SharedCore.fromAssets(context.assets)
        try {
            seed(database.timerDao())
            val snapshot = database.timerDao().legacyDependencySnapshot()
            val decision = LegacyDependencyAdoption(core::dispatch).plan(snapshot, now, zone)
            val partial = "{\"outcome\":\"planned\",\"recovery\":{\"blocksMutations\":false}}"
            assertTrue(LegacyDependencyStartup.blocked(snapshot.local.copy(legacyDependencyInputJson = "{}")))
            assertTrue(LegacyDependencyStartup.blocked(snapshot.local.copy(
                legacyDependencyInputJson = "{}", legacyDependencyResultJson = partial)))
            val contradictory = JsonObject(decision.result + ("recovery" to JsonObject(
                decision.plan.recovery + mapOf("status" to JsonPrimitive("ready"),
                    "blocksMutations" to JsonPrimitive(false), "blocksSync" to JsonPrimitive(true)))))
            assertTrue(LegacyDependencyStartup.blocked(snapshot.local.copy(
                legacyDependencyInputJson = decision.input.toString(), legacyDependencyResultJson = contradictory.toString())))
            assertTrue(LegacyDependencyStartup.blocked(snapshot.local.copy(
                legacyDependencyInputJson = "bad json", legacyDependencyResultJson = decision.result.toString())))
            val wrongOwner = JsonObject(decision.result + ("ownership" to buildJsonObject { put("ownerId", "other") }))
            assertTrue(LegacyDependencyStartup.blocked(snapshot.local.copy(
                legacyDependencyInputJson = decision.input.toString(), legacyDependencyResultJson = wrongOwner.toString())))
            assertEquals(snapshot, database.timerDao().legacyDependencySnapshot())
        } finally { database.close() }
    }

    @Test
    fun independentlyAuthoredAndroidWitnessMatchesCompleteInputAndNativeReturn() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "android-witness-reopen.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, PomodoroughDatabase::class.java, name).build()
        val core = SharedCore.fromAssets(context.assets)
        try {
            AndroidLegacyDependencyWitness.seed(database.timerDao())
            val fixture = Json.parseToJsonElement(checkNotNull(javaClass.classLoader
                ?.getResource("legacy-dependency-android-witness.json")).readText()).jsonObject
            val snapshot = database.timerDao().legacyDependencySnapshot()
            val decision = LegacyDependencyAdoption(core::dispatch).plan(snapshot, now, zone)
            assertEquals(fixture.getValue("input"), decision.input)
            assertEquals(fixture.getValue("nativeEnvelope").jsonObject.getValue("value"), decision.result)
            assertEquals("break-proof", decision.plan.timerDependencies?.first { it.operationId == "pause-proof" }?.dependsOnOperationId)
            for (field in listOf("generatedByFinishCommandId", "physicalOccurredAt", "neverSent")) {
                val workspace = decision.input.getValue("workspace").jsonObject
                val queues = workspace.getValue("local").jsonObject
                val rows = queues.getValue("commands").jsonArray
                val modified = JsonObject(rows[1].jsonObject - field)
                val changed = JsonArray(rows.mapIndexed { index, row -> if (index == 1) modified else row })
                val mutantInput = JsonObject(decision.input + ("workspace" to JsonObject(workspace +
                    ("local" to JsonObject(queues + ("commands" to changed))))))
                assertNotEquals(fixture.getValue("input"), mutantInput)
            }
            val saved = LegacyDependencyStartup(database.timerDao(), core::dispatch).adopt(now, zone)
            assertFalse(LegacyDependencyStartup.blocked(saved))
            val after = database.timerDao().legacyDependencySnapshot()
            // Core's direct-parent edge stays in the result; the retained sibling edge is unchanged.
            assertEquals(snapshot.commands, after.commands)
            assertEquals(listOf("finish-proof", "finish-proof"), after.commands.mapNotNull { it.generatedByFinishCommandId })
            assertEquals(decision.input.toString(), saved.legacyDependencyInputJson)
            assertEquals(decision.result.toString(), saved.legacyDependencyResultJson)
            database.close()
            database = Room.databaseBuilder(context, PomodoroughDatabase::class.java, name).build()
            assertEquals(after, database.timerDao().legacyDependencySnapshot())
            assertEquals(saved, LegacyDependencyStartup(database.timerDao()) { _, _ -> error("must not rerun") }.adopt(now, zone))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test
    fun plannedReopenRejectsOnlyContradictorySyncFlagWithoutCore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "planned-sync-flag.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, PomodoroughDatabase::class.java, name).build()
        try {
            AndroidLegacyDependencyWitness.seed(database.timerDao())
            val fixture = Json.parseToJsonElement(checkNotNull(javaClass.classLoader
                ?.getResource("legacy-dependency-android-witness.json")).readText()).jsonObject
            val input = fixture.getValue("input").toString()
            val planned = fixture.getValue("nativeEnvelope").jsonObject.getValue("value").jsonObject
            val local = checkNotNull(database.timerDao().localState()).copy(
                legacyDependencyInputJson = input, legacyDependencyResultJson = planned.toString())
            database.timerDao().updateState(local)
            val noCore: (String, String) -> JsonElement = { _, _ -> error("reopen must not call Core") }
            assertFalse(LegacyDependencyStartup.blocked(LegacyDependencyStartup(database.timerDao(), noCore).adopt(now, zone)))
            val changed = JsonObject(planned + ("recovery" to JsonObject(
                planned.getValue("recovery").jsonObject + ("blocksSync" to JsonPrimitive(true)))))
            database.timerDao().updateState(local.copy(legacyDependencyResultJson = changed.toString()))
            val expected = database.timerDao().legacyDependencySnapshot()
            database.close()
            database = Room.databaseBuilder(context, PomodoroughDatabase::class.java, name).build()
            val reopened = LegacyDependencyStartup(database.timerDao(), noCore).adopt(now, zone)
            assertTrue(LegacyDependencyStartup.blocked(reopened))
            assertEquals(expected, database.timerDao().legacyDependencySnapshot())
            assertEquals(JsonPrimitive("planned"), changed["outcome"])
            assertEquals(planned - "recovery", changed - "recovery")
            assertEquals(planned.getValue("recovery").jsonObject - "blocksSync",
                changed.getValue("recovery").jsonObject - "blocksSync")
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test
    fun plannedAdoptionKeepsSiblingGatingSoFinishAckReleasesStartAndPauseTogether() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        val core = SharedCore.fromAssets(context.assets)
        try {
            seedWitnessForRepository(database.timerDao())
            val before = database.timerDao().pendingCommands()
            val calls = CountingDispatch(core::dispatch)
            val repo = offlineRepository(context, database.timerDao(), calls::dispatch)
            try {
                repo.initialize()
                assertEquals(before, database.timerDao().pendingCommands())
                assertEquals(siblingGating, commandDependencies(repo))
                assertEquals(1, calls.adoptions)
                val saved = checkNotNull(database.timerDao().localState())
                assertFalse(LegacyDependencyStartup.blocked(saved))
                assertEquals("planned", Json.parseToJsonElement(checkNotNull(saved.legacyDependencyResultJson))
                    .jsonObject.getValue("outcome").jsonPrimitive.content)
                assertNull(repo.state.value.notice)
            } finally { repo.shutdownForTest() }
        } finally { database.close() }
    }

    @Test
    fun modernQueueWithFinishGeneratedRowsNeverAsksCoreForAdoption() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        val core = SharedCore.fromAssets(context.assets)
        try {
            seedWitnessForRepository(database.timerDao())
            val modern = checkNotNull(database.timerDao().localState()).copy(legacyDependencyResultJson = null)
            database.timerDao().updateState(modern)
            val before = database.timerDao().pendingCommands()
            val calls = CountingDispatch(core::dispatch)
            val repo = offlineRepository(context, database.timerDao(), calls::dispatch)
            try {
                repo.initialize()
                assertEquals(before, database.timerDao().pendingCommands())
                assertEquals(siblingGating, commandDependencies(repo))
                assertEquals(0, calls.adoptions)
                val after = checkNotNull(database.timerDao().localState())
                assertNull(after.legacyDependencyInputJson)
                assertNull(after.legacyDependencyResultJson)
                assertNull(repo.state.value.notice)
            } finally { repo.shutdownForTest() }
        } finally { database.close() }
    }

    @Test
    fun blockedAndInvalidAdoptionStillFailClosedAtRepositoryStartup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val core = SharedCore.fromAssets(context.assets)
        val recovery = context.getString(me.egigoka.pomodorough.R.string.legacy_dependencies_need_recovery)
        val blocked: suspend (TimerDao) -> Unit = { seed(it) }
        val invalid: suspend (TimerDao) -> Unit = { seedWitnessForRepository(it) }
        val corrupt = CountingDispatch { operation, body ->
            if (operation == LegacyDependencyAdoption.Operation) JsonObject(emptyMap()) else core.dispatch(operation, body)
        }
        for ((seedQueue, dispatch) in listOf(blocked to CountingDispatch(core::dispatch), invalid to corrupt)) {
            val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
            try {
                seedQueue(database.timerDao())
                val before = database.timerDao().pendingCommands()
                val repo = offlineRepository(context, database.timerDao(), dispatch::dispatch)
                try {
                    repo.initialize()
                    assertEquals(1, dispatch.adoptions)
                    assertEquals(recovery, repo.state.value.notice)
                    assertTrue(LegacyDependencyStartup.blocked(checkNotNull(database.timerDao().localState())))
                    assertFalse(repo.addTask("must remain blocked"))
                    assertEquals(before, database.timerDao().pendingCommands())
                } finally { repo.shutdownForTest() }
            } finally { database.close() }
        }
    }

    @Test
    fun version16MigrationMarksOnlyQueuesWithRetainedDependencyRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (parent in listOf("finish-1", null)) {
            val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
            try {
                database.timerDao().insertCommand(PendingCommandEntity("break-1", 2, "timer-break", "start",
                    "short_break", 300_000, now.toString(), now.toEpochMilli(), 1, 0, generatedByFinishCommandId = parent))
                val sql = database.openHelper.writableDatabase
                sql.execSQL("CREATE TABLE legacy_local_state (id INTEGER PRIMARY KEY NOT NULL, deviceId TEXT NOT NULL)")
                sql.execSQL("INSERT INTO legacy_local_state VALUES (0, 'retained-device')")
                sql.execSQL("DROP TABLE local_state")
                sql.execSQL("ALTER TABLE legacy_local_state RENAME TO local_state")

                PomodoroughDatabase.Migration16To17.migrate(sql)

                sql.query("SELECT legacyDependencyInputJson, legacyDependencyResultJson FROM local_state").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.isNull(0))
                    assertEquals(parent?.let { LegacyDependencyAdoptionPending }, cursor.getString(1))
                }
            } finally { database.close() }
        }
    }

    /** HEAD gating: every Finish-generated row waits on the Finish itself, not on a sibling. */
    private val siblingGating = mapOf("break-proof" to "finish-proof", "pause-proof" to "finish-proof")

    /** Repository startup decodes the stored profile; Core reads only its `id`, so the input is unchanged. */
    private suspend fun seedWitnessForRepository(dao: TimerDao) {
        AndroidLegacyDependencyWitness.seed(dao)
        val profile = User("account-proof", "proof@example.test", "Proof", "")
        dao.updateState(checkNotNull(dao.localState()).copy(userJson = Json.encodeToString(User.serializer(), profile)))
    }

    private class CountingDispatch(private val delegate: (String, String) -> JsonElement) {
        var adoptions = 0
        fun dispatch(operation: String, body: String): JsonElement {
            if (operation == LegacyDependencyAdoption.Operation) adoptions += 1
            return delegate(operation, body)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun commandDependencies(repo: TimerRepository): Map<String, String> =
        TimerRepository::class.java.getDeclaredField("commandDependencies")
            .apply { isAccessible = true }.get(repo) as Map<String, String>

    private fun offlineRepository(
        context: Context,
        dao: TimerDao,
        dispatch: ((String, String) -> JsonElement)?,
    ): TimerRepository {
        val api = java.lang.reflect.Proxy.newProxyInstance(
            me.egigoka.pomodorough.data.api.PomodoroughService::class.java.classLoader,
            arrayOf(me.egigoka.pomodorough.data.api.PomodoroughService::class.java),
        ) { _, _, _ -> error("offline startup must not use transport") } as me.egigoka.pomodorough.data.api.PomodoroughService
        val auth = java.lang.reflect.Proxy.newProxyInstance(
            me.egigoka.pomodorough.data.auth.AuthSession::class.java.classLoader,
            arrayOf(me.egigoka.pomodorough.data.auth.AuthSession::class.java),
        ) { _, method, _ -> when (method.name) {
            "credentialState" -> me.egigoka.pomodorough.data.auth.AuthCredentialState.Empty
            "hasTokens" -> false
            else -> error("offline startup must not use auth")
        } } as me.egigoka.pomodorough.data.auth.AuthSession
        return TimerRepository(context, dao, api, auth, Json, networkAvailable = { false },
            currentTimeMillis = { now.toEpochMilli() }, sharedCoreDispatch = dispatch)
    }
}
