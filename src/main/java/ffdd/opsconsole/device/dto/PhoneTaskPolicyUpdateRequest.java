package ffdd.opsconsole.device.dto;

import java.util.List;

public record PhoneTaskPolicyUpdateRequest(List<String> allowedTaskTypeIds,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=TaskSimulationSettings.StrictVersion.class) Long expectedVersion,
        String reason,String operator,List<String> allowedTaskClasses) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectMonthlyTarget(String key,Object value) {
        if(java.util.Set.of("monthlyTargetUsdt","monthlyTargetBandPct","clearMonthlyTarget").contains(key))
            throw new ffdd.opsconsole.shared.exception.BizException(422,"MONTHLY_TARGET_DEVICE_INELIGIBLE");
    }
}
