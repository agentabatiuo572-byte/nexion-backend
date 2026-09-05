package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.content.dto.NovaAiChatRequest;
import ffdd.opsconsole.content.mapper.AppNovaConversationMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AppNovaAiConcurrencyTest {
    private static final String SESSION = "a".repeat(64);
    private final NovaAiGateway gateway = mock(NovaAiGateway.class);
    private final AppNovaConversationMapper mapper = mock(AppNovaConversationMapper.class);
    private final NovaAiProperties properties = new NovaAiProperties();
    private final AppNovaAiService service = new AppNovaAiService(gateway, properties, mapper,
            mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class));

    private void setup() {
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        properties.setMaxConcurrentRequests(4);
        when(mapper.insertTurn(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void sameConversationAndDuplicateTurnsNeverOverlapAndSlotsAreReleased() throws Exception {
        setup();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(gateway.chat(any())).thenAnswer(inv -> {
            calls.incrementAndGet(); entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return "answer";
        });
        var conversation = UUID.randomUUID().toString();
        var first = request(conversation, "first");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var active = executor.submit(() -> service.chat(1L, SESSION, first));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.chat(1L, SESSION, first))
                    .isInstanceOf(BizException.class).hasMessage("NOVA_AI_TURN_IN_PROGRESS");
            assertThatThrownBy(() -> service.chat(1L, SESSION, request(conversation, "second")))
                    .isInstanceOf(BizException.class).hasMessage("NOVA_AI_CONVERSATION_BUSY");
            assertThatThrownBy(() -> service.chat(1L, SESSION, new NovaAiChatRequest(
                    "changed", "zh", conversation, first.turnId(), List.of())))
                    .isInstanceOf(BizException.class).hasMessage("NOVA_AI_TURN_CONFLICT");
            assertThat(calls.get()).isEqualTo(1);
            release.countDown();
            assertThat(active.get(3, TimeUnit.SECONDS).reply()).isEqualTo("answer");
            assertThat(service.chat(1L, SESSION, request(conversation, "next")).reply()).isEqualTo("answer");
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void unrelatedUsersAndConversationsRetainIndependentCapacity() throws Exception {
        setup();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(gateway.chat(any())).thenAnswer(inv -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return "answer";
        });
        var executor = Executors.newFixedThreadPool(2);
        var request = request(UUID.randomUUID().toString(), "question");
        try {
            var first = executor.submit(() -> service.chat(1L, SESSION, request));
            var second = executor.submit(() -> service.chat(2L, SESSION, request));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void exceptionReleasesConversationAndGlobalCapacityForRetry() {
        setup(); properties.setMaxConcurrentRequests(1);
        var request = request(UUID.randomUUID().toString(), "question");
        when(gateway.chat(any())).thenThrow(new BizException(504, "NOVA_AI_TIMEOUT")).thenReturn("answer");
        assertThatThrownBy(() -> service.chat(1L, SESSION, request)).hasMessage("NOVA_AI_TIMEOUT");
        assertThat(service.chat(1L, SESSION, request).reply()).isEqualTo("answer");
    }

    private NovaAiChatRequest request(String conversation, String message) {
        return new NovaAiChatRequest(message, "zh", conversation, UUID.randomUUID().toString(), List.of());
    }
}
