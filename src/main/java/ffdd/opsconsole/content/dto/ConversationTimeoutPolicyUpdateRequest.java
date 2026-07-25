package ffdd.opsconsole.content.dto;

import java.math.BigDecimal;

public record ConversationTimeoutPolicyUpdateRequest(
        BigDecimal warnMinutes,
        BigDecimal closeMinutes,
        Long expectedVersion,
        String operator,
        String reason) {

    public ConversationTimeoutPolicyUpdateRequest(
            Integer warnMinutes,
            Integer closeMinutes,
            Long expectedVersion,
            String operator,
            String reason) {
        this(decimal(warnMinutes), decimal(closeMinutes), expectedVersion, operator, reason);
    }

    public boolean hasIntegralMinutes() {
        return integral(warnMinutes) && integral(closeMinutes);
    }

    public int warnMinutesExact() {
        return warnMinutes.intValueExact();
    }

    public int closeMinutesExact() {
        return closeMinutes.intValueExact();
    }

    private static BigDecimal decimal(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static boolean integral(BigDecimal value) {
        if (value == null || value.stripTrailingZeros().scale() > 0) {
            return false;
        }
        try {
            value.intValueExact();
            return true;
        } catch (ArithmeticException outOfIntegerRange) {
            return false;
        }
    }
}
