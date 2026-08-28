package ffdd.opsconsole.home.application;

import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper.DevelopmentActiveTask;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper.DevelopmentE3CapacityConfig;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper.DevelopmentRunningTask;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper.DevelopmentTaskConfig;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper.DevelopmentTaskDevice;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper.DevelopmentTaskSettlement;
import ffdd.opsconsole.shared.capacity.E3DeviceCapacityPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Development-only server task worker for the formal App.
 *
 * <p>Every eligible device owns one independent running task. A reward is frozen from the E2
 * task configuration when the task is assigned, but no receipt, wallet credit, ledger row, or
 * earning event is written until the configured task duration has elapsed. This deliberately
 * replaces the retired midnight/up-front daily settlement.</p>
 */
@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "nexion.home.development-settlement.enabled", havingValue = "true")
@Slf4j
public class DevelopmentHomeSettlementBootstrap implements ApplicationRunner {
    private static final String FIXED_COUNTRY_CODE = "+86";
    private static final String FIXED_PHONE = "18708173775";
    private static final String CLIENT_NAME = "NexGrid Development Workload";
    private static final String TASK_PREFIX = "DEV-TASK-";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> TASK_CLASSES = Set.of("IG", "VG", "LL", "FT", "EM", "SP");

    private final DevelopmentHomeSettlementMapper mapper;
    private final Clock clock;
    private final DeviceTransaction deviceTransaction;
    @SuppressWarnings("ArchitectureConfigField")
    private final String countryCode;
    @SuppressWarnings("ArchitectureConfigField")
    private final String phone;
    @SuppressWarnings("ArchitectureConfigField")
    private final boolean enabled;

    @Autowired
    public DevelopmentHomeSettlementBootstrap(
            DevelopmentHomeSettlementMapper mapper,
            Clock clock,
            @Value("${nexion.auth.development-passkey-account.country-code:}") String countryCode,
            @Value("${nexion.auth.development-passkey-account.phone:}") String phone,
            @Value("${nexion.home.development-settlement.enabled:false}") boolean enabled,
            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.clock = clock;
        this.countryCode = countryCode == null ? "" : countryCode.trim();
        this.phone = phone == null ? "" : phone.trim();
        this.enabled = enabled;
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.deviceTransaction = work -> {
            Integer result = transaction.execute(status -> work.getAsInt());
            return result == null ? 0 : result;
        };
    }

    DevelopmentHomeSettlementBootstrap(
            DevelopmentHomeSettlementMapper mapper,
            Clock clock,
            String countryCode,
            String phone,
            boolean enabled) {
        this.mapper = mapper;
        this.clock = clock;
        this.countryCode = countryCode == null ? "" : countryCode.trim();
        this.phone = phone == null ? "" : phone.trim();
        this.enabled = enabled;
        this.deviceTransaction = IntSupplier::getAsInt;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureDevelopmentPhone();
        advanceTasks();
    }

    @Scheduled(fixedDelayString = "${nexion.home.development-settlement.refresh-ms:1000}",
            initialDelayString = "${nexion.home.development-settlement.initial-delay-ms:1000}")
    public synchronized int advanceTasks() {
        if (!enabled) return 0;
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE).withNano(0);
        Map<String, String> capacityConfig = developmentCapacityConfig();
        if (!E3DeviceCapacityPolicy.validConfig(capacityConfig)) {
            log.warn("event=DEVELOPMENT_TASK_PROGRESS_SKIPPED reason=E3_CAPACITY_CONFIG_INVALID");
            return 0;
        }
        List<DevelopmentTaskConfig> pool = validatedTaskPool(mapper.developmentTaskPool());
        if (pool.isEmpty()) {
            log.warn("event=DEVELOPMENT_TASK_PROGRESS_SKIPPED reason=E2_TASK_POOL_UNAVAILABLE");
            return 0;
        }
        List<DevelopmentTaskDevice> candidates = mapper.developmentTaskDevices();
        if (candidates == null || candidates.isEmpty()) return 0;

