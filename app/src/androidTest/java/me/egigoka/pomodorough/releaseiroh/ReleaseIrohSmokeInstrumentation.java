package me.egigoka.pomodorough.releaseiroh;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;
import computer.iroh.Accepting;
import computer.iroh.Connection;
import computer.iroh.Endpoint;
import computer.iroh.EndpointAddr;
import computer.iroh.EndpointBuilder;
import computer.iroh.Incoming;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;

public final class ReleaseIrohSmokeInstrumentation extends Instrumentation {
    private static final byte[] ALPN =
            "me.egigoka.pomodorough/sync/1".getBytes(StandardCharsets.UTF_8);
    private static final long TIMEOUT_SECONDS = 60L;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Thread worker = new Thread(this::runSmoke, "release-iroh-smoke");
        worker.start();
    }

    private void runSmoke() {
        Bundle running = statusBundle();
        running.putString("stream", "releaseIrohHandshake started");
        sendStatus(1, running);

        Throwable failure = null;
        try {
            completeEndpointHandshake();
        } catch (Throwable error) {
            failure = error;
        }

        if (failure == null) {
            Bundle passed = statusBundle();
            passed.putString("stream", ".");
            sendStatus(0, passed);
            Bundle result = new Bundle();
            result.putString("stream", "\nOK (1 test)\n");
            finish(Activity.RESULT_OK, result);
        } else {
            Bundle failed = statusBundle();
            failed.putString("stack", Log.getStackTraceString(failure));
            failed.putString("stream", "releaseIrohHandshake failed: " + failure.getMessage());
            sendStatus(-2, failed);
            Bundle result = new Bundle();
            result.putString("stream", Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static void completeEndpointHandshake() throws Throwable {
        Endpoint server = bindEndpoint();
        Endpoint client = bindEndpoint();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            String serverId;
            String clientId;
            try (var id = server.id()) {
                serverId = id.toString();
            }
            try (var id = client.id()) {
                clientId = id.toString();
            }

            Future<Connection> acceptedFuture = executor.submit(() -> {
                try {
                    Incoming incoming = await(Incoming.class, server::acceptNext);
                    try (incoming) {
                        Accepting accepting = await(Accepting.class, incoming::accept);
                        try (accepting) {
                            require(
                                    Arrays.equals(await(byte[].class, accepting::alpn), ALPN),
                                    "incoming ALPN mismatch");
                            return await(Connection.class, accepting::connect);
                        }
                    }
                } catch (Throwable error) {
                    throw new ProbeFailure(error);
                }
            });

            Connection outgoing;
            try (EndpointAddr address = server.addr()) {
                outgoing = await(Connection.class, continuation -> client.connect(address, ALPN, continuation));
            }
            Connection incoming = getFuture(acceptedFuture);
            try (outgoing; incoming) {
                require(Arrays.equals(outgoing.alpn(), ALPN), "outgoing ALPN mismatch");
                require(Arrays.equals(incoming.alpn(), ALPN), "accepted ALPN mismatch");
                try (var remote = outgoing.remoteId()) {
                    require(serverId.equals(remote.toString()), "outgoing peer identity mismatch");
                }
                try (var remote = incoming.remoteId()) {
                    require(clientId.equals(remote.toString()), "accepted peer identity mismatch");
                }
            }
        } finally {
            executor.shutdownNow();
            shutdown(server);
            shutdown(client);
        }
    }

    private static Endpoint bindEndpoint() throws Throwable {
        try (EndpointBuilder builder = new EndpointBuilder()) {
            builder.applyMinimal();
            builder.alpns(java.util.List.of(ALPN));
            return await(Endpoint.class, builder::bind);
        }
    }

    private static void shutdown(Endpoint endpoint) throws Throwable {
        try {
            await(Unit.class, endpoint::shutdown);
        } finally {
            endpoint.close();
        }
    }

    private static Connection getFuture(Future<Connection> future) throws Throwable {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ProbeFailure probeFailure && probeFailure.getCause() != null) {
                throw probeFailure.getCause();
            }
            throw cause;
        }
    }

    private static <T> T await(Class<T> expectedType, SuspendCall<T> call) throws Throwable {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Object> result = executor.submit(() -> {
            try {
                return BuildersKt.runBlocking(
                        EmptyCoroutineContext.INSTANCE,
                        (scope, continuation) -> {
                            try {
                                @SuppressWarnings("unchecked")
                                Continuation<T> typedContinuation =
                                        (Continuation<T>) (Continuation<?>) continuation;
                                return call.invoke(typedContinuation);
                            } catch (Throwable error) {
                                throw new ProbeFailure(error);
                            }
                        });
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ProbeFailure(error);
            }
        });
        try {
            return expectedType.cast(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ProbeFailure probeFailure && probeFailure.getCause() != null) {
                throw probeFailure.getCause();
            }
            throw cause;
        } finally {
            result.cancel(true);
            executor.shutdownNow();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static Bundle statusBundle() {
        Bundle status = new Bundle();
        status.putInt("current", 1);
        status.putInt("numtests", 1);
        status.putString("class", ReleaseIrohSmokeInstrumentation.class.getName());
        status.putString("test", "releaseIrohHandshake");
        return status;
    }

    @FunctionalInterface
    private interface SuspendCall<T> {
        Object invoke(Continuation<T> continuation) throws Throwable;
    }

    private static final class ProbeFailure extends RuntimeException {
        ProbeFailure(Throwable cause) {
            super(cause);
        }
    }
}
