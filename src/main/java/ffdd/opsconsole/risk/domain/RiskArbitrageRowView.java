package ffdd.opsconsole.risk.domain;

import java.util.List;

public record RiskArbitrageRowView(
        String rowId,
        String viewKey,
        String clusterId,
        List<String> cells,
        Integer level,
        List<String> actions,
        String disposition,
        Long version,
        String clusterStatus,
        Long clusterVersion
) {
    public RiskArbitrageRowView(
            String rowId, String viewKey, String clusterId, List<String> cells,
            Integer level, List<String> actions, String disposition) {
        this(rowId, viewKey, clusterId, cells, level, actions, disposition, 0L, null, null);
    }
}
