package me.egigoka.pomodorough

import android.app.Application
import computer.iroh.IrohAndroid
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.data.api.PomodoroughApi
import me.egigoka.pomodorough.data.auth.AuthRepository
import me.egigoka.pomodorough.data.auth.TokenVault
import me.egigoka.pomodorough.data.iroh.IrohReplicationRepository
import me.egigoka.pomodorough.data.iroh.IrohReplicationService
import me.egigoka.pomodorough.data.iroh.IrohRoomStore
import me.egigoka.pomodorough.data.iroh.IrohSecretVault
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.LocalWorkspaceCoordinator
import okhttp3.OkHttpClient

class PomodoroughApplication : Application() {
    lateinit var timerRepository: TimerRepository
        private set

    override fun onCreate() {
        super.onCreate()
        IrohAndroid.installAndroidContext(applicationContext)
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
        val api = PomodoroughApi(BuildConfig.API_BASE_URL, client, json)
        val database = PomodoroughDatabase.create(this)
        val irohVault = IrohSecretVault(this)
        val workspaceCoordinator = LocalWorkspaceCoordinator()
        val irohRoomStore = IrohRoomStore(database.timerDao(), irohVault, workspaceCoordinator)
        val auth = AuthRepository(
            api = api,
            tokenVault = TokenVault(this, json),
            googleServerClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
        )
        var repository: TimerRepository? = null
        val irohService = IrohReplicationService(
            store = irohRoomStore,
            vault = irohVault,
            onProjection = { repository?.scheduleWorkspaceReload() },
        )
        val irohReplication = IrohReplicationRepository(
            dao = database.timerDao(),
            store = irohRoomStore,
            service = irohService,
        )
        repository = TimerRepository(
            context = this,
            dao = database.timerDao(),
            api = api,
            auth = auth,
            json = json,
            replication = irohReplication,
            workspaceCoordinator = workspaceCoordinator,
        )
        timerRepository = checkNotNull(repository)
    }
}
