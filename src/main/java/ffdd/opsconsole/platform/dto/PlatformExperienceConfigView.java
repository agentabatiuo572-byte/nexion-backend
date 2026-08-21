package ffdd.opsconsole.platform.dto;

import ffdd.opsconsole.device.domain.PlatformComputeConfigView;
import java.util.List;

public record PlatformExperienceConfigView(
        long version,
        PlatformComputeConfigView.ShareConfig share,
        boolean ready,
        boolean homeNewcomerTasksEnabled,
        boolean homeWeeklyPromoEnabled,
        List<String> sources,
        String updatedAt) {
}
