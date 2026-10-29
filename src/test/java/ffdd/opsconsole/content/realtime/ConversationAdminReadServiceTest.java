package ffdd.opsconsole.content.realtime;
import ffdd.opsconsole.content.domain.*;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.web.AppSupportController.MarkReadRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ConversationAdminReadServiceTest {
    @Test void publishesOnlyAfterCommitAndNotForAnIdempotentReplay() {
        var repo=mock(ConversationRepository.class);
        var c=new ContentConversationView(1L,"CV-1",12L,"support","OPEN","1","seat",2,"hello",null,null,null,null,null,null,null,null,null,7L);
        when(repo.findByConversationNoForUpdate("CV-1")).thenReturn(Optional.of(c));
        when(repo.containsCustomerMessage("CV-1",10L)).thenReturn(true);
        when(repo.markUserMessagesReadThrough(eq(c),eq(10L),anyString(),any())).thenReturn(true,false);
        var events=mock(ApplicationEventPublisher.class);
        var service=new ConversationAdminReadService(ffdd.opsconsole.content.SupportTestDependencies.ownership(), repo,events,mock(ProductionSupportPathGuard.class),Clock.systemUTC());
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(0,service.read("CV-1",new MarkReadRequest(10L,"OPEN",7L),"seat").getCode());
            assertEquals(0,service.read("CV-1",new MarkReadRequest(10L,"OPEN",7L),"seat").getCode());
            verifyNoInteractions(events);
            var callbacks=TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1,callbacks.size());
            callbacks.forEach(TransactionSynchronization::afterCommit);
            verify(events,times(1)).publishEvent(any(Object.class));
            verify(repo,times(2)).containsCustomerMessage("CV-1",10L);
            verify(repo,never()).messages(anyString());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void aRolledBackReadDoesNotPublishAReceipt() {
        var repo=mock(ConversationRepository.class);
        var c=new ContentConversationView(1L,"CV-1",12L,"support","OPEN","1","seat",2,"hello",null,null,null,null,null,null,null,null,null,7L);
        when(repo.findByConversationNoForUpdate("CV-1")).thenReturn(Optional.of(c));
        when(repo.containsCustomerMessage("CV-1",10L)).thenReturn(true);
        when(repo.markUserMessagesReadThrough(eq(c),eq(10L),anyString(),any())).thenReturn(true);
        var events=mock(ApplicationEventPublisher.class);
        var service=new ConversationAdminReadService(ffdd.opsconsole.content.SupportTestDependencies.ownership(), repo,events,mock(ProductionSupportPathGuard.class),Clock.systemUTC());
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.read("CV-1",new MarkReadRequest(10L,"OPEN",7L),"seat");
            TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verifyNoInteractions(events);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void requiresTheExactVisibleUserMessageAndExpectedVersion() {
        var repo=mock(ConversationRepository.class);
        var c=new ContentConversationView(1L,"CV-1",12L,"support","OPEN","1","seat",2,"hello",null,null,null,null,null,null,null,null,null,7L);
        when(repo.findByConversationNoForUpdate("CV-1")).thenReturn(Optional.of(c));
        when(repo.containsCustomerMessage("CV-1",10L)).thenReturn(true);
        var events=mock(ApplicationEventPublisher.class);
        var service=new ConversationAdminReadService(ffdd.opsconsole.content.SupportTestDependencies.ownership(), repo,events,mock(ProductionSupportPathGuard.class),Clock.systemUTC());
        assertEquals(409,service.read("CV-1",new MarkReadRequest(10L,"OPEN",6L),"seat").getCode());
        assertEquals(404,service.read("CV-1",new MarkReadRequest(11L,"OPEN",7L),"seat").getCode());
        verify(repo,never()).markUserMessagesReadThrough(any(),any(),any(),any());
        when(repo.markUserMessagesReadThrough(eq(c),eq(10L),anyString(),any())).thenReturn(true);
        assertEquals(0,service.read("CV-1",new MarkReadRequest(10L,"OPEN",7L),"seat").getCode());
        verify(events).publishEvent(any(Object.class));
        verify(repo,never()).messages(anyString());
    }
    @Test void deniedAccessDoesNotReadAnyTargetOrHistory() {
        var repo=mock(ConversationRepository.class);
        var ownership=mock(ffdd.opsconsole.content.application.SupportOwnershipService.class);
        doThrow(new ffdd.opsconsole.shared.exception.BizException(403,"SUPPORT_SCOPE_FORBIDDEN"))
            .when(ownership).writeConversation("CV-1",true);
        var events=mock(ApplicationEventPublisher.class);
        var service=new ConversationAdminReadService(ownership,repo,events,mock(ProductionSupportPathGuard.class),Clock.systemUTC());
        var failure=assertThrows(ffdd.opsconsole.shared.exception.BizException.class,
            ()->service.read("CV-1",new MarkReadRequest(10L,"OPEN",7L),"seat"));
        assertEquals(403,failure.getCode());verifyNoInteractions(repo,events);
    }
    @Test void staleHeaderDoesNotEvenQueryTheMessageTarget() {
        var repo=mock(ConversationRepository.class);
        var c=new ContentConversationView(1L,"CV-1",12L,"support","OPEN","1","seat",2,"hello",null,null,null,null,null,null,null,null,null,7L);
        when(repo.findByConversationNoForUpdate("CV-1")).thenReturn(Optional.of(c));
        var service=new ConversationAdminReadService(ffdd.opsconsole.content.SupportTestDependencies.ownership(),repo,
            mock(ApplicationEventPublisher.class),mock(ProductionSupportPathGuard.class),Clock.systemUTC());
        assertEquals(409,service.read("CV-1",new MarkReadRequest(10L,"OPEN",6L),"seat").getCode());
        verify(repo,never()).containsCustomerMessage(anyString(),anyLong());
        verify(repo,never()).messages(anyString());
    }
}
