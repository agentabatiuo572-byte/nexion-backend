package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupportAcceptanceSandboxReadTest {
    private static final Long USER_ID = 42L;
    private static final String RUN_ID = "run-1";
    private static final String CONVERSATION_ID = "ACV-1";

    @Test
    void crossConversationLastSeenCannotClearThisConversationUnreadHeader() {
        assertInvalidAgentMessageDoesNotTouchReadHeader(91L);
    }

    @Test
    void otherUsersLastSeenCannotClearThisConversationUnreadHeader() {
        assertInvalidAgentMessageDoesNotTouchReadHeader(92L);
    }

    @Test
    void userSentLastSeenCannotClearUnreadHeader() {
        assertInvalidAgentMessageDoesNotTouchReadHeader(93L);
    }

    @Test
    void unknownFutureLastSeenCannotClearUnreadHeader() {
        assertInvalidAgentMessageDoesNotTouchReadHeader(Long.MAX_VALUE);
    }

    @Test
    void scopedAgentMessageUpdatesHeaderAndReceiptsOnlyAfterAuthorityValidation() {
        SupportAcceptanceSandboxMapper mapper = mapper();
        when(mapper.agentMessageExists(RUN_ID, USER_ID, CONVERSATION_ID, 9L)).thenReturn(1);
        when(mapper.readHeaderCas(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.readCas(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(2);

        Map<String, Object> result = service(mapper).read(USER_ID, CONVERSATION_ID, request(9L));

        assertThat(result).containsKey("conversation");
        verify(mapper).readHeaderCas(eq(CONVERSATION_ID), eq(RUN_ID), eq(USER_ID), eq("OPEN"), eq(5L), org.mockito.ArgumentMatchers.any());
        verify(mapper).readCas(eq(CONVERSATION_ID), eq(RUN_ID), eq(USER_ID), eq(9L), eq("OPEN"), eq(5L), eq("user:42"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void receiptWriteFailureThrowsSoTheTransactionalHeaderCasRollsBack() {
        SupportAcceptanceSandboxMapper mapper = mapper();
        when(mapper.agentMessageExists(RUN_ID, USER_ID, CONVERSATION_ID, 9L)).thenReturn(1);
        when(mapper.readHeaderCas(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.readCas(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(0);

        assertThatThrownBy(() -> service(mapper).read(USER_ID, CONVERSATION_ID, request(9L)))
                .isInstanceOf(BizException.class).hasMessageContaining("SUPPORT_ACCEPTANCE_CONFLICT");
    }

    private void assertInvalidAgentMessageDoesNotTouchReadHeader(Long lastSeen) {
        SupportAcceptanceSandboxMapper mapper = mapper();

        assertThatThrownBy(() -> service(mapper).read(USER_ID, CONVERSATION_ID, request(lastSeen)))
                .isInstanceOf(BizException.class);

        verify(mapper).agentMessageExists(RUN_ID, USER_ID, CONVERSATION_ID, lastSeen);
        verify(mapper, never()).readHeaderCas(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).readCas(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private SupportAcceptanceSandboxMapper mapper() {
        SupportAcceptanceSandboxMapper mapper = mock(SupportAcceptanceSandboxMapper.class);
        when(mapper.sandboxUser(USER_ID)).thenReturn(1);
        when(mapper.conversation(RUN_ID, USER_ID, CONVERSATION_ID))
                .thenReturn(Map.of("conversationNo", CONVERSATION_ID, "status", "open", "version", 5L));
        when(mapper.conversationMessages(RUN_ID, USER_ID, CONVERSATION_ID)).thenReturn(List.of());
        return mapper;
    }

    private SupportAcceptanceSandboxService service(SupportAcceptanceSandboxMapper mapper) {
        return new SupportAcceptanceSandboxService(mock(SupportAcceptanceSandboxProfileGuard.class), mapper, Clock.systemUTC(), RUN_ID);
    }

    private Map<String, Object> request(Long lastSeen) {
        return Map.of("expectedStatus", "OPEN", "expectedVersion", 5L, "lastSeenMessageId", lastSeen);
    }
}
