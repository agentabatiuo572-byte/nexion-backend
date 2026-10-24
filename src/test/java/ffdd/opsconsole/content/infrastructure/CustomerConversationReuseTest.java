package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.mapper.ConversationMapper;
import ffdd.opsconsole.content.mapper.ConversationMessageMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CustomerConversationReuseTest {
    @org.junit.jupiter.api.BeforeEach void transaction() { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true); }
    @org.junit.jupiter.api.AfterEach void clearTransaction() { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
    final ConversationMapper mapper=mock(ConversationMapper.class);
    final ConversationMessageMapper messages=mock(ConversationMessageMapper.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final MybatisConversationRepository repository=new MybatisConversationRepository(mapper,messages,ownership);
    final LocalDateTime now=LocalDateTime.of(2026,10,24,10,0);
    final ContentConversationView row=new ContentConversationView(1L,"CV-OLD",42L,"advisor","OPEN","7","Agent",0,"before",now,null,null,null,null,null,null,null,now);
    void existing() {
        when(mapper.currentCustomerConversation(42L)).thenReturn("CV-OLD");
        when(mapper.lockConversationHeader("CV-OLD")).thenReturn(1L);
        when(mapper.findCurrentByConversationNo("CV-OLD")).thenReturn(row);
        when(mapper.findByConversationNo("CV-OLD")).thenReturn(row);
        when(mapper.resumeCustomerConversation("CV-OLD",0L,now)).thenReturn(1);
        when(messages.insert(any(ConversationMessageEntity.class))).thenAnswer(call->{((ConversationMessageEntity)call.getArgument(0)).setId(91L);return 1;});
    }
    @Test void inboundEntryReusesCustomerWindowWithoutNewHeaderOrPolicySnapshot() {
        existing();when(mapper.replyConversationAsUser("CV-OLD",42L,"hello","OPEN",0L,now)).thenReturn(1);
        assertThat(repository.createUserConversation("CV-NEW",42L,"advisor","hello","7","Agent",now).conversationNo()).isEqualTo("CV-OLD");
        verify(ownership).lockCustomer(42L);verify(mapper,never()).insert(any(ConversationEntity.class));verify(mapper,never()).insertTimeoutSnapshot(anyString());
        verify(messages).insert(argThat((ConversationMessageEntity m)->m.getConversationNo().equals("CV-OLD")&&m.getSenderType().equals("user")&&m.getSenderId().equals(42L)));
    }
    @Test void outboundEntryKeepsOriginalWindowAndExactMessageId() {
        existing();when(mapper.replyConversation("CV-OLD","hello","OPEN",0L,now)).thenReturn(1);
        var result=repository.createConversationWithMessage("CV-NEW",42L,"advisor","7","Agent","hello",7L,"Agent",now);
        assertThat(result.conversation().conversationNo()).isEqualTo("CV-OLD");assertThat(result.messageId()).isEqualTo(91L);
        verify(mapper,never()).insert(any(ConversationEntity.class));verify(ownership).lockCustomer(42L);
    }
    @Test void transferOrVersionConflictDoesNotAppendOrFork() {
        existing();when(mapper.resumeCustomerConversation("CV-OLD",0L,now)).thenReturn(0);
        assertThatThrownBy(()->repository.createUserConversation("CV-NEW",42L,"advisor","hello","7","Agent",now)).hasMessageContaining("CONVERSATION_TRANSFER_PENDING");
        verifyNoInteractions(messages);verify(mapper,never()).insert(any(ConversationEntity.class));
    }
}
