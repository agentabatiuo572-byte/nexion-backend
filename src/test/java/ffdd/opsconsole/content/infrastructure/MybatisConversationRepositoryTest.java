package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.mapper.ConversationMapper;
import ffdd.opsconsole.content.mapper.ConversationMessageMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DuplicateKeyException;

class MybatisConversationRepositoryTest {
    private final ConversationMapper mapper = mock(ConversationMapper.class);
    private final ConversationMessageMapper messageMapper = mock(ConversationMessageMapper.class);
    private final MybatisConversationRepository repository = new MybatisConversationRepository(mapper, messageMapper, ffdd.opsconsole.content.SupportTestDependencies.ownership());
    private final LocalDateTime now = LocalDateTime.of(2026, 7, 23, 12, 0);

    @Test void collectionCountAndRowsUseTheSameExplicitScope() {
        var scope=new ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope(7L,
                ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.MANAGED,9L,null);
        when(mapper.countConversationsScoped(null,null,null,null,"needle",null,null,scope)).thenReturn(2L);
        var request=new ffdd.opsconsole.content.dto.ConversationQueryRequest(null,null,null,null,"needle",null,2L,10L);
        assertThat(repository.pageConversations(request,scope).getTotal()).isEqualTo(2L);
        org.mockito.Mockito.verify(mapper).pageConversationsScoped(null,null,null,"needle",null,null,null,false,10L,10L,null,scope);
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).countConversations(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void missingCollectionScopeNeverFallsBackToAppQuery() {
        assertThatThrownBy(()->repository.pageConversations(null,null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void lockingReadAlwaysLocksHeaderBeforePendingTransfer() {
        ContentConversationView conversation = transferredConversation();
        when(mapper.lockConversationHeader("CV-RACE")).thenReturn(1L);
        when(mapper.lockPendingTransfers("CV-RACE")).thenReturn(java.util.List.of(2L));
        when(mapper.findCurrentByConversationNo("CV-RACE")).thenReturn(conversation);

        assertThat(repository.findByConversationNoForUpdate("CV-RACE")).contains(conversation);

        InOrder order = inOrder(mapper);
        order.verify(mapper).lockConversationHeader("CV-RACE");
        order.verify(mapper).lockPendingTransfers("CV-RACE");
        order.verify(mapper).findCurrentByConversationNo("CV-RACE");
    }

    @Test
    void competingTransferWritesNeitherTransferNorMessageWhenHeaderClaimLoses() {
        ContentConversationView conversation = openConversation();
        when(mapper.markTransferred("CV-RACE", "agent-2", "Agent Two", 0L, now)).thenReturn(0);

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
        assertThat(repository.returnTransfer(conversation, "from", "return to owner", "agent-2", now)).isFalse();

        verifyNoInteractions(messageMapper);
    }

    @Test
    void waitReplyStatusAndArchiveWriteNoMessageWhenHeaderCasLoses() {
        ContentConversationView transferred = transferredConversation();
        ContentConversationView open = openConversation();
        when(mapper.markTransferWait("CV-RACE", "转入会话继续等待: continue waiting", 0L, now)).thenReturn(0);
        when(mapper.replyConversation("CV-RACE", "reply", "OPEN", 0L, now)).thenReturn(0);
        when(mapper.updateConversationStatus("CV-RACE", "RESOLVED", "OPEN", 0L, now)).thenReturn(0);
        when(mapper.updateConversationStatus("CV-RACE", "CLOSED", "OPEN", 0L, now)).thenReturn(0);

        assertThat(repository.waitTransfer(transferred, "continue waiting", "agent-2", now)).isFalse();
        assertThat(repository.reply(open, "reply", "agent-1", now)).isFalse();
        assertThat(repository.updateStatus(open, "RESOLVED", "agent-1", now)).isFalse();
        assertThat(repository.archive(open, true, "agent-1", now)).isFalse();

        verifyNoInteractions(messageMapper);
    }

    @Test
    void equalBodyRepliesReturnEachExactGeneratedMessageId() {
        ContentConversationView open = openConversation();
        when(mapper.replyConversation("CV-RACE", "same", "OPEN", 0L, now)).thenReturn(1);
        AtomicLong generated = new AtomicLong(76L);
        when(messageMapper.insert(any(ConversationMessageEntity.class))).thenAnswer(invocation -> {
            ConversationMessageEntity entity = invocation.getArgument(0);
            entity.setId(generated.incrementAndGet());
            return 1;
        });

        Long first = repository.replyAndReturnMessageId(open, "same", "agent-1", now);
        Long second = repository.replyAndReturnMessageId(open, "same", "agent-2", now);

        assertThat(first).isEqualTo(77L);
        assertThat(second).isEqualTo(78L);
    }

    @Test
    void invariantFailureAfterTransferRowClaimEscapesBeforeSystemMessage() {
        ContentConversationView conversation = transferredConversation();
        when(mapper.markTransferAccepted("CV-RACE", "agent-2", now)).thenReturn(1);
        when(mapper.acceptConversation("CV-RACE", "agent-2", "Agent Two", 0L, now)).thenReturn(0);

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
        when(mapper.markTransferred("CV-RACE", "agent-2", "Agent Two", 0L, now)).thenReturn(1);
        when(mapper.insertTransfer(
                "CV-RACE", "agent-1", "Agent One", "agent", "agent-2", "Agent Two",
                "needs specialist", "agent-1", now)).thenThrow(new DuplicateKeyException("duplicate active transfer"));

        assertThatThrownBy(() -> repository.transferToPending(
                conversation, "agent", "agent-2", "Agent Two", "needs specialist", "agent-1", now))
                .isInstanceOf(DuplicateKeyException.class);
        verifyNoInteractions(messageMapper);
    }

    @Test void successfulUserAndAgentSegmentsWritePolicyBeforeHeaderAndMessageInSameTransaction() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            when(mapper.insertTimeoutSnapshot("CV-RACE")).thenReturn(1);
            when(mapper.findByConversationNo("CV-RACE")).thenReturn(openConversation());
            repository.createUserConversation("CV-RACE",1001L,"support","hello",now);
            repository.createConversationWithMessage("CV-RACE",1001L,"advisor","7","Agent","hello",7L,"Agent",now);
            var order=inOrder(mapper,messageMapper);
            order.verify(mapper).insertTimeoutSnapshot("CV-RACE");
            order.verify(mapper).insert(any(ConversationEntity.class));
            order.verify(messageMapper).insert(any(ConversationMessageEntity.class));
            order.verify(mapper).insertTimeoutSnapshot("CV-RACE");
            order.verify(mapper).insert(any(ConversationEntity.class));
            order.verify(messageMapper).insert(any(ConversationMessageEntity.class));
        } finally {org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
    }

    @Test void missingPolicyNeverCreatesHumanHeaderOrMessage() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(()->repository.createUserConversation("CV-RACE",1001L,"support","hello",now))
                    .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class).hasMessage("M3_TIMEOUT_POLICY_NOT_CONFIGURED");
            org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).insert(any(ConversationEntity.class));
            verifyNoInteractions(messageMapper);
        } finally {org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
    }

    @Test void humanSegmentCreationOutsideTransactionFailsBeforeAnyWrite() {
        assertThatThrownBy(()->repository.createUserConversation("CV-RACE",1001L,"support","hello",now))
                .isInstanceOf(IllegalStateException.class).hasMessage("CONVERSATION_CREATE_TRANSACTION_REQUIRED");
        verifyNoInteractions(mapper,messageMapper);
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
