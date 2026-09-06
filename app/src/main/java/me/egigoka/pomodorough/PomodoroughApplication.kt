package me.egigoka.pomodorough

import android.app.Application
import computer.iroh.IrohAndroid
import io.sentry.android.core.SentryAndroid
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.data.api.PomodoroughApi
import me.egigoka.pomodorough.data.auth.AuthRepository
import me.egigoka.pomodorough.data.auth.TokenVault
import me.egigoka.pomodorough.data.iroh.IrohReplicationRepository
import me.egigoka.pomodorough.data.iroh.IrohReplicationService
import me.egigoka.pomodorough.data.iroh.IrohRoomStore
import me.egigoka.pomodorough.data.iroh.IrohSecretVault
import me.egigoka.pomodorough.data.local.LocalWorkspaceCoordinator
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import okhttp3.OkHttpClient

class PomodoroughApplication : Application() {
    lateinit var timerRepository: TimerRepository
        private set

    override fun onCreate() {
        super.onCreate()
        startCrashReporting()
        IrohAndroid.installAndroidContext(applicationContext)
        timerRepository = ApplicationDependencyGraph(this).createTimerRepository()
    }

    private fun startCrashReporting() {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) {
            return
        }
        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.release = "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.environment = "production"
            options.sessionReplay.sessionSampleRate = 0.1
            options.sessionReplay.onErrorSampleRate = 1.0
            // Masking flags are setter-only on SentryReplayOptions.
            options.sessionReplay.setMaskAllText(true)
            options.sessionReplay.setMaskAllImages(true)
        }
    }
}

private class ApplicationDependencyGraph(
    private val application: PomodoroughApplication,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val api = PomodoroughApi(BuildConfig.API_BASE_URL, client, json)
    private val database = PomodoroughDatabase.create(application)
    private val irohVault = IrohSecretVault(application)
    private val workspaceCoordinator = LocalWorkspaceCoordinator()
    private val sharedCore by lazy { SharedCore.fromAssets(application.assets) }
    private val sharedCoreDispatch: (String, String) -> JsonElement = { operation, input ->
        sharedCore.dispatch(operation, input)
    }
    private var repository: TimerRepository? = null

    fun createTimerRepository(): TimerRepository {
        val roomStore = createRoomStore()
        val replication = createIrohReplication(roomStore)
        return TimerRepository(
            context = application,
            dao = database.timerDao(),
            api = api,
            auth = createAuthRepository(),
            json = json,
            sharedCoreDispatch = sharedCoreDispatch,
            replication = replication,
            workspaceCoordinator = workspaceCoordinator,
        ).also { repository = it }
    }

    private fun createRoomStore() = IrohRoomStore(
        database.timerDao(),
        irohVault,
        sharedCoreDispatch,
        workspaceCoordinator,
    )

    private fun createAuthRepository() = AuthRepository(
        api = api,
        tokenVault = TokenVault(application, json),
        googleServerClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
    )

    private fun createIrohReplication(roomStore: IrohRoomStore): IrohReplicationRepository {
        val dao = database.timerDao()
        val service = IrohReplicationService(
            store = roomStore,
            vault = irohVault,
            onProjection = { repository?.scheduleWorkspaceReload() },
        )
        return IrohReplicationRepository(
            workspace = dao,
            rooms = dao,
            store = roomStore,
            service = service,
        )
    }
}
