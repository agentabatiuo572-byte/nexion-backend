package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import ffdd.opsconsole.content.dto.NovaAiChatRequest;
import ffdd.opsconsole.content.mapper.AppNovaConversationMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AppNovaAiTimeoutReplayTest {
    @Test
    void callerTimeoutDoesNotRegenerateAndRetrySavesOnlyOneCompletedTurn() throws Exception {
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var generation = new AtomicInteger();
        var state = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int status = 200;
            if (state.compareAndSet(0, 1)) {
                generation.incrementAndGet();
                try {
                    if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("test release timed out");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                state.set(2);
                completed.countDown();
            } else if (state.get() == 1) status = 429;
            byte[] body = "{\"answer\":\"one answer\",\"model\":\"guardrail-test\"}".getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); }
            catch (java.io.IOException ignored) { /* first HTTP caller has already timed out */ }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var properties = new NovaAiProperties();
            properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
            properties.setReadTimeoutMs(1000);
            properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var mapper = mock(AppNovaConversationMapper.class);
            when(mapper.insertTurn(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
            var service = new AppNovaAiService(new RagNovaAiGateway(properties, new ObjectMapper()), properties, mapper,
                    mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class));
            var request = new NovaAiChatRequest("question", "en", UUID.randomUUID().toString(), UUID.randomUUID().toString(), List.of());
            assertThatThrownBy(() -> service.chat(42L, "a".repeat(64), request)).hasMessage("NOVA_AI_TIMEOUT");
            assertThatThrownBy(() -> service.chat(42L, "a".repeat(64), request)).hasMessage("NOVA_AI_BUSY");
            verify(mapper, never()).insertTurn(any(), any(), any(), any(), any(), any(), any(), any());
            release.countDown();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(service.chat(42L, "a".repeat(64), request).reply()).isEqualTo("one answer");
            assertThat(generation.get()).isEqualTo(1);
            verify(mapper, times(1)).insertTurn(any(), any(), any(), any(), any(), any(), any(), any());
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }
}
