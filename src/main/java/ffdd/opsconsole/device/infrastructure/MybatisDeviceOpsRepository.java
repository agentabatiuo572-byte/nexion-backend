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
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisDeviceOpsRepository implements DeviceOpsRepository {
    private static final Set<String> E3_CONFIG_KEYS = Set.of(
            "degradeEarly",
            "degradeMid",
            "degradeLate",
            "stageEarlyEnd",
            "stageMidEnd",
            "cycleMonths",
            "minEfficiency",
            "taskLockS1",
            "taskLockPro",
            "taskLockRack",
            "salvagePct",
            "eligibility",
            "minHoldingMonths",
            "promoMult",
            "promoCooldownDays",
            "promoMaxPerSession",
            "promoDelaySeconds",
            "promoMinAgeDays",
            "promoRoutes",
            "inventorySoftMax");
    private final DeviceOpsMapper mapper;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final AtomicBoolean datacenterCatalogReady = new AtomicBoolean(false);

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
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        long offset = (pageNum - 1) * pageSize;
        long total = mapper.countDevices(status, dcLocation, keyword);
        List<DeviceOpsView> records = mapper.pageDevices(status, dcLocation, keyword, pageSize, offset);
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
                mapper.countK2ArbitrageHits(cliffMonth),
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
        Map<String, String> config = readTimeSeedPolicy.enabled() ? defaultE3Config() : new LinkedHashMap<>();
        mapper.defaultLifecycleRules().forEach(row -> {
            int startMonth = row.startMonth() == null ? 0 : row.startMonth();
            if (startMonth == 1) {
                config.put("degradeEarly", decayToPercent(row.monthlyDecayRate()));
                config.put("stageEarlyEnd", String.valueOf(row.endMonth()));
            } else if (startMonth == 4) {
                config.put("degradeMid", decayToPercent(row.monthlyDecayRate()));
                config.put("stageMidEnd", String.valueOf(row.endMonth()));
            } else if (startMonth >= 9) {
                config.put("degradeLate", decayToPercent(row.monthlyDecayRate()));
            }
            config.put("minEfficiency", rateToPercent(row.floorEfficiency()));
        });
        mapper.e3ConfigRows().stream()
                .filter(row -> E3_CONFIG_KEYS.contains(row.configKey()))
                .forEach(row -> config.put(row.configKey(), row.configValue()));
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
        ensureDatacenterCatalogReady();
        return mapper.datacenterSummaries().stream()
                .map(this::toDatacenterView)
                .toList();
    }

    @Override
    public Optional<DeviceDatacenterView> findDatacenter(String dcLocation) {
        ensureDatacenterCatalogReady();
        return Optional.ofNullable(mapper.findDatacenter(dcLocation))
                .map(this::toDatacenterView);
    }

    @Override
    public DeviceDatacenterView createDatacenter(DeviceDatacenterUpsertRequest request, String operator, LocalDateTime now) {
        ensureDatacenterCatalogReady();
        mapper.insertDatacenter(request.dcLocation(), request.regionLabel(), request.status(), request.sortOrder(), operator);
        return findDatacenter(request.dcLocation()).orElseThrow();
    }

    @Override
    public Optional<DeviceDatacenterView> updateDatacenter(String dcLocation, DeviceDatacenterUpsertRequest request, String operator, LocalDateTime now) {
        ensureDatacenterCatalogReady();
        int updated = mapper.updateDatacenter(dcLocation, request.regionLabel(), request.status(), request.sortOrder(), operator);
        if (updated == 0) {
            return Optional.empty();
        }
        return findDatacenter(dcLocation);
    }

    @Override
    public boolean softDeleteDatacenter(String dcLocation, String operator, LocalDateTime now) {
        ensureDatacenterCatalogReady();
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

    private void ensureDatacenterCatalogReady() {
        if (datacenterCatalogReady.get()) {
            return;
        }
        mapper.ensureDatacenterCatalogTable();
        if (readTimeSeedPolicy.enabled() && mapper.countDatacenterCatalogRows() == 0) {
            mapper.seedDefaultDatacenters();
        }
        datacenterCatalogReady.set(true);
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
                row.updatedAt());
    }

    private Map<String, String> defaultE3Config() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("degradeEarly", "-4");
        config.put("degradeMid", "-6");
        config.put("degradeLate", "-23.7");
        config.put("stageEarlyEnd", "3");
        config.put("stageMidEnd", "8");
        config.put("cycleMonths", "12");
        config.put("minEfficiency", "22");
        config.put("taskLockS1", "40");
        config.put("taskLockPro", "140");
        config.put("taskLockRack", "450");
        config.put("salvagePct", "30");
        config.put("eligibility", "L4+ 持有者");
        config.put("minHoldingMonths", "6");
        config.put("promoMult", "1.0");
        config.put("promoCooldownDays", "14");
        config.put("promoMaxPerSession", "1");
        config.put("promoDelaySeconds", "6");
        config.put("promoMinAgeDays", "30");
        config.put("promoRoutes", "/me/devices");
        config.put("inventorySoftMax", "0");
        return config;
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
            case "degradeEarly" -> 10;
            case "degradeMid" -> 20;
            case "degradeLate" -> 30;
            case "stageEarlyEnd" -> 40;
            case "stageMidEnd" -> 50;
            case "cycleMonths" -> 60;
            case "minEfficiency" -> 70;
            case "taskLockS1" -> 80;
            case "taskLockPro" -> 90;
            case "taskLockRack" -> 100;
            case "salvagePct" -> 110;
            case "eligibility" -> 120;
            case "minHoldingMonths" -> 130;
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
