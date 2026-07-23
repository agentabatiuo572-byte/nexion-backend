package ffdd.opsconsole.shared.outbox;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers durable outbox messages into the modular-monolith event bus. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventOutboxDispatchScheduler {
    private static final int BATCH_SIZE = 100;
    static final String SUPPORTED_EVENT_TYPE = "ADMIN_KILLSWITCH_TOGGLED";
    static final String TAMPER_EVENT_TYPE = "RISK_TAMPER_DETECTED";
    static final String TAMPER_CONFIG_EVENT_TYPE = "ADMIN_J3_TAMPER_CONFIG_CHANGED";
    static final String D5_WITHDRAWAL_LIMIT_CHANGED_EVENT_TYPE = "admin.withdraw_limit_changed";
    static final String K4_WITHDRAWAL_ESCALATED_EVENT_TYPE = "risk.withdraw_escalated";
    /** Sprint4: F1 V-Rank 晋升完成(Consumer 级联 L1 上级 re-eval)。 */
    static final String VRANK_PROMOTION_COMPLETED_EVENT_TYPE = "VRANK_PROMOTION_COMPLETED";
    /** H3 trusted cross-domain facts; mappings remain data-owned in nx_growth_quest_event_binding. */
    static final List<String> H3_QUEST_FACT_EVENT_TYPES = List.of(
            "checkout.started",
            "H8_REFERRAL_REWARD_SETTLED",
            "LEARNING_COURSE_COMPLETED",
            "admin.device_activated",
            "COMMISSION_UNLOCKED");
    /** Sprint4 阶段2: F1 被动评估触发漏斗(用户 checkout/kyc/register → evaluate,analytics 已发 outbox)。 */
    static final List<String> F1_PASSIVE_EVAL_EVENT_TYPES = List.of(
            "checkout.completed",
            "kyc.express_verified",
            "auth.register_completed");
    static final List<String> C2_HIGH_RISK_EVENT_TYPES = List.of(
            "admin.user_frozen",
            "admin.user_unfrozen",
            "admin.user_impersonation_started",
            "admin.user_impersonation_ended");
    static final List<String> C5_SECURITY_EVENT_TYPES = List.of(
            "admin.2fa_disabled",
            "admin.password_reset_requested",
            "admin.user_unlocked",
            "admin.session_revoked",
            "auth.login_locked",
            "auth.refresh_token_reuse_detected");

    private final EventOutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(
            fixedDelayString = "${nexion.outbox.dispatch-delay-ms:1000}",
            initialDelayString = "${nexion.outbox.dispatch-initial-delay-ms:1000}")
    public void dispatchPending() {
        // Only dispatch event types that have a synchronous, durable consumer.
        // Publishing an unknown Spring event succeeds even when it has no listener;
        // selecting all event types here would therefore falsely mark them PUBLISHED.
        List<String> supportedEventTypes = new java.util.ArrayList<>(
                List.of(SUPPORTED_EVENT_TYPE, TAMPER_EVENT_TYPE, TAMPER_CONFIG_EVENT_TYPE,
                        D5_WITHDRAWAL_LIMIT_CHANGED_EVENT_TYPE,
                        K4_WITHDRAWAL_ESCALATED_EVENT_TYPE,
                        VRANK_PROMOTION_COMPLETED_EVENT_TYPE));
        supportedEventTypes.addAll(C2_HIGH_RISK_EVENT_TYPES);
        supportedEventTypes.addAll(C5_SECURITY_EVENT_TYPES);
        supportedEventTypes.addAll(H3_QUEST_FACT_EVENT_TYPES);
        supportedEventTypes.addAll(F1_PASSIVE_EVAL_EVENT_TYPES);
        for (String eventType : supportedEventTypes) {
            List<EventOutboxMessage> pending = outboxService.listPendingByEventType(eventType, BATCH_SIZE);
            if (pending == null || pending.isEmpty()) {
                continue;
            }
            for (EventOutboxMessage message : pending) {
                try {
                    eventPublisher.publishEvent(message);
                    if (!outboxService.markPublished(message.getEventId())) {
                        log.warn("Outbox message was delivered but status was not updated eventId={}", message.getEventId());
                    }
                } catch (RuntimeException ex) {
                    outboxService.markFailed(message.getEventId(), ex.getMessage());
                    log.warn("Outbox delivery failed eventId={}, eventType={}, error={}",
                            message.getEventId(), message.getEventType(), ex.getMessage());
                }
            }
        }
    }
}
