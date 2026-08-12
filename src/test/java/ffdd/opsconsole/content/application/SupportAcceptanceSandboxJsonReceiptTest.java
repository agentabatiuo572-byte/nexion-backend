package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupportAcceptanceSandboxJsonReceiptTest {
    private static final String RUN_ID = "run-1";
    private static final Long USER_ID = 42L;

    @Test
    void conversationCommandPersistsAReplayableReceiptContainingJavaTimeValues() {
        SupportAcceptanceSandboxMapper mapper = mock(SupportAcceptanceSandboxMapper.class);
        SupportAcceptanceSandboxProfileGuard guard = mock(SupportAcceptanceSandboxProfileGuard.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T09:44:18Z"), ZoneOffset.UTC);
        LocalDateTime createdAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        when(mapper.sandboxUser(USER_ID)).thenReturn(1);
        when(mapper.command(any(), eq(RUN_ID), eq(USER_ID))).thenReturn(null);
        when(mapper.conversation(eq(RUN_ID), eq(USER_ID), any())).thenAnswer(invocation -> Map.of(
                "conversationNo", (Object) invocation.getArgument(2),
                "createdAt", createdAt));
        when(mapper.conversationMessages(eq(RUN_ID), eq(USER_ID), any())).thenReturn(List.of(Map.of(
                "id", 1L,
                "createdAt", createdAt)));

        SupportAcceptanceSandboxService service = new SupportAcceptanceSandboxService(guard, mapper, clock, RUN_ID);

        assertThatCode(() -> service.startConversation(USER_ID, "support-command-1", Map.of(
                "conversationType", "SUPPORT",
                "openingText", "Acceptance support message")))
                .doesNotThrowAnyException();

        verify(mapper).commandInsert(eq("support-command-1"), eq(RUN_ID), eq(USER_ID),
                eq("CONVERSATION_CREATE"), eq("support-command-1"), eq("app"), any(),
                org.mockito.ArgumentMatchers.contains("2026-08-12T09:44:18"),
                eq("conversation"), any(), eq(createdAt));
    }
}
