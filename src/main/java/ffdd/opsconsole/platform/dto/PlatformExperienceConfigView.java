package ffdd.opsconsole.platform.dto;

import ffdd.opsconsole.device.domain.PlatformComputeConfigView;
import java.util.List;

public record PlatformExperienceConfigView(
        long version,
        PlatformComputeConfigView.ShareConfig share,
        boolean ready,
        List<String> sources,
        String updatedAt) {
}
