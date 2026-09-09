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
        when(repo.messages("CV-1")).thenReturn(List.of(new ContentConversationMessageView(10L,1L,"CV-1",12L,"user","u","hi",null,null)));
        when(repo.markUserMessagesReadThrough(eq(c),eq(10L),anyString(),any())).thenReturn(true,false);
        var events=mock(ApplicationEventPublisher.class);
        var service=new ConversationAdminReadService(repo,events,mock(ProductionSupportPathGuard.class),Clock.systemUTC());
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(0,service.read("CV-1",new MarkReadRequest(10L,"OPEN",7L),"seat").getCode());
            assertEquals(0,service.read("CV-1",new MarkReadRequest(10L,"OPEN",7L),"seat").getCode());
            verifyNoInteractions(events);
            var callbacks=TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1,callbacks.size());
            callbacks.forEach(TransactionSynchronization::afterCommit);
            verify(events,times(1)).publishEvent(any(Object.class));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void aRolledBackReadDoesNotPublishAReceipt() {
        var repo=mock(ConversationRepository.class);
        var c=new ContentConversationView(1L,"CV-1",12L,"support","OPEN","1","seat",2,"hello",null,null,null,null,null,null,null,null,null,7L);
        when(repo.findByConversationNoForUpdate("CV-1")).thenReturn(Optional.of(c));
        when(repo.messages("CV-1")).thenReturn(List.of(new ContentConversationMessageView(10L,1L,"CV-1",12L,"user","u","hi",null,null)));
        when(repo.markUserMessagesReadThrough(eq(c),eq(10L),anyString(),any())).thenReturn(true);
        var events=mock(ApplicationEventPublisher.class);
        var service=new ConversationAdminReadService(repo,events,mock(ProductionSupportPathGuard.class),Clock.systemUTC());
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
        when(repo.messages("CV-1")).thenReturn(List.of(new ContentConversationMessageView(10L,1L,"CV-1",12L,"user","u","hi",null,null)));
        var events=mock(ApplicationEventPublisher.class);
        var service=new ConversationAdminReadService(repo,events,mock(ProductionSupportPathGuard.class),Clock.systemUTC());
        assertEquals(409,service.read("CV-1",new MarkReadRequest(10L,"OPEN",6L),"seat").getCode());
        assertEquals(404,service.read("CV-1",new MarkReadRequest(11L,"OPEN",7L),"seat").getCode());
        verify(repo,never()).markUserMessagesReadThrough(any(),any(),any(),any());
        when(repo.markUserMessagesReadThrough(eq(c),eq(10L),anyString(),any())).thenReturn(true);
        assertEquals(0,service.read("CV-1",new MarkReadRequest(10L,"OPEN",7L),"seat").getCode());
        verify(events).publishEvent(any(Object.class));
    }
}
