package ffdd.opsconsole.risk.domain;

import java.util.List;

public record RiskArbitrageRowView(
        String rowId,
        String viewKey,
        String clusterId,
        List<String> cells,
        Integer level,
        List<String> actions,
        String disposition
) {
}
