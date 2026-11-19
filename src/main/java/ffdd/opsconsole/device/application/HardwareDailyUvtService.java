package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.shared.capacity.E3DeviceCapacityPolicy;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Paid hardware uses its purchase snapshot and the E3 policy effective at completion. */
@Service
public class HardwareDailyUvtService {
    private static final Set<String> HARDWARE_CAPACITY_KEYS = Set.of(
            "capacityApplyToS1", "capacityApplyToPro", "capacityApplyToProV2",
            "capacityApplyToRackP1", "capacityApplyToRackP2");

    private final AppTaskAssignmentMapper assignments;
    private final SimulatedTaskPolicyService policies;
    private final LocalDateTime startsAt;

    public HardwareDailyUvtService(AppTaskAssignmentMapper assignments, SimulatedTaskPolicyService policies,
            @Value("${nexion.hardware-daily-uvt.start-utc:}") String startUtc) {
        this.assignments = assignments;
        this.policies = policies;
        this.startsAt = startUtc.isBlank() ? null : LocalDate.parse(startUtc.trim()).atStartOfDay(ZoneOffset.UTC)
                .withZoneSameInstant(DateTimeFormatConfig.BUSINESS_ZONE).toLocalDateTime();
    }

    BigDecimal amount(long userId, long deviceId, String productCode, String seriesCode, LocalDateTime completedAt) {
        return amount(userId, deviceId, productCode, seriesCode, completedAt, true);
    }

    BigDecimal previewAmount(long userId, long deviceId, String productCode, String seriesCode, LocalDateTime completedAt) {
        return amount(userId, deviceId, productCode, seriesCode, completedAt, false);
    }

    private BigDecimal amount(long userId, long deviceId, String productCode, String seriesCode, LocalDateTime completedAt,
            boolean lock) {
        if (startsAt == null || completedAt.isBefore(startsAt) || !appliesTo(seriesCode)) return BigDecimal.ZERO;
        var device = lock ? assignments.lockPaidHardwareDailyUvt(userId, deviceId, productCode, completedAt)
                : assignments.paidHardwareDailyUvt(userId, deviceId, productCode, completedAt);
        if (device == null) return BigDecimal.ZERO;
        var capacity = E3DeviceCapacityPolicy.project(device.seriesCode(), null, device.purchasedAt(), device.activatedAt(),
                completedAt, policies.capacityConfigAt(completedAt));
        return E3DeviceCapacityPolicy.applyCapacity(device.dailyNex(), capacity);
    }

    boolean appliesTo(String seriesCode) {
        String capacityKey = E3DeviceCapacityPolicy.classify(seriesCode, null);
        return capacityKey != null && HARDWARE_CAPACITY_KEYS.contains(capacityKey);
    }
}
