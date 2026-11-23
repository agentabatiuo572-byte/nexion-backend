package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.DeviceSlotReplacementMapper;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceSlotReplacementCompletion {
    private final DeviceSlotReplacementMapper replacements;
    private final CanonicalStateMapper devices;
    private final EventOutboxService outbox;

    /** The task or device command holds the account lock and has just released the old slot. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void afterDeactivation(long userId, long oldDeviceId) {
        Long newDeviceId = replacements.successor(userId, oldDeviceId);
        if (newDeviceId == null) return;
        var device = devices.lockDeviceForUserCommand(newDeviceId);
        // An intervening administrative revocation must not hold a finished task open.
        if (device == null || device.userId() != userId || !"OWNED".equals(device.ownershipStatus())) {
            replacements.unbind(userId, oldDeviceId);
            return;
        }
        int cap = devices.deviceSlotCap();
        if (devices.lockOccupiedDeviceSlotCount(userId, false) >= cap
                || devices.activateOwnedDeviceCas(userId, newDeviceId, device.rowVersion(), cap) != 1)
            throw new BizException(409, "DEVICE_REPLACEMENT_STATE_CHANGED");
        if ("DEACTIVATED".equals(device.status())) devices.resumeUserDeactivatedRuntime(userId, newDeviceId);
        var attribution = devices.userEventAttribution(userId);
        if (attribution == null) throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        outbox.publishUserEvent("USER_DEVICE", device.instanceNo(), "device.activated", userId,
                attribution.phase(), attribution.accountAgeMonths(), attribution.cohort(),
                Map.of("userId", userId, "deviceId", newDeviceId, "instanceNo", device.instanceNo(),
                        "previousStatus", device.status(), "status", "ACTIVE", "rowVersion", device.rowVersion() + 1));
        replacements.unbind(userId, oldDeviceId);
    }
}
