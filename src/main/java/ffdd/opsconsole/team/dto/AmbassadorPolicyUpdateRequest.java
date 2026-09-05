package ffdd.opsconsole.team.dto;

import java.math.BigDecimal;
import java.util.List;

public record AmbassadorPolicyUpdateRequest(
        String policyVersion,
        BigDecimal defaultBudgetUsdt,
        List<Bucket> buckets,
        Long expectedRevision,
        String reason,
        String operator) {

    public record Bucket(
            String id,
            String title,
            String range,
            String rule,
            BigDecimal minBudgetUsdt,
            BigDecimal maxBudgetUsdt) { }
}
