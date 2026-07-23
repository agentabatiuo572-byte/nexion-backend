package ffdd.opsconsole.team.domain;

import java.util.Optional;

/**
 * F1 V-Rank 晋升评估上下文(引擎入参 VO)。
 *
 * <p>封装触发类型/来源事件 ID/操作人,用于审计与幂等。
 */
public record VRankPromotionContext(
        Long userId,
        TriggerType triggerType,
        String sourceEventId,
        String operator) {

    public VRankPromotionContext {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (triggerType == null) {
            triggerType = TriggerType.SYSTEM_EVALUATION;
        }
    }

    /** 便捷工厂:系统定时评估触发。 */
    public static VRankPromotionContext systemEvaluation(Long userId) {
        return new VRankPromotionContext(userId, TriggerType.SYSTEM_EVALUATION, null, "ENGINE");
    }

    public enum TriggerType {
        /** 订单支付完成触发的评估 */
        ORDER_PAID,
        /** 系统定时任务触发的评估 */
        SYSTEM_EVALUATION,
        /** 运营手动触发 */
        MANUAL_OPERATION,
        /** 其他事件源(Sprint3+ 扩展点) */
        OTHER;

        public String code() {
            return name();
        }
    }

    public Optional<String> findSourceEventId() {
        return Optional.ofNullable(sourceEventId);
    }
}
