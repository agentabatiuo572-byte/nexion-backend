package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.common.boundary.PhoneActivationCapacityContract;
import ffdd.opsconsole.device.application.ComputeV2DeviceLifecycleService;
import ffdd.opsconsole.device.application.DeviceSlotReplacementCompletion;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PhoneActivationCapacityService implements PhoneActivationCapacityContract {
    private static final Set<String> SLOT_OCCUPYING_STATUSES = Set.of("ACTIVE", "ONLINE", "BUSY", "RUNNING", "OFFLINE");
    private final CanonicalStateMapper mapper;
    @Autowired
    private DeviceSlotReplacementCompletion slotReplacement;
    @Autowired
    private ComputeV2DeviceLifecycleService computeLifecycle;
    @Autowired
    private AuditLogService audit;
    @Autowired
    private EventOutboxService outbox;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BizException.class)
    public void requireCapacity(Long userId, Long currentBoundPhoneDeviceId) {
        var user = mapper.lockUser(userId);
        if (user == null) throw new BizException(403, "USER_AUTH_REQUIRED");
        // Reusing the same occupied phone does not add a device, including on an old over-cap account.
        if (currentBoundPhoneDeviceId != null
                && mapper.lockOccupiedOnboardingPhone(userId, currentBoundPhoneDeviceId) != null) return;
        int active = mapper.lockActiveDeviceCount(userId, user.sandbox());
        int reserved = mapper.lockReservedDeviceOrderCount(userId, user.sandbox());
        int trial = mapper.lockReservedTrialSlotCount(userId);
        int cap = Math.max(1, mapper.lockDeviceSlotCap());
        if ((long) Math.max(0, active) + Math.max(0, reserved) + trial >= cap) {
            throw new BizException(409, "CAPACITY_REPLACEMENT_REQUIRED");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseBoundPhoneSlot(long userId, long deviceId, boolean scheduledReconciliation) {
        var device = mapper.lockDeviceForUserCommand(deviceId);
        if (device == null || device.userId() != userId || device.activatedAt() == null || device.deactivatedAt() != null
                || !"OWNED".equalsIgnoreCase(device.ownershipStatus())
                || !SLOT_OCCUPYING_STATUSES.contains(device.status().toUpperCase(Locale.ROOT))
                || !("PHONE".equalsIgnoreCase(device.deviceType()) || "MOBILE".equalsIgnoreCase(device.deviceType()))) return;
        computeLifecycle.stopForH5Login(userId, deviceId, device.instanceNo());
        if (mapper.deactivateOwnedDeviceCas(userId, deviceId, device.rowVersion()) != 1)
            throw new BizException(409, "DEVICE_VERSION_CONFLICT");
        slotReplacement.afterDeactivation(userId, deviceId);
        var attribution = mapper.userEventAttribution(userId);
        if (attribution == null) throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        var fact = Map.of("userId", userId, "deviceId", deviceId, "instanceNo", device.instanceNo(),
                "previousStatus", device.status(), "status", "DEACTIVATED", "rowVersion", device.rowVersion() + 1);
        outbox.publishUserEvent("USER_DEVICE", device.instanceNo(), "device.deactivated", userId,
                attribution.phase(), attribution.accountAgeMonths(), attribution.cohort(), fact);
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action("USER_DEVICE_DEACTIVATED").resourceType("USER_DEVICE").resourceId(Long.toString(deviceId))
                .bizNo(device.instanceNo()).userId(userId)
                .actorId(scheduledReconciliation ? 0L : userId)
                .actorType(scheduledReconciliation ? "SYSTEM" : "USER")
                .actorUsername(scheduledReconciliation ? "system" : "user:" + userId)
                .method(scheduledReconciliation ? "SCHEDULED" : "INTERNAL").path("/internal/auth/h5-phone-release")
                .result("SUCCESS").riskLevel("MEDIUM")
                .detail(Map.of("reason", scheduledReconciliation ? "LEGACY_DUAL_SESSION_RECONCILED" : "H5_LOGIN", "state", fact))
                .build());
    }
}
