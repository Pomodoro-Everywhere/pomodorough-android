package me.egigoka.pomodorough.data.iroh

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import computer.iroh.Endpoint
import computer.iroh.EndpointBuilder
import computer.iroh.EndpointTicket
import computer.iroh.RecvStream
import computer.iroh.SendStream
import java.nio.ByteBuffer
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IrohReplicationServiceTest {
    private lateinit var database: PomodoroughDatabase
    private lateinit var service: IrohReplicationService
    private lateinit var core: SharedCore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        core = SharedCore.fromAssets(context.assets)
        val vault = IrohSecretVault(context)
        service = IrohReplicationService(
            store = IrohRoomStore(
                database.timerDao(),
                vault,
                { operation, input -> core.dispatch(operation, input) },
            ),
            vault = vault,
            onProjection = {},
        )
    }

    @After
    fun tearDown() = runBlocking {
        service.stop()
        database.close()
    }

    @Test
    fun dialingServiceServesReverseInventoryAfterHello() = runBlocking {
        val dao = database.timerDao()
        dao.insertState(LocalStateEntity(
            deviceId = "device-client01",
            settingsJson = IrohJson.strict.encodeToString(TimerSettings()),
        ))
        val store = IrohRoomStore(
            dao,
            IrohSecretVault(ApplicationProvider.getApplicationContext()),
            { operation, input -> core.dispatch(operation, input) },
        )
        val room = store.createRoom(null).first
        val secret = requireNotNull(store.activeRoomSecret())
        val peer = bindLocalEndpoint()
        try {
            val peerTicket = endpointTicket(peer)
            service = IrohReplicationService(
                store = store,
                vault = IrohSecretVault(ApplicationProvider.getApplicationContext()),
                onProjection = {},
            )
            service.start(
                IrohServiceContext(room.roomId, secret.copyOf(), "device-client01", null),
                startPeriodicSync = false,
            )
            val invite = IrohRoomInvite(room.roomId, null, peerTicket, secret.copyOf())
            val peerTask = async {
                val incoming = withTimeout(10_000L) { requireNotNull(peer.acceptNext()) }
                incoming.use {
                    val accepting = incoming.accept()
                    val connection = try {
                        accepting.connect()
                    } finally {
                        accepting.close()
                    }
                    try {
                        val helloStream = connection.acceptBi()
                        val hello = readMessage(helloStream.recv(), secret) as IrohRpcMessage.Hello
                        writeMessage(
                            IrohRpcMessage.Hello(hello.value.copy(
                                deviceId = "device-peer0001",
                                endpointTicket = peerTicket,
                                platform = "linux",
                            )),
                            helloStream.send(),
                            secret,
                        )
                        helloStream.close()

                        val pullStream = connection.acceptBi()
                        val pull = readMessage(pullStream.recv(), secret) as IrohRpcMessage.Inventory

                        val reverseId = IrohProtocolV1.requestId()
                        val reverseStream = connection.openBi()
                        writeMessage(
                            IrohRpcMessage.Inventory(IrohInventoryRequest(
                                IrohProtocolV1.Version,
                                room.roomId,
                                reverseId,
                                "inventory",
                                null,
                                IrohProtocolV1.MaxInventoryEntries,
                            )),
                            reverseStream.send(),
                            secret,
                        )
                        val reverse = readMessage(reverseStream.recv(), secret) as IrohRpcMessage.InventoryResult
                        assertTrue(reverse.value.entries.any {
                            it.domain == IrohDomain.genesis && it.id == "genesis"
                        })
                        reverseStream.close()

                        writeMessage(
                            IrohRpcMessage.InventoryResult(IrohInventoryResult(
                                IrohProtocolV1.Version,
                                room.roomId,
                                pull.requestId,
                                "inventoryResult",
                                emptyList(),
                                null,
                            )),
                            pullStream.send(),
                            secret,
                        )
                        pullStream.close()
                        withTimeout(10_000L) { connection.closed() }
                    } finally {
                        connection.close()
                    }
                }
            }

            service.join(invite)
            peerTask.await()
            Unit
        } finally {
            secret.fill(0)
            peer.shutdown()
            peer.close()
        }
    }

    private suspend fun bindLocalEndpoint(): Endpoint {
        val builder = EndpointBuilder()
        return try {
            builder.applyMinimal()
            builder.applyN0DisableRelay()
            builder.alpns(listOf(IrohProtocolV1.Alpn))
            builder.bind()
        } finally {
            builder.close()
        }
    }

    private fun endpointTicket(endpoint: Endpoint): String = endpoint.addr().use { address ->
        EndpointTicket.fromAddr(address).use(Any::toString)
    }

    private suspend fun readMessage(stream: RecvStream, secret: ByteArray): IrohRpcMessage {
        stream.use {
            val header = stream.readExact(4u)
            val bodyLength = ByteBuffer.wrap(header).int
            val mac = stream.readExact(32u)
            val body = stream.readExact(bodyLength.toUInt())
            check(stream.read(1u).isEmpty())
            return IrohMessageCodec.decode(IrohFrameCodec.decode(header + mac + body, secret))
        }
    }

    private suspend fun writeMessage(
        message: IrohRpcMessage,
        stream: SendStream,
        secret: ByteArray,
    ) = stream.use {
        stream.writeAll(IrohFrameCodec.encode(IrohMessageCodec.encode(message), secret))
        stream.finish()
    }
}
