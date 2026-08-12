package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.ConversationIdleCandidate;
import ffdd.opsconsole.content.domain.ConversationTimeoutPolicy;
import ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class ConversationIdleTimeoutScheduler {
    private static final int BATCH_SIZE = 100;

    private final ConversationTimeoutPolicyMapper mapper;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final ProductionSupportPathGuard productionPathGuard;

    @Scheduled(fixedDelayString = "${nexion.content.conversation-idle-timeout-delay-ms:60000}")
    @Transactional
    public SweepResult sweep() {
        if (!productionPathGuard.productionSupportAutomationAllowed()) return new SweepResult(0, 0);
        ConversationTimeoutPolicy policy = mapper.selectPolicy();
        if (policy == null) {
            return new SweepResult(0, 0);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime warnCutoff = now.minusMinutes(policy.warnMinutes());
        LocalDateTime closeCutoff = now.minusMinutes(policy.closeMinutes());
        int warned = 0;
        int closed = 0;

        for (ConversationIdleCandidate candidate
                : mapper.selectDueWarningCandidates(warnCutoff, closeCutoff, BATCH_SIZE)) {
            ConversationIdleCandidate locked = mapper.lockCandidate(candidate.conversationNo());
            if (!eligible(locked, candidate, warnCutoff)
                    || !locked.lastActivityAt().isAfter(closeCutoff)) {
                continue;
            }
            if (mapper.insertEvent(
                    locked.conversationNo(),
                    "WARN",
                    locked.lastActivityAt(),
                    policy.version(),
                    now) != 1) {
                continue;
            }
            String message = "当前会话已闲置 " + policy.warnMinutes()
                    + " 分钟,约 " + (policy.closeMinutes() - policy.warnMinutes())
                    + " 分钟后自动结束;继续发送消息可保持会话。";
            mapper.insertSystemMessage(locked.id(), locked.conversationNo(), message, now);
            publish(locked, ConversationMessageEvent.EventType.MESSAGE, message, now);
            warned++;
        }

        for (ConversationIdleCandidate candidate : mapper.selectDueCloseCandidates(closeCutoff, BATCH_SIZE)) {
            ConversationIdleCandidate locked = mapper.lockCandidate(candidate.conversationNo());
            if (!eligible(locked, candidate, closeCutoff)) {
                continue;
            }
            if (mapper.insertEvent(
                    locked.conversationNo(),
                    "CLOSE",
                    locked.lastActivityAt(),
                    policy.version(),
                    now) != 1) {
                continue;
            }
            String message = "会话已因用户闲置 " + policy.closeMinutes() + " 分钟自动结束,可重新发起会话。";
            if (mapper.closeIfStillIdle(locked.conversationNo(), locked.lastActivityAt(), message, now) != 1) {
                throw new IllegalStateException("M3_TIMEOUT_CLOSE_CAS_FAILED");
            }
            mapper.insertSystemMessage(locked.id(), locked.conversationNo(), message, now);
            publish(locked, ConversationMessageEvent.EventType.STATUS, message, now);
            closed++;
        }

        if (warned > 0 || closed > 0) {
            auditLogService.recordRequired(AuditLogWriteRequest.builder()
                    .action("M3_CONVERSATION_IDLE_TIMEOUT_APPLIED")
                    .resourceType("CONVERSATION_TIMEOUT_POLICY")
                    .resourceId("GLOBAL")
                    .actorType("SYSTEM")
                    .actorUsername("conversation-idle-timeout-scheduler")
                    .result("SUCCESS")
                    .riskLevel("MEDIUM")
                    .detail(Map.of(
                            "policyVersion", policy.version(),
                            "warnMinutes", policy.warnMinutes(),
                            "closeMinutes", policy.closeMinutes(),
                            "warned", warned,
                            "closed", closed))
                    .build());
        }
        return new SweepResult(warned, closed);
    }

    private boolean eligible(
            ConversationIdleCandidate locked,
            ConversationIdleCandidate candidate,
            LocalDateTime cutoff) {
        return locked != null
                && "OPEN".equals(locked.status())
                && Objects.equals(locked.lastActivityAt(), candidate.lastActivityAt())
                && !locked.lastActivityAt().isAfter(cutoff);
    }

    private void publish(
            ConversationIdleCandidate candidate,
            ConversationMessageEvent.EventType type,
            String message,
            LocalDateTime now) {
        ConversationMessageEvent event = ConversationMessageEvent.builder()
                .conversationNo(candidate.conversationNo())
                .eventType(type)
                .senderType("SYSTEM")
                .senderName("系统")
                .body(message)
                .ts(now)
                .build();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eventPublisher.publishEvent(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishEvent(event);
            }
        });
    }

    public record SweepResult(int warned, int closed) {
    }
}
