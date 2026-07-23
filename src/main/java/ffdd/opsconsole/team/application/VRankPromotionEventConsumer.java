package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.VRankPromotionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * F1 V-Rank 晋升事件消费者(Sprint4)。
 *
 * <p>订阅 outbox 分发的 {@link EventOutboxMessage}(eventType=VRANK_PROMOTION_COMPLETED),
 * 找晋升者的 L1 上级 sponsor → 触发上级 re-evaluate(下家晋升可能使上级也满足更高阶,链式级联)。
 *
 * <h2>级联终止保障</h2>
 * <ul>
 *   <li>nx_team_member L1 是树结构(无环),到 root(无 L1 上级)自然终止。</li>
 *   <li>evaluate 幂等:上级达稳态后 v_rank 不变 → 不发新事件 → 链终止。</li>
 *   <li>异步链式:每轮 DispatchScheduler 处理一批,evaluate 发的新事件下一轮处理(非递归,无栈溢出)。</li>
 * </ul>
 *
 * <p>消费失败 → 抛异常触发 DispatchScheduler markFailed(retry);不阻断其他事件分发。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VRankPromotionEventConsumer {

    private final ObjectMapper objectMapper;
    private final TeamCommissionRepository commissionRepository;
    private final VRankPromotionEngine vRankPromotionEngine;

    @EventListener
    public void onPromotionCompleted(EventOutboxMessage message) {
        if (message == null || !VRankPromotionEngine.PROMOTION_EVENT_TYPE.equals(message.getEventType())) {
            return;
        }
        Long userId = null;
        String toCode = "?";
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            if (node.has("userId")) {
                userId = node.get("userId").asLong();
            }
            if (node.has("toCode")) {
                toCode = node.get("toCode").asText();
            }
            if (userId == null) {
                log.warn("VRANK_PROMOTION_COMPLETED payload missing userId, skip: eventId={}", message.getEventId());
                return;
            }
            // 级联:找 L1 上级 sponsor(findSponsorUserId 含 nx_sponsorship→nx_team_member L1 fallback)
            Long sponsorUserId = commissionRepository.findSponsorUserId(userId);
            if (sponsorUserId == null) {
                log.debug("VRANK_PROMOTION_COMPLETED cascade ends (no L1 sponsor): user={} toCode={}", userId, toCode);
                return;
            }
            log.info("VRANK_PROMOTION_COMPLETED cascade: re-evaluate sponsor={} (descendant={} → {})",
                    sponsorUserId, userId, toCode);
            // evaluate 幂等:上级稳态不变则 return(不发新事件→链终止)
            String sponsorRank = vRankPromotionEngine.evaluate(VRankPromotionContext.systemEvaluation(sponsorUserId));
            log.info("VRANK_PROMOTION_COMPLETED cascade done: sponsor={} evaluated rank={}", sponsorUserId, sponsorRank);
        } catch (JsonProcessingException ex) {
            log.error("VRANK_PROMOTION_COMPLETED payload parse failed: eventId={}, err={}",
                    message.getEventId(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("VRANK_PROMOTION_COMPLETED consume failed (will retry): eventId={}, userId={}, err={}",
                    message.getEventId(), userId, ex.getMessage());
            throw ex;
        }
    }
}
