package ffdd.opsconsole.risk.domain;

import java.time.LocalDateTime;

public record RiskCaseView(
        String caseNo,
        Long userId,
        String bizType,
        String bizNo,
        String region,
        String userLevel,
        String decision,
        String reason,
        Integer riskScore,
        String ruleCodes,
        String status,
        String reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt) {
}
