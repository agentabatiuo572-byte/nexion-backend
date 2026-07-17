package ffdd.opsconsole.risk.dto;

import java.util.List;

public record RiskScoreBatchCommandRequest(
        List<String> userNos,
        Long expectedModelVersion,
        String reason,
        String operator
) {
}
