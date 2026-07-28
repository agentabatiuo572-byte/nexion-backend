package ffdd.opsconsole.team.dto;

import java.util.List;

public record F5CommissionReissueRequest(
        List<String> commissionIds,
        String reason,
        String operator) {
}
