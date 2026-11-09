package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** E5 store projection; phone telemetry belongs to the E6 phone response. */
public record StoreDeviceView(
        Long id, Long userId, String userNo, String nickname, String instanceNo, String name,
        String productTier, String productCode, String status, String dcLocation, String hostingLabel,
        String sourceChannel, BigDecimal hashrate, BigDecimal dailyUsdt, BigDecimal dailyNex,
        LocalDateTime purchasedAt, LocalDateTime activatedAt, LocalDateTime deactivatedAt,
        String baseRate, BigDecimal currentEfficiency, Integer pendingDeactivate, String pausedReason,
        Long activeDevicesForUser, Long userDeviceSlotNo, int maxDevicesPerUser,
        String simState, String simLastError, LocalDateTime simNextActionAt,
        CurrentTask currentTask, DeviceOpsView.MonthlyTarget monthlyTarget) {
    public record CurrentTask(String taskNo, String name, String taskClass,
                              LocalDateTime startedAt, LocalDateTime dueAt) { }

    public static StoreDeviceView from(DeviceOpsView d, boolean simEnabled, boolean legacyPending) {
        var task = d.simTaskNo() == null ? null : new CurrentTask(d.simTaskNo(), d.simTaskName(),
                d.simTaskClass(), d.simTaskStartedAt(), d.simTaskDueAt());
        return new StoreDeviceView(d.id(), d.userId(), d.userNo(), d.nickname(), d.instanceNo(), d.name(),
                d.productTier(), d.productCode(), d.status(), d.dcLocation(), d.dcLocation(), d.sourceChannel(),
                d.hashrate(), d.dailyUsdt(), d.dailyNex(), d.purchasedAt(), d.activatedAt(), d.deactivatedAt(),
                d.baseRate(), d.currentEfficiency(), d.pendingDeactivate(), d.pausedReason(),
                d.activeDevicesForUser(), d.userDeviceSlotNo(), 6,
                simEnabled ? legacyPending ? "LEGACY_PENDING" : d.simState() : "SIM_OFF",
                legacyPending ? "SIMULATED_LEGACY_NOT_DRAINED" : d.simLastError(), d.simNextActionAt(), task, d.monthlyTarget());
    }
}
