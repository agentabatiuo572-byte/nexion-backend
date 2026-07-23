package ffdd.opsconsole.device.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.device.domain.DeviceDatacenterView;
import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.domain.DeviceTradeinOverviewView;
import ffdd.opsconsole.device.domain.DeviceTradeinTxView;
import ffdd.opsconsole.device.dto.DeviceDatacenterUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import ffdd.opsconsole.device.mapper.DeviceOpsMapper;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisDeviceOpsRepository implements DeviceOpsRepository {
    private static final Set<String> E3_CONFIG_KEYS = Set.of(
            "stageEarlyEnd",
            "stageMidEnd",
            "cycleMonths",
            "taskLockS1",
            "taskLockPro",
            "taskLockRack",
            "eligibility",
            "promoMult",
            "promoCooldownDays",
            "promoMaxPerSession",
            "promoDelaySeconds",
            "promoMinAgeDays",
            "promoRoutes",
            "inventorySoftMax",
            "capacityBand1DeltaPct", "capacityBand2DeltaPct", "capacityBand3DeltaPct",
            "capacityFloorPct", "capacitySubsidyDays",
            "capacityApplyToPhone", "capacityApplyToCloudShare", "capacityApplyToPcGpu",
            "capacityApplyToS1", "capacityApplyToPro", "capacityApplyToProV2",
            "capacityApplyToRackP1", "capacityApplyToRackP2",
            "tradeinEnabled", "tradeinLadderCut1", "tradeinLadderCut2", "tradeinLadderCut3", "tradeinLadderCut4",
            "tradeinLadderCredit1", "tradeinLadderCredit2", "tradeinLadderCredit3", "tradeinLadderCredit4", "tradeinLadderCredit5",
            "tradeinRequireHigherPrice", "tradeinMaxDevicesPerOrder", "earlyAccessEnabled", "earlyAccessLeadDays");
    private static final Map<String, String> E3_RHYTHM_DEFAULTS = Map.ofEntries(
            Map.entry("capacityBand1DeltaPct", "-3"), Map.entry("capacityBand2DeltaPct", "-6"), Map.entry("capacityBand3DeltaPct", "-23.7"),
            Map.entry("stageEarlyEnd", "3"), Map.entry("stageMidEnd", "8"), Map.entry("cycleMonths", "12"),
            Map.entry("capacityFloorPct", "22"), Map.entry("capacitySubsidyDays", "30"),
            Map.entry("capacityApplyToPhone", "false"), Map.entry("capacityApplyToCloudShare", "false"), Map.entry("capacityApplyToPcGpu", "false"),
            Map.entry("capacityApplyToS1", "true"), Map.entry("capacityApplyToPro", "true"), Map.entry("capacityApplyToProV2", "true"),
            Map.entry("capacityApplyToRackP1", "true"), Map.entry("capacityApplyToRackP2", "true"),
            Map.entry("tradeinEnabled", "true"), Map.entry("tradeinLadderCut1", "25"), Map.entry("tradeinLadderCut2", "50"),
            Map.entry("tradeinLadderCut3", "75"), Map.entry("tradeinLadderCut4", "100"),
            Map.entry("tradeinLadderCredit1", "75"), Map.entry("tradeinLadderCredit2", "60"), Map.entry("tradeinLadderCredit3", "45"),
            Map.entry("tradeinLadderCredit4", "30"), Map.entry("tradeinLadderCredit5", "15"),
            Map.entry("tradeinRequireHigherPrice", "true"), Map.entry("tradeinMaxDevicesPerOrder", "3"),
            Map.entry("eligibility", "L4+ 持有者"), Map.entry("promoMult", "1.5"),
            Map.entry("promoCooldownDays", "14"), Map.entry("promoMaxPerSession", "3"),
            Map.entry("promoDelaySeconds", "6"), Map.entry("promoMinAgeDays", "30"),
            Map.entry("promoRoutes", "/me/devices"), Map.entry("inventorySoftMax", "0"),
            Map.entry("taskLockS1", "30"), Map.entry("taskLockPro", "150"), Map.entry("taskLockRack", "480"),
            Map.entry("earlyAccessEnabled", "false"), Map.entry("earlyAccessLeadDays", "30"));
    private final DeviceOpsMapper mapper;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    @Override
    public Map<String, Object> overviewCounters() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalDevices", mapper.countTotalDevices());
        overview.put("onlineDevices", mapper.countOnlineDevices());
        overview.put("offlineDevices", mapper.countOfflineDevices());
        overview.put("recycledDevices", mapper.countRecycledDevices());
        overview.put("pendingRecycleDevices", mapper.countPendingRecycleDevices());
        overview.put("abnormalDevices", mapper.countAbnormalDevices());
        overview.put("datacenters", datacenterSummaries());
        return overview;
    }

    @Override
    public PageResult<DeviceOpsView> pageDevices(DeviceOpsQueryRequest request) {
        String status = request == null ? null : normalizeUpper(request.status());
        String dcLocation = request == null ? null : normalize(request.dcLocation());
        String keyword = request == null ? null : normalize(request.keyword());
        Long userId = request == null || request.userId() == null || request.userId() <= 0 ? null : request.userId();
        String kind = request == null ? null : normalizeUpper(request.kind());
        String heartbeat = request == null ? null : normalize(request.heartbeat());
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        long offset = (pageNum - 1) * pageSize;
        long total = mapper.countDevices(status, dcLocation, keyword, userId, kind, heartbeat);
        List<DeviceOpsView> records = mapper.pageDevices(status, dcLocation, keyword, userId, kind, heartbeat, pageSize, offset);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public List<DeviceOpsView> listUserDevices(Long userId, int limit) {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        return mapper.listUserDevices(userId, normalizeSize((long) limit));
    }

    @Override
    public Optional<DeviceOpsView> findDevice(Long deviceId) {
        return Optional.ofNullable(mapper.findDevice(deviceId));
    }

    @Override
    public Optional<DeviceOpsView> restoreDevice(Long deviceId, LocalDateTime restoredAt) {
        mapper.restoreDevice(deviceId, restoredAt);
        mapper.restoreDeviceRuntime(deviceId, restoredAt);
        return findDevice(deviceId);
    }

    @Override
    public long countActiveDevicesByUser(Long userId) {
        return mapper.countActiveDevicesByUser(userId);
    }

    @Override
    public Optional<DeviceOpsView> activateDevice(Long deviceId, LocalDateTime activatedAt) {
        if (mapper.activateE5Device(deviceId, activatedAt) != 1) {
            return Optional.empty();
        }
        mapper.restoreDeviceRuntime(deviceId, activatedAt);
        return findDevice(deviceId);
    }

    @Override
    public Optional<DeviceOpsView> deactivateE5Device(Long deviceId, boolean unbind, LocalDateTime now) {
        if (mapper.deactivateE5Device(deviceId, unbind ? 1 : 0, now) != 1) {
            return Optional.empty();
        }
        mapper.markRuntimeOffline(deviceId, now, unbind ? "unbind" : "deactivate");
        return findDevice(deviceId);
    }

    @Override
    public List<Long> lockE5BatchCandidateIds(Long userId, boolean pause, int limit) {
        return mapper.lockE5BatchCandidateIds(userId, pause ? 1 : 0, limit);
    }

    @Override
    public int pauseDevicesByUser(Long userId, String reason, LocalDateTime now) {
        return mapper.pauseDevicesByUser(userId, reason, now);
    }

    @Override
    public int resumeDevicesByUser(Long userId, LocalDateTime now) {
        return mapper.resumeDevicesByUser(userId);
    }

    @Override
    public DeviceTradeinOverviewView e3TradeinOverview(LocalDateTime since, LocalDateTime monthStart, int cliffMonth) {
        DeviceOpsMapper.TradeinOverviewMetrics metrics = mapper.tradeinOverviewMetrics(cliffMonth);
        List<DeviceTradeinTxView> txStats = new ArrayList<>();
        txStats.add(txStat("recycle", "设备回收", "POST /api/admin/devices/e3/tradein/recycle", since));
        txStats.add(txStat("replace", "设备置换", "POST /api/admin/devices/e3/tradein/replace", since));
        txStats.add(txStat("deactivate", "设备停用", "POST /api/admin/devices/e3/tradein/deactivate", since));
        return new DeviceTradeinOverviewView(
                metrics.averageAgeMonths(),
                metrics.cliffDeviceCount(),
                mapper.countTradeinApplicationsSince(monthStart),
                safe(mapper.sumTradeinDiscountSince(monthStart)),
                mapper.countK2ArbitrageHits(),
                txStats);
    }

    @Override
    public Optional<DeviceOpsView> executeTradeinAction(String operation, Long deviceId, String tradeInNo, LocalDateTime now) {
        DeviceOpsView device = mapper.findDevice(deviceId);
        if (device == null || isFinalStatus(device.status())) {
            return Optional.empty();
        }
        int updated;
        if ("replace".equals(operation)) {
            mapper.insertTradeInOrderFromDevice(tradeInNo, deviceId);
            updated = mapper.recycleDevice(deviceId, now);
        } else if ("recycle".equals(operation)) {
            updated = mapper.recycleDevice(deviceId, now);
        } else if ("deactivate".equals(operation)) {
            updated = mapper.deactivateDevice(deviceId, now);
        } else {
            return Optional.empty();
        }
        if (updated == 0) {
            return Optional.empty();
        }
        mapper.markRuntimeOffline(deviceId, now, operation);
        return findDevice(deviceId);
    }

    @Override
    public Map<String, String> e3Config() {
        Map<String, String> config = new LinkedHashMap<>();
        mapper.e3ConfigRows().stream()
                .filter(row -> E3_CONFIG_KEYS.contains(row.configKey()))
                .forEach(row -> config.put(row.configKey(), row.configValue()));
        E3_RHYTHM_DEFAULTS.forEach(config::putIfAbsent);
        return config;
    }

    @Override
    public void upsertE3Config(String key, String value, String valueType, String operator) {
        int updated = mapper.updateE3Config(key, value, valueType, operator);
        if (updated == 0) {
            mapper.insertE3Config(key, value, valueType, operator, sortForConfig(key));
        }
    }

    @Override
    public List<DeviceDatacenterView> datacenterSummaries() {
        return mapper.datacenterSummaries().stream()
                .map(this::toDatacenterView)
                .toList();
    }

    @Override
    public Optional<DeviceDatacenterView> findDatacenter(String dcLocation) {
        return Optional.ofNullable(mapper.findDatacenter(dcLocation))
                .map(this::toDatacenterView);
    }

    @Override
    public DeviceDatacenterView createDatacenter(DeviceDatacenterUpsertRequest request, String operator, LocalDateTime now) {
        mapper.insertDatacenter(request.dcLocation(), request.regionLabel(), request.location(), request.displayName(),
                request.status(), request.sortOrder(), operator);
        return findDatacenter(request.dcLocation()).orElseThrow();
    }

    @Override
    public Optional<DeviceDatacenterView> updateDatacenter(String dcLocation, DeviceDatacenterUpsertRequest request, String operator, LocalDateTime now) {
        boolean renamed = !dcLocation.equals(request.dcLocation());
        int updated = renamed
                ? mapper.renameDatacenter(dcLocation, request.dcLocation(), request.regionLabel(), request.location(),
                        request.displayName(), request.status(), request.sortOrder(), operator)
                : mapper.updateDatacenter(dcLocation, request.regionLabel(), request.location(), request.displayName(),
                        request.status(), request.sortOrder(), operator);
        if (updated == 0) {
            return Optional.empty();
        }
        if (renamed) {
            mapper.renameDatacenterState(dcLocation, request.dcLocation());
            mapper.renameDatacenterDevices(dcLocation, request.dcLocation());
        }
        return findDatacenter(request.dcLocation());
    }

    @Override
    public boolean softDeleteDatacenter(String dcLocation, String operator, LocalDateTime now) {
        return mapper.softDeleteDatacenter(dcLocation, operator, now) > 0;
    }

    @Override
    public void pauseDatacenter(String dcLocation, String reason, String operator, LocalDateTime now) {
        upsertDatacenterState(dcLocation, true, reason, operator, now);
        mapper.pauseRuntimeByDatacenter(dcLocation, reason, now);
    }

    @Override
    public void resumeDatacenter(String dcLocation, String operator, LocalDateTime now) {
        upsertDatacenterState(dcLocation, false, null, operator, now);
        mapper.resumeRuntimeByDatacenter(dcLocation);
    }

    private void upsertDatacenterState(String dcLocation, boolean paused, String reason, String operator, LocalDateTime now) {
        int pausedValue = paused ? 1 : 0;
        int updated = mapper.updateDatacenterState(dcLocation, pausedValue, reason, operator, now);
        if (updated == 0) {
            mapper.insertDatacenterState(dcLocation, pausedValue, reason, paused ? now : null, paused ? null : now, operator);
        }
    }

    private DeviceDatacenterView toDatacenterView(DeviceOpsMapper.DatacenterSummaryRow row) {
        return new DeviceDatacenterView(
                row.dcLocation(),
                row.regionLabel(),
                row.status(),
                row.sortOrder(),
                safeLong(row.totalDevices()),
                safeLong(row.onlineDevices()),
                safeLong(row.pendingRecycleDevices()),
                safeLong(row.abnormalDevices()),
                safe(row.avgGpuUsage()),
                safe(row.avgGpuTempC()),
                safe(row.avgGpuPowerW()),
                row.dispatchPaused() != null && row.dispatchPaused() == 1,
                row.pausedReason(),
                row.pausedAt(),
                row.resumedAt(),
                row.createdAt(),
                row.updatedAt(),
                row.location(),
                row.displayName());
    }

    private String decayToPercent(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .multiply(BigDecimal.valueOf(-100))
                .stripTrailingZeros()
                .toPlainString();
    }

    private String rateToPercent(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros()
                .toPlainString();
    }

    private DeviceTradeinTxView txStat(String operation, String name, String endpoint, LocalDateTime since) {
        DeviceOpsMapper.TradeinTxMetrics metrics = mapper.tradeinTxMetrics(operation, since);
        DeviceOpsMapper.TradeinCandidateRow candidate = mapper.tradeinActionCandidate();
        String latestKind = metrics.failureCount() > 0 ? "最近失败" : "最新成功";
        String latestStatus = metrics.failureCount() > 0 ? "fail" : "ok";
        String reason = latestReason(operation, metrics, candidate);
        return new DeviceTradeinTxView(
                operation,
                name,
                endpoint,
                safeLong(metrics.successCount()),
                safeLong(metrics.failureCount()),
                safeLong(metrics.rollbackCount()),
                latestKind,
                latestStatus,
                metrics.latestAt(),
                reason,
                candidate == null ? null : candidate.deviceId(),
                candidate == null ? null : candidate.instanceNo(),
                candidate == null ? null : candidate.userId());
    }

    private String latestReason(String operation, DeviceOpsMapper.TradeinTxMetrics metrics, DeviceOpsMapper.TradeinCandidateRow candidate) {
        if (metrics.latestAt() == null && candidate == null) {
            return "暂无可执行候选设备";
        }
        if ("replace".equals(operation) && metrics.successCount() > 0) {
            return "trade-in order 已写入 · source device 已回收";
        }
        if ("recycle".equals(operation) && metrics.successCount() > 0) {
            return "设备状态已进入 RECYCLED · 可走撤销回收";
        }
        if ("deactivate".equals(operation) && metrics.successCount() > 0) {
            return "设备已停用 · generation lineage 由 server 归档";
        }
        if (candidate == null) {
            return "暂无可执行候选设备";
        }
        return candidate.instanceNo() + " · " + candidate.status() + " · 待操作确认";
    }

    private boolean isFinalStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        return List.of("RECYCLED", "DEACTIVATED", "INACTIVE", "RETIRED").contains(normalized);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private int sortForConfig(String key) {
        return switch (key) {
            case "capacityBand1DeltaPct" -> 10;
            case "capacityBand2DeltaPct" -> 20;
            case "capacityBand3DeltaPct" -> 30;
            case "stageEarlyEnd" -> 40;
            case "stageMidEnd" -> 50;
            case "cycleMonths" -> 60;
            case "capacityFloorPct" -> 70;
            case "capacitySubsidyDays" -> 75;
            case "taskLockS1" -> 80;
            case "taskLockPro" -> 90;
            case "taskLockRack" -> 100;
            case "eligibility" -> 120;
            case "promoMult" -> 140;
            case "promoCooldownDays" -> 150;
            case "promoMaxPerSession" -> 160;
            case "promoDelaySeconds" -> 170;
            case "promoMinAgeDays" -> 180;
            case "promoRoutes" -> 190;
            case "inventorySoftMax" -> 200;
            default -> 100;
        };
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeUpper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase() : null;
    }

    private long normalizePage(Long pageNum) {
        return Math.max(1L, pageNum == null ? 1L : pageNum);
    }

    private long normalizeSize(Long pageSize) {
        return Math.max(1L, Math.min(100L, pageSize == null ? 20L : pageSize));
    }
}
