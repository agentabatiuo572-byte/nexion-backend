package ffdd.opsconsole.device.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.domain.DeviceTradeinOverviewView;
import ffdd.opsconsole.device.domain.DeviceTradeinTxView;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import ffdd.opsconsole.device.mapper.DeviceOpsMapper;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
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
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
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
    private final CanonicalStateMapper canonicalStateMapper;
    private final ffdd.opsconsole.device.application.ComputeV2DeviceLifecycleService computeV2Lifecycle;

    public MybatisDeviceOpsRepository(DeviceOpsMapper mapper,OpsReadTimeSeedPolicy readTimeSeedPolicy,CanonicalStateMapper canonicalStateMapper) {
        this(mapper,readTimeSeedPolicy,canonicalStateMapper,null);
    }

    @org.springframework.beans.factory.annotation.Value("${nexion.simulated-tasks.enabled:false}")
    private boolean simEnabled;

    @Override
    public Map<String, Object> overviewCounters() {
        // 原四数只读旧口径 legacyState,与改动前完全一致:总数=在售范围全部;运行中=RUNNING;已停用=STOPPED;
        // 等待启动=WAITING_ACTIVATION/WAITING_INIT/LEGACY_PENDING(显式三态)。stale ERROR 等一律按旧运行态归属。
        // 新增三数只读 resolvedState(与列表状态筛选同一有效状态解析):出错重试=ERROR、旧任务未结清=LEGACY_PENDING、
        // 等待配置=WAITING_CONFIG;它们仍计入总数,不重复也不改动原四数。simEnabled=false 时 resolvedState 全为 SIM_OFF,新三数为 0。
        long total = 0, running = 0, waiting = 0, stopped = 0, errorRetry = 0, legacyPending = 0, waitingConfig = 0;
        for (var row : mapper.storeStateCounts(simEnabled)) {
            long count = row.deviceCount();
            total += count;
            switch (row.legacyState()) {
                case "RUNNING" -> running += count;
                case "WAITING_ACTIVATION", "WAITING_INIT", "LEGACY_PENDING" -> waiting += count;
                case "STOPPED" -> stopped += count;
                default -> { }
            }
            switch (row.resolvedState()) {
                case "ERROR" -> errorRetry += count;
                case "LEGACY_PENDING" -> legacyPending += count;
                case "WAITING_CONFIG" -> waitingConfig += count;
                default -> { }
            }
        }
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("total", total);
        counters.put("running", running);
        counters.put("waitingActivation", waiting);
        counters.put("stopped", stopped);
        counters.put("errorRetry", errorRetry);
        counters.put("legacyPending", legacyPending);
        counters.put("waitingConfig", waitingConfig);
        return counters;
    }

    @Override
    public Map<String, Object> e5Observability() {
        List<Map<String, Object>> activity = mapper.e5Activity24h().stream().map(event -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventType", event.eventType());
            item.put("aggregateType", event.aggregateType());
            item.put("aggregateId", event.aggregateId());
            item.put("occurredAt", event.createdAt());
            return item;
        }).toList();
        return Map.of("activity", activity);
    }

    @Override
    public PageResult<DeviceOpsView> pageDevices(DeviceOpsQueryRequest request, boolean phones) {
        String state = normalizeUpper(request.simState());
        if (state != null && !Set.of("WAITING_ACTIVATION", "WAITING_INIT", "LEGACY_PENDING", "RUNNING", "WAITING_CONFIG", "PAUSED", "CLOSING",
                "STOPPED", "UNBOUND", "ERROR", "SIM_OFF").contains(state))
            throw new BizException(422, "E5_SIM_STATE_INVALID");
        String source = normalizeUpper(request.source());
        if (source != null && !Set.of("ORDER", "TRADE_IN", "TRIAL", "PROMOTION_GIFT").contains(source))
            throw new BizException(422, "E5_SOURCE_INVALID");
        String productCode = normalize(request.productCode());
        String keyword = normalize(request.keyword());
        Long userId = request.userId();
        if (userId != null && userId <= 0)
            throw new BizException(422, "E5_USER_ID_INVALID");
        long pageNum = normalizePage(request.pageNum());
        long pageSize = normalizeSize(request.pageSize());
        long offset;
        try {
            offset = Math.multiplyExact(pageNum - 1, pageSize);
        } catch (ArithmeticException invalidOffset) {
            throw new BizException(422, "E5_PAGE_NUM_INVALID");
        }
        long total = mapper.countDevices(phones, simEnabled, state, source, productCode, keyword, userId);
        var records = mapper.pageDevices(phones, simEnabled, state, source, productCode, keyword, userId, pageSize, offset);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Set<Long> legacyPendingDeviceIds(List<Long> deviceIds) {
        return deviceIds.isEmpty() ? Set.of() : mapper.legacyPendingDeviceIds(deviceIds);
    }

    @Override
    public PageResult<DeviceOpsView> pageUserDevices(Long userId, long page, long size) {
        long pageNum = normalizePage(page), pageSize = normalizeSize(size);
        return new PageResult<>(mapper.countUserDevices(userId), pageNum, pageSize,
                mapper.listUserDevices(userId, pageSize, Math.multiplyExact(pageNum - 1, pageSize)));
    }

    @Override
    public List<DeviceOpsView> listUserDevices(Long userId, int limit) {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        return mapper.listUserDevices(userId, normalizeSize((long) limit), 0);
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
    public long countOccupiedSlotsForE5Activation(Long userId) {
        Boolean sandbox = mapper.lockUserSandboxForE5Activation(userId);
        if (sandbox == null) throw new BizException(409, "DEVICE_ACTIVATION_LOCK_UNAVAILABLE");
        return canonicalStateMapper.lockOccupiedDeviceSlotCount(userId, sandbox);
    }

    @Override
    public boolean lockUserForE5Activation(Long userId) {
        return mapper.lockUserForE5Activation(userId) != null;
    }

    @Override
    public Optional<DeviceOpsView> activateDevice(Long deviceId, LocalDateTime activatedAt) {
        if (mapper.activateE5Device(deviceId, activatedAt, simEnabled) != 1) {
            return Optional.empty();
        }
        mapper.restoreDeviceRuntime(deviceId, activatedAt);
        return findDevice(deviceId);
    }

    @Override
    public Optional<DeviceOpsView> deactivateE5Device(Long deviceId, boolean unbind, LocalDateTime now) {
        if(computeV2Lifecycle!=null && computeV2Lifecycle.manages(deviceId)){
            var original=findDevice(deviceId).orElse(null);
            if(original!=null){
                if(unbind)computeV2Lifecycle.requireDrainedForReplacement(original.userId(),deviceId);
                else if(computeV2Lifecycle.deferDeactivation(original.userId(),deviceId,null))return findDevice(deviceId);
            }
        }
        if (mapper.deactivateE5Device(deviceId, unbind ? 1 : 0, now) != 1) {
            return Optional.empty();
        }
        DeviceOpsView updated = findDevice(deviceId).orElse(null);
        if (updated == null) return Optional.empty();
        if (!Integer.valueOf(1).equals(updated.pendingDeactivate())) {
            mapper.markRuntimeOffline(deviceId, now, unbind ? "unbind" : "deactivate");
            return findDevice(deviceId);
        }
        return Optional.of(updated);
    }

    @Override
    public List<Long> lockE5BatchCandidateIds(Long userId, boolean pause, int limit) {
        return mapper.lockE5BatchCandidateIds(userId, pause ? 1 : 0, limit, simEnabled);
    }

    @Override
    public int pauseDevicesByUser(Long userId, String reason, LocalDateTime now) {
        return mapper.pauseDevicesByUser(userId, reason, now, simEnabled);
    }

    @Override
    public int resumeDevicesByUser(Long userId, List<Long> deviceIds, LocalDateTime now) {
        return mapper.resumeDevicesByUser(userId, deviceIds);
    }

    @Override
    public void syncSimulatedPause(Long userId, List<Long> deviceIds, boolean pause, LocalDateTime now) {
        if (simEnabled) mapper.syncSimulatedPause(userId, deviceIds, pause, now);
    }

    @Override
    public DeviceTradeinOverviewView e3TradeinOverview(LocalDateTime since, LocalDateTime monthStart, int earlyEnd, int midEnd) {
        DeviceOpsMapper.TradeinOverviewMetrics metrics = mapper.tradeinOverviewMetrics(earlyEnd, midEnd);
        List<DeviceTradeinTxView> txStats = new ArrayList<>();
        txStats.add(txStat("recycle", "设备回收", "POST /api/admin/devices/e3/tradein/recycle", since));
        txStats.add(txStat("replace", "设备置换", "POST /api/admin/devices/e3/tradein/replace", since));
        txStats.add(txStat("deactivate", "设备停用", "POST /api/admin/devices/e3/tradein/deactivate", since));
        return new DeviceTradeinOverviewView(
                metrics.averageAgeMonths(),
                metrics.cliffDeviceCount(),
                metrics.earlyDeviceCount(),
                metrics.midDeviceCount(),
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
        if(computeV2Lifecycle!=null){
            if("deactivate".equals(operation)){
                if(computeV2Lifecycle.deferDeactivation(device.userId(),deviceId,null))return findDevice(deviceId);
            }else computeV2Lifecycle.requireDrainedForReplacement(device.userId(),deviceId);
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
