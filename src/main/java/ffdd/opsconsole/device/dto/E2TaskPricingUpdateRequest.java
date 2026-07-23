package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

/**
 * Single-action update for the E2 server-canonical task pricing surface.
 * Exactly one of queueSaturation or a taskClass patch is submitted by the UI.
 */
public record E2TaskPricingUpdateRequest(
        String taskClass,
        BigDecimal minReward,
        BigDecimal maxReward,
        Integer minVram,
        Boolean enabled,
        BigDecimal queueSaturation,
        String reason,
        String operator) {
}
