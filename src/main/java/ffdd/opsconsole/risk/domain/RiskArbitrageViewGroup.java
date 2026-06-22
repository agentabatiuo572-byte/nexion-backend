package ffdd.opsconsole.risk.domain;

import java.util.List;

public record RiskArbitrageViewGroup(
        String key,
        String label,
        String sub,
        List<String> head,
        String note,
        List<RiskArbitrageRowView> rows
) {
}
