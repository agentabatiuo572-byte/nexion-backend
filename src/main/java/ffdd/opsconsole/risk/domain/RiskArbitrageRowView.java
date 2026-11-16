package ffdd.opsconsole.risk.domain;

import java.util.List;
import java.util.Map;

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
        Long clusterVersion,
        Map<String, Object> structured
) {
    public RiskArbitrageRowView(
            String rowId, String viewKey, String clusterId, List<String> cells,
            Integer level, List<String> actions, String disposition,
            Long version, String clusterStatus, Long clusterVersion) {
        this(rowId, viewKey, clusterId, cells, level, actions, disposition,
                version, clusterStatus, clusterVersion, Map.of());
    }

    public RiskArbitrageRowView(
            String rowId, String viewKey, String clusterId, List<String> cells,
            Integer level, List<String> actions, String disposition) {
        this(rowId, viewKey, clusterId, cells, level, actions, disposition, 0L, null, null, Map.of());
    }
}