        int progressed = 0;
        for (DevelopmentTaskDevice candidate : candidates) {
            if (candidate == null || candidate.userId() == null || candidate.userId() <= 0
                    || candidate.userDeviceId() == null || candidate.userDeviceId() <= 0) {
                log.warn("event=DEVELOPMENT_TASK_DEVICE_SKIPPED reason=DEVICE_FACT_INVALID");
                continue;
            }
            try {
                progressed += deviceTransaction.execute(() -> advanceDevice(candidate, now));
            } catch (RuntimeException failure) {
                log.error("event=DEVELOPMENT_TASK_DEVICE_FAILED deviceId={} reason={}",
                        candidate.userDeviceId(), failure.getMessage());
            }
        }
        if (progressed > 0) {
            log.info("event=DEVELOPMENT_TASK_PROGRESS_READY progressed={}", progressed);
        }
        return progressed;
    }

    private int advanceDevice(DevelopmentTaskDevice candidate, LocalDateTime now) {
        // Serialize the empty-active-task decision on the canonical device row. Each device is
        // advanced in its own REQUIRES_NEW transaction so a settlement failure rolls back only
        // that device and cannot stall the rest of the fleet.
        DevelopmentTaskDevice device = mapper.lockDevelopmentTaskDevice(
                candidate.userId(), candidate.userDeviceId());
        if (device == null) return 0;
        Map<String, String> capacityConfig = developmentCapacityConfig();
        if (!E3DeviceCapacityPolicy.validConfig(capacityConfig)) {
            log.warn("event=DEVELOPMENT_TASK_DEVICE_SKIPPED deviceId={} reason=E3_CAPACITY_CONFIG_INVALID",
                    device.userDeviceId());
            return 0;
        }
        List<DevelopmentTaskConfig> pool = validatedTaskPool(mapper.developmentTaskPool());
        if (pool.isEmpty()) {
            log.warn("event=DEVELOPMENT_TASK_DEVICE_SKIPPED deviceId={} reason=E2_TASK_POOL_UNAVAILABLE",
                    device.userDeviceId());
            return 0;
        }
        List<PlannedDevice> lockedPlans = planDevices(List.of(device), pool, capacityConfig, now);
        if (lockedPlans.isEmpty()) return 0;
        PlannedDevice plan = lockedPlans.get(0);
        int progressed = 0;
        DevelopmentActiveTask active = mapper.lockDevelopmentActiveTask(
                device.userId(), device.userDeviceId());
        if (active != null) {
            if (!taskFinished(active, now) || !settleCompletedTask(active, now)) return 0;
            progressed++;
        }
        long completedCount = mapper.developmentCompletedTaskCount(
                device.userId(), device.userDeviceId());
        DevelopmentTaskConfig next = plan.eligibleTasks().get(
                Math.floorMod(completedCount, plan.eligibleTasks().size()));
        BigDecimal reward = taskReward(next, plan.capacity());
        DevelopmentRunningTask row = runningTask(device, next, reward, now);
        if (mapper.insertDevelopmentRunningTask(row) == 1) progressed++;
        return progressed;
    }

    private void ensureDevelopmentPhone() {
        if (!enabled || !FIXED_COUNTRY_CODE.equals(countryCode) || !FIXED_PHONE.equals(phone)) return;
        Long userId = mapper.findDevelopmentUserId(countryCode, phone);
        if (userId == null || userId <= 0 || mapper.findDevelopmentHomeDeviceId(userId) != null) return;
        mapper.ensureDevelopmentDevice(userId, "DEV-HOME-PHONE-" + userId);
    }

    private List<PlannedDevice> planDevices(
            List<DevelopmentTaskDevice> devices,
            List<DevelopmentTaskConfig> pool,
            Map<String, String> capacityConfig,
            LocalDateTime now) {
        if (devices == null || devices.isEmpty()) return List.of();
        List<PlannedDevice> plans = new ArrayList<>();
        for (DevelopmentTaskDevice device : devices) {
            if (!validDevice(device)) {
                log.warn("event=DEVELOPMENT_TASK_DEVICE_SKIPPED reason=DEVICE_FACT_INVALID");
                continue;
            }
            int capacityVram = effectiveVram(device);
            List<DevelopmentTaskConfig> eligible = pool.stream()
                    .filter(task -> task.minVram() <= capacityVram)
                    .toList();
            if (eligible.isEmpty()) {
                log.info("event=DEVELOPMENT_TASK_POOL_EMPTY deviceId={} capacityVram={}",
                        device.userDeviceId(), capacityVram);
                continue;
            }
            try {
                E3DeviceCapacityPolicy.Projection capacity = E3DeviceCapacityPolicy.project(
                        device.productCode(), device.deviceType(), device.purchasedAt(),
                        device.activatedAt(), now, capacityConfig);
                plans.add(new PlannedDevice(device, eligible, capacity));
            } catch (IllegalArgumentException invalid) {
                log.warn("event=DEVELOPMENT_TASK_DEVICE_SKIPPED deviceId={} reason={}",
                        device.userDeviceId(), invalid.getMessage());
            }
        }
        return plans;
    }

    private boolean settleCompletedTask(DevelopmentActiveTask task, LocalDateTime now) {
        if (mapper.completeDevelopmentTask(task.userId(), task.userDeviceId(), task.taskNo(), now) != 1) {
            return false;
        }
        String receiptNo = "R-" + task.taskNo();
        DevelopmentTaskSettlement settlement = new DevelopmentTaskSettlement(
                task.taskNo(), receiptNo, task.userId(), task.userDeviceId(), task.taskClass(),
                task.clientName(), task.rewardUsdt(), sha256(task.taskNo()), now);
        if (mapper.insertDevelopmentTaskReceipt(settlement) != 1) {
            throw new IllegalStateException("DEVELOPMENT_TASK_RECEIPT_CONFLICT");
        }
        mapper.ensureDevelopmentWallet(task.userId(), now);
        if (mapper.creditDevelopmentWallet(task.userId(), task.taskNo(), task.rewardUsdt(), now) != 1) {
            throw new IllegalStateException("DEVELOPMENT_TASK_WALLET_CREDIT_FAILED");
        }
        BigDecimal balanceAfter = mapper.developmentWalletUsdt(task.userId());
        if (balanceAfter == null
                || mapper.insertDevelopmentWalletLedger(task.userId(), task.taskNo(), task.rewardUsdt(),
                balanceAfter, now) != 1
                || mapper.insertDevelopmentEarningEvent("EARN-" + task.taskNo(), task.userId(),
                task.userDeviceId(), receiptNo, task.rewardUsdt(), now) != 1) {
            throw new IllegalStateException("DEVELOPMENT_TASK_SETTLEMENT_FAILED");
        }
        return true;
    }

    private DevelopmentRunningTask runningTask(
            DevelopmentTaskDevice device,
            DevelopmentTaskConfig task,
            BigDecimal reward,
            LocalDateTime now) {
        String taskNo = TASK_PREFIX + UUID.randomUUID().toString().replace("-", "")
                .toUpperCase(Locale.ROOT);
        return new DevelopmentRunningTask(
                taskNo, device.userId(), device.userDeviceId(), task.taskId(), task.name(),
                task.taskClass(), task.modelName(), CLIENT_NAME, reward, requiredSeconds(task.taskClass()),
                sha256(taskNo + ":nonce"), now, now.plusHours(24));
    }

    private List<DevelopmentTaskConfig> validatedTaskPool(List<DevelopmentTaskConfig> rows) {
        if (rows == null) return List.of();
        List<DevelopmentTaskConfig> valid = new ArrayList<>();
        for (DevelopmentTaskConfig row : rows) {
            if (row == null || row.taskId() == null || row.taskId().isBlank()
                    || row.name() == null || row.name().isBlank()
                    || row.taskClass() == null || !TASK_CLASSES.contains(row.taskClass().toUpperCase(Locale.ROOT))
                    || row.modelName() == null || row.modelName().isBlank()
                    || row.minVram() == null || row.minVram() < 0
                    || row.minReward() == null || row.maxReward() == null
                    || row.minReward().signum() < 0 || row.maxReward().compareTo(row.minReward()) < 0) {
                log.warn("event=DEVELOPMENT_TASK_PROGRESS_SKIPPED reason=E2_TASK_CONFIG_INVALID");
                return List.of();
            }
            valid.add(new DevelopmentTaskConfig(row.taskId(), row.name(),
                    row.taskClass().toUpperCase(Locale.ROOT), row.modelName(),
                    row.minReward(), row.maxReward(), row.minVram()));
        }
        return valid.stream()
                .sorted(Comparator.comparingInt(DevelopmentTaskConfig::minVram)
                        .thenComparing(DevelopmentTaskConfig::taskId))
                .toList();
    }

    private boolean validDevice(DevelopmentTaskDevice device) {
        return device != null && device.userId() != null && device.userId() > 0
                && device.userDeviceId() != null && device.userDeviceId() > 0
                && device.productCode() != null && !device.productCode().isBlank()
                && device.deviceType() != null && !device.deviceType().isBlank()
                && device.activatedAt() != null;
    }

    private int effectiveVram(DevelopmentTaskDevice device) {
        String type = device.deviceType().trim().toUpperCase(Locale.ROOT);
        String product = device.productCode().trim().toLowerCase(Locale.ROOT);
        // Cloud-share represents a managed provider slice rather than local physical VRAM.
        // Route it through the same low-capability E2 pool used by the 8 GB phone tier even if a
        // stale/incorrect physical VRAM value was persisted. The physical display value remains
        // untouched; this method is only the server routing equivalent.
        if (type.equals("SHARE") || type.equals("CLOUD") || product.equals("cloud-share")) return 8;
        return device.vramTotalGb() == null ? 0 : Math.max(0, device.vramTotalGb());
    }

    private BigDecimal taskReward(
            DevelopmentTaskConfig task,
            E3DeviceCapacityPolicy.Projection capacity) {
        BigDecimal midpoint = task.minReward().add(task.maxReward())
                .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
        return E3DeviceCapacityPolicy.applyCapacity(midpoint, capacity)
                .setScale(6, RoundingMode.HALF_UP);
    }

    private boolean taskFinished(DevelopmentActiveTask task, LocalDateTime now) {
        return task.startedAt() != null && task.requiredSeconds() != null && task.requiredSeconds() > 0
                && !now.isBefore(task.startedAt().plusSeconds(task.requiredSeconds()));
    }

    private int requiredSeconds(String taskClass) {
        return switch (taskClass.toUpperCase(Locale.ROOT)) {
            case "IG" -> 18;
            case "VG" -> 900;
            case "LL" -> 12;
            case "FT" -> 1800;
            case "EM" -> 5;
            case "SP" -> 30;
            default -> throw new IllegalArgumentException("TASK_ASSIGNMENT_CLASS_UNSUPPORTED");
        };
    }

    private Map<String, String> developmentCapacityConfig() {
        List<DevelopmentE3CapacityConfig> rows = mapper.developmentE3CapacityConfig();
        if (rows == null) return Map.of();
        Map<String, String> config = new LinkedHashMap<>();
        for (DevelopmentE3CapacityConfig row : rows) {
            if (row == null || row.configKey() == null || row.configValue() == null
                    || config.put(row.configKey(), row.configValue()) != null) {
                return Map.of();
            }
        }
        return config;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record PlannedDevice(
            DevelopmentTaskDevice device,
            List<DevelopmentTaskConfig> eligibleTasks,
            E3DeviceCapacityPolicy.Projection capacity) {
    }

    @FunctionalInterface
    private interface DeviceTransaction {
        int execute(IntSupplier work);
    }
}
