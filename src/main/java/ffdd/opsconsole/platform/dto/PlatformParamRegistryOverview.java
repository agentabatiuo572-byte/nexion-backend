package ffdd.opsconsole.platform.dto;

import java.util.List;

public record PlatformParamRegistryOverview(
        List<PlatformParamRegistryRow> rows,
        PlatformParamRegistryStats stats,
        List<PlatformParamRegistrySourceState> sources,
        String observedAt) {
}
