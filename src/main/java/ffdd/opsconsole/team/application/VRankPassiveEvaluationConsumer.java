package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.team.domain.VRankPromotionContext;
import java.math.BigDecimal;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * F1 V-Rank 被动评估 + F2 unilevel 佣金结算消费者(Sprint4 阶段2)。
 *
 * <p>订阅 outbox 分发的 A4 漏斗事件:
 * <ul>
 *   <li>checkout.completed → F1 evaluate(buyer 升阶) + F2 settle(L1-L7 上级 unilevel 佣金);</li>
 *   <li>kyc.express_verified / auth.register_completed → F1 evaluate(解锁 directRefs/初始评估)。</li>
 * </ul>
 *
 * <p>evaluate 幂等(稳态不变 return);F2 settle 幂等(同 orderNo+ancestor+network)。
 * 评估/结算失败不抛(避免 retry 死循环 + 阻塞 DispatchScheduler);遗漏靠 VRankPromotionScheduler 兜底。
 *
 * <p>⚠️ 生产性能:漏斗事件量大(checkout 每次支付),同步处理可能阻塞 DispatchScheduler。
 *
 * <p><b>@Async 待异步事务设计(P3 评估,暂不改)</b>:@EventListener + @Async + @EnableAsync 可解除 DispatchScheduler
 * 阻塞,但存在风险:① evaluate 是 @Transactional,@Async 异步执行需保证事务边界在异步线程内独立开启(此处跨 bean 调用,
 * Spring 代理可达,无自调用问题);② 异步并发可能导致同用户多次 evaluate 竞态(v_rank UPDATE 丢失更新 + 级联 L1 上级乱序);
 * ③ 漏斗事件顺序依赖(checkout 先于 kyc 解锁场景)。在引入乐观锁/分布式锁前保持同步,避免事务 bug。
 *
 * <p><b>E2 checkout 联调(跨域,记录)</b>:settleUnilevelIfOrderPresent 依赖 checkout envelope 携带
 * order_subtotal_usdt / order_no 字段;真实 checkout 事件由 E2 订单域发布,待 E2 域 checkout.completed 事件
 * 结构稳定后联调对齐字段名。当前仅消费 envelope 已有字段,缺失则跳过 F2(订单结算靠定时/对账补)。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VRankPassiveEvaluationConsumer {

    private static final Set<String> TRIGGER_TYPES = Set.of(
            "checkout.completed",
            "kyc.express_verified",
            "auth.register_completed");
    private static final String CHECKOUT_COMPLETED = "checkout.completed";

    private final ObjectMapper objectMapper;
    private final VRankPromotionEngine vRankPromotionEngine;
    private final UnilevelCommissionService unilevelCommissionService;

    @EventListener
    public void onPassiveEvalTrigger(EventOutboxMessage message) {
        if (message == null || !TRIGGER_TYPES.contains(message.getEventType())) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            long userId = readLong(node, "user_id", "userId");
            if (userId <= 0) {
                log.debug("Passive eval trigger skipped (no userId): type={} eventId={}",
                        message.getEventType(), message.getEventId());
                return;
            }
            // F1 被动评估(checkout/kyc/register → 用户 V-Rank 升阶)
            // P2 traceability 修复:传 outbox eventId 作 sourceEventId → nx_user_level_log.trigger_event_id
            // (原 systemEvaluation(userId) 不带 sourceEventId → trigger_event_id 恒 NULL,可追溯性断)
            log.info("F1 passive eval trigger: type={} user={} eventId={} → evaluate", message.getEventType(), userId, message.getEventId());
            vRankPromotionEngine.evaluate(new VRankPromotionContext(
                    userId,
                    VRankPromotionContext.TriggerType.SYSTEM_EVALUATION,
                    message.getEventId(),
                    "ENGINE"));
            // F2 unilevel 佣金结算(仅 checkout.completed,需 envelope 携带订单金额/号)
            if (CHECKOUT_COMPLETED.equals(message.getEventType())) {
                settleUnilevelIfOrderPresent(userId, node, message.getEventId());
            }
        } catch (Exception ex) {
            log.warn("Passive eval/settle failed (scheduler backfills): type={} eventId={} err={}",
                    message.getEventType(), message.getEventId(), ex.getMessage());
        }
    }

    /** F2: 若 checkout envelope 携带订单金额 + 号,触发 unilevel 上级佣金结算。 */
    private void settleUnilevelIfOrderPresent(long buyerUserId, JsonNode node, String eventId) {
        BigDecimal subtotal = readDecimal(node, "order_subtotal_usdt", "orderSubtotalUsdt", "subtotal_usdt", "amount_usdt");
        String orderNo = readText(node, "order_no", "orderNo", "order_id", "orderId");
        if (subtotal == null || subtotal.signum() <= 0 || orderNo == null || orderNo.isBlank()) {
            // envelope 未携带订单字段:实际生产 checkout 事件应含;缺失则跳过 F2(订单结算靠定时/对账补)
            log.debug("F2 settle skipped (checkout envelope missing order fields): buyer={} eventId={}",
                    buyerUserId, eventId);
            return;
        }
        int settled = unilevelCommissionService.settle(buyerUserId, subtotal, orderNo);
        log.info("F2 settle on checkout: buyer={} order={} subtotal={} settled={}",
                buyerUserId, orderNo, subtotal, settled);
    }

    private static long readLong(JsonNode node, String... keys) {
        for (String k : keys) {
            if (node.has(k) && node.get(k).canConvertToLong()) {
                return node.get(k).asLong();
            }
        }
        return -1L;
    }

    private static String readText(JsonNode node, String... keys) {
        for (String k : keys) {
            if (node.has(k) && !node.get(k).isNull()) {
                String v = node.get(k).asText();
                if (v != null && !v.isBlank()) return v;
            }
        }
        return null;
    }

    private static BigDecimal readDecimal(JsonNode node, String... keys) {
        for (String k : keys) {
            if (node.has(k) && node.get(k).isNumber()) {
                return node.get(k).decimalValue();
            }
        }
        return null;
    }
}
