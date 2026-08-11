package ffdd.opsconsole.device.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AppTaskDeviceState(
        Long deviceId,
        String instanceNo,
        String deviceType,
        LocalDateTime lockUntil,
        AppTaskAssignmentView currentTask,
        List<AppTaskAssignmentView> recentTasks) {
}
