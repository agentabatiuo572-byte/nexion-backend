package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;

/** Retired idle closer. Reply deadlines are read state and never terminate a customer thread. */
@Deprecated
@RequiredArgsConstructor
public class ConversationIdleTimeoutScheduler {
    private final ConversationTimeoutPolicyMapper mapper;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final ProductionSupportPathGuard productionPathGuard;

    public SweepResult sweep() { return new SweepResult(0, 0); }
    public record SweepResult(int warned, int closed) {}
}
