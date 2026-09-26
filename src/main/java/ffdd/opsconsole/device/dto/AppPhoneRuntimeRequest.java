package ffdd.opsconsole.device.dto;

public record AppPhoneRuntimeRequest(String calibrationDeviceId, Integer batteryLevel,
                                     Boolean networkReachable, Boolean isCharging) {
}
