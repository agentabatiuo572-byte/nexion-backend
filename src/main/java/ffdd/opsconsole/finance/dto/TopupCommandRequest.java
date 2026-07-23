package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;

public record TopupCommandRequest(
        String value,
        Boolean enabled,
        BigDecimal numericValue,
        String unit,
        String method,
        String evidenceRef,
        Boolean evidenceConfirmed,
        String expectedValue,
        String reason,
        String operator) {

    /** Compatibility constructor for non-numeric legacy commands (enable/PSP/BIN). */
    public TopupCommandRequest(String value, Boolean enabled, String reason, String operator) {
        this(value, enabled, null, null, null, null, null, null, reason, operator);
    }

    /** Compatibility constructor for evidence-based commands that do not mutate versioned configuration. */
    public TopupCommandRequest(
            String value,
            Boolean enabled,
            BigDecimal numericValue,
            String unit,
            String method,
            String evidenceRef,
            Boolean evidenceConfirmed,
            String reason,
            String operator) {
        this(value, enabled, numericValue, unit, method, evidenceRef, evidenceConfirmed, null, reason, operator);
    }
}
