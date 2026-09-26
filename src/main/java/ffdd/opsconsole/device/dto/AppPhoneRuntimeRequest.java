package ffdd.opsconsole.device.dto;

public record AppPhoneRuntimeRequest(Long deviceId, Integer batteryLevel,
                                     Boolean networkReachable, Boolean isCharging) {
}
