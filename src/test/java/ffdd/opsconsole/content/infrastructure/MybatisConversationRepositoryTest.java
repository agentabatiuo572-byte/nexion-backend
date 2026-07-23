package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.mapper.ConversationMapper;
import ffdd.opsconsole.content.mapper.ConversationMessageMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

class MybatisConversationRepositoryTest {
    private final ConversationMapper mapper = mock(ConversationMapper.class);
    private final ConversationMessageMapper messageMapper = mock(ConversationMessageMapper.class);
    private final MybatisConversationRepository repository = new MybatisConversationRepository(mapper, messageMapper);
    private final LocalDateTime now = LocalDateTime.of(2026, 7, 23, 12, 0);

    @Test
    void lockingReadAlwaysLocksHeaderBeforePendingTransfer() {
        ContentConversationView conversation = transferredConversation();
        when(mapper.lockConversationHeader("CV-RACE")).thenReturn(1L);
        when(mapper.lockPendingTransfers("CV-RACE")).thenReturn(java.util.List.of(2L));
        when(mapper.findByConversationNo("CV-RACE")).thenReturn(conversation);

        assertThat(repository.findByConversationNoForUpdate("CV-RACE")).contains(conversation);

        InOrder order = inOrder(mapper);
        order.verify(mapper).lockConversationHeader("CV-RACE");
        order.verify(mapper).lockPendingTransfers("CV-RACE");
        order.verify(mapper).findByConversationNo("CV-RACE");
    }

    @Test
    void competingTransferWritesNeitherTransferNorMessageWhenHeaderClaimLoses() {
        ContentConversationView conversation = openConversation();
        when(mapper.markTransferred("CV-RACE", "agent-2", "Agent Two", now)).thenReturn(0);

        assertThat(repository.transferToPending(
                conversation, "agent", "agent-2", "Agent Two", "needs specialist", "agent-1", now)).isFalse();

        verifyNoInteractions(messageMapper);
    }

    @Test
    void acceptAndReturnWriteNoHeaderOrMessageWhenPendingTransferClaimLoses() {
        ContentConversationView conversation = transferredConversation();
        when(mapper.markTransferAccepted("CV-RACE", "agent-2", now)).thenReturn(0);
        when(mapper.markTransferReturned("CV-RACE", "return to owner", "agent-2", now)).thenReturn(0);

        assertThat(repository.acceptTransfer(conversation, "agent-2", "Agent Two", "agent-2", now)).isFalse();
        assertThat(repository.returnTransfer(conversation, "return to owner", "agent-2", now)).isFalse();

        verifyNoInteractions(messageMapper);
    }

    @Test
    void waitReplyStatusAndArchiveWriteNoMessageWhenHeaderCasLoses() {
        ContentConversationView transferred = transferredConversation();
        ContentConversationView open = openConversation();
        when(mapper.markTransferWait("CV-RACE", "转入会话继续等待: continue waiting", now)).thenReturn(0);
        when(mapper.replyConversation("CV-RACE", "reply", "OPEN", now)).thenReturn(0);
        when(mapper.updateConversationStatus("CV-RACE", "RESOLVED", "OPEN", now)).thenReturn(0);
        when(mapper.updateConversationStatus("CV-RACE", "CLOSED", "OPEN", now)).thenReturn(0);

        assertThat(repository.waitTransfer(transferred, "continue waiting", "agent-2", now)).isFalse();
        assertThat(repository.reply(open, "reply", "agent-1", now)).isFalse();
        assertThat(repository.updateStatus(open, "RESOLVED", "agent-1", now)).isFalse();
        assertThat(repository.archive(open, true, "agent-1", now)).isFalse();

        verifyNoInteractions(messageMapper);
    }

    @Test
    void invariantFailureAfterTransferRowClaimEscapesBeforeSystemMessage() {
        ContentConversationView conversation = transferredConversation();
        when(mapper.markTransferAccepted("CV-RACE", "agent-2", now)).thenReturn(1);
        when(mapper.acceptConversation("CV-RACE", "agent-2", "Agent Two", now)).thenReturn(0);

        assertThatThrownBy(() -> repository.acceptTransfer(conversation, "agent-2", "Agent Two", "agent-2", now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CONVERSATION_ACCEPT_HEADER_UPDATE_FAILED");
        verifyNoInteractions(messageMapper);
    }

    @Test
    void duplicatePendingTransferRowsFailClosedBeforeHeaderAndMessage() {
        ContentConversationView conversation = transferredConversation();
        when(mapper.markTransferAccepted("CV-RACE", "agent-2", now)).thenReturn(2);

        assertThatThrownBy(() -> repository.acceptTransfer(conversation, "agent-2", "Agent Two", "agent-2", now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CONVERSATION_ACCEPT_TRANSFER_CARDINALITY_INVALID");
        verifyNoInteractions(messageMapper);
    }

    @Test
    void uniquePendingTransferViolationEscapesBeforeSystemMessage() {
        ContentConversationView conversation = openConversation();
        when(mapper.markTransferred("CV-RACE", "agent-2", "Agent Two", now)).thenReturn(1);
        when(mapper.insertTransfer(
                "CV-RACE", "agent-1", "Agent One", "agent", "agent-2", "Agent Two",
                "needs specialist", "agent-1", now)).thenThrow(new DuplicateKeyException("duplicate active transfer"));

        assertThatThrownBy(() -> repository.transferToPending(
                conversation, "agent", "agent-2", "Agent Two", "needs specialist", "agent-1", now))
                .isInstanceOf(DuplicateKeyException.class);
        verifyNoInteractions(messageMapper);
    }

    private ContentConversationView openConversation() {
        return new ContentConversationView(
                1L, "CV-RACE", 1001L, "support", "OPEN", "agent-1", "Agent One", 0,
                "hello", now, null, null, null, null, null, null, null, now);
    }

    private ContentConversationView transferredConversation() {
        return new ContentConversationView(
                1L, "CV-RACE", 1001L, "support", "TRANSFERRED", "agent-2", "Agent Two", 0,
                "hello", now, "agent-1", "Agent One", "agent", "agent-2", "Agent Two",
                "needs specialist", now, now);
    }
}
