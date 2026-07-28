package ffdd.opsconsole.team.dto;

import java.util.List;

public record F5CommissionSuspensionRequest(
        List<String> kinds,
        Boolean suspended,
        String reason,
        String operator) {
}
