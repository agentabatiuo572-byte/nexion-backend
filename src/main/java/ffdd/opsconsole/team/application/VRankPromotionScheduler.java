package ffdd.opsconsole.team.application;

import ffdd.opsconsole.team.domain.VRankPromotionContext;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F1 V-Rank 兜底评估调度器(Sprint4)。
 *
 * <p>定时全量扫描 nx_team_member 自循环用户 re-evaluate,补偿:
 * <ul>
 *   <li>晋升事件发布失败(engine.publishPromotionCompleted catch 了不阻断晋升,事件丢失);</li>
 *   <li>Consumer 级联失败(outbox retry 后仍 DEAD);</li>
 *   <li>跨域触发点(checkout/kyc/register,PRD §5.7)未接入时的评估缺口。</li>
 * </ul>
 *
 * <p>evaluate 幂等:稳态用户 v_rank 不变 → return(不写流水/不发事件),安全批量扫描。
 * fixedDelay 6h + initialDelay 10min(避开启动峰值),每批 1000 条;单用户异常不中断整批。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VRankPromotionScheduler {

    private static final int BATCH_SIZE = 1000;

    private final TeamCommissionMapper teamCommissionMapper;
    private final VRankPromotionEngine vRankPromotionEngine;

    /** 兜底批量评估(可配置 nexion.vrank.backfill-delay-ms,默认 6h)。 */
    @Scheduled(fixedDelayString = "${nexion.vrank.backfill-delay-ms:21600000}",
               initialDelayString = "${nexion.vrank.backfill-initial-delay-ms:600000}")
    public void backfillEvaluate() {
        List<Long> userIds = teamCommissionMapper.listSelfLoopUserIds(BATCH_SIZE);
        if (userIds == null || userIds.isEmpty()) {
            log.debug("V-Rank backfill: no users to evaluate");
            return;
        }
        int evaluated = 0;
        int errors = 0;
        for (Long userId : userIds) {
            try {
                vRankPromotionEngine.evaluate(VRankPromotionContext.systemEvaluation(userId));
                evaluated++;
            } catch (RuntimeException ex) {
                errors++;
                log.warn("V-Rank backfill evaluate failed for user {}: {}", userId, ex.getMessage());
            }
        }
        log.info("V-Rank backfill done: scanned={}, evaluated={}, errors={}", userIds.size(), evaluated, errors);
    }
}
