package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ConversationIdleTimeoutSchedulerTest {
    @Test void retiredIdleCloserNeverChangesConversationsOrWritesMessages() {
        var mapper=mock(ConversationTimeoutPolicyMapper.class);
        var audit=mock(AuditLogService.class);
        var publisher=mock(ApplicationEventPublisher.class);
        var guard=mock(ProductionSupportPathGuard.class);
        var scheduler=new ConversationIdleTimeoutScheduler(mapper,audit,publisher,Clock.systemUTC(),guard);
        assertThat(scheduler.sweep()).isEqualTo(new ConversationIdleTimeoutScheduler.SweepResult(0,0));
        verifyNoInteractions(mapper,audit,publisher,guard);
        assertThat(ConversationIdleTimeoutScheduler.class.isAnnotationPresent(org.springframework.stereotype.Service.class)).isFalse();
    }
}
