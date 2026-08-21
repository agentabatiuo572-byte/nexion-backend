package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.DeviceCatalogMapper;
import ffdd.opsconsole.device.mapper.DeviceCatalogMapper.TaskPriceHistorySchemaRow;
import ffdd.opsconsole.device.mapper.DeviceCatalogMapper.TaskPriceSeedRow;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Permanent price observations for E2 tasks; the current task price remains authoritative in E2. */
@Service
@RequiredArgsConstructor
public class E2TaskPriceHistoryService {
    public static final String SOURCE_DEV_SEED = "DEVELOPMENT_SEED";
    public static final String SOURCE_PC_CREATE = "PC_CREATE";
    public static final String SOURCE_PC_UPDATE = "PC_UPDATE";
    public static final String SOURCE_PC_PRICE = "PC_PRICE";
    public static final String SOURCE_SCHEDULED = "SCHEDULED_SNAPSHOT";

    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BUCKET_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm");
    private static final Set<String> WRITE_SOURCES = Set.of(SOURCE_PC_CREATE, SOURCE_PC_UPDATE, SOURCE_PC_PRICE);
    private static final Map<String, BigDecimal> DEVELOPMENT_DELTAS = Map.of(
            "IG", new BigDecimal("3.20"),
            "VG", new BigDecimal("2.40"),
            "LL", new BigDecimal("-1.10"),
            "FT", new BigDecimal("0.50"),
            "EM", new BigDecimal("0.80"),
            "SP", new BigDecimal("-0.40"));
    private static final List<BigDecimal> POSITIVE_CURVE = List.of(
            new BigDecimal("0.986"), new BigDecimal("0.990"), new BigDecimal("0.988"),
            new BigDecimal("0.994"), new BigDecimal("0.996"), new BigDecimal("0.993"),
            new BigDecimal("1.000"), new BigDecimal("0.997"), new BigDecimal("1.002"),
            new BigDecimal("0.999"), new BigDecimal("1.004"), BigDecimal.ONE);
    private static final List<BigDecimal> NEGATIVE_CURVE = List.of(
            new BigDecimal("1.014"), new BigDecimal("1.010"), new BigDecimal("1.012"),
            new BigDecimal("1.006"), new BigDecimal("1.004"), new BigDecimal("1.007"),
            BigDecimal.ONE, new BigDecimal("1.003"), new BigDecimal("0.998"),
            new BigDecimal("1.001"), new BigDecimal("0.996"), BigDecimal.ONE);

    private final DeviceCatalogMapper mapper;
    private final Clock clock;

    @PostConstruct
    void validateSchema() {
        TaskPriceHistorySchemaRow schema = mapper.taskPriceHistorySchema();
        if (schema == null || schema.tableCount() != 1 || schema.columnCount() != 9
                || schema.uniqueIndexCount() != 1 || schema.observedIndexCount() != 1
                || schema.taskTimeIndexCount() != 1 || schema.classTimeIndexCount() != 1) {
            throw new IllegalStateException("E2_TASK_PRICE_HISTORY_SCHEMA_INVALID");
        }
    }

    @Transactional
    public void recordCurrentPrice(String taskId, String sourceType, LocalDateTime observedAt) {
        if (taskId == null || taskId.isBlank() || observedAt == null || !WRITE_SOURCES.contains(sourceType)) {
            throw new IllegalArgumentException("TASK_PRICE_HISTORY_INPUT_INVALID");
        }
        mapper.insertTaskPriceHistoryFromTask(taskId.trim(), sourceType, observedAt);
    }

    /**
     * Completes development history per active task. Rows use the same permanent business table
     * and deterministic keys as normal observations, so restarts repair gaps without duplicates.
     */
    @Transactional
    public int seedDevelopmentHistory() {
        LocalDateTime now = serverNow();
        int inserted = 0;
        for (TaskPriceSeedRow task : safe(mapper.activeTaskPriceSeeds())) {
            if (task == null || task.taskId() == null || task.price() == null || task.price().signum() <= 0) {
                continue;
            }
            String taskClass = canonicalTaskClass(task.taskClass());
            BigDecimal delta = DEVELOPMENT_DELTAS.getOrDefault(taskClass, BigDecimal.ZERO);
            BigDecimal baseline = task.price().divide(
                    BigDecimal.ONE.add(delta.movePointLeft(2)), 8, RoundingMode.HALF_UP);
            inserted += insertSeed(task, taskClass, baseline, "dev-seed-v1-baseline",
                    now.minusHours(23).minusMinutes(55), now);

            List<BigDecimal> curve = delta.signum() < 0 ? NEGATIVE_CURVE : POSITIVE_CURVE;
            for (int index = 0; index < curve.size(); index++) {
                int minutesAgo = (curve.size() - 1 - index) * 5;
                BigDecimal price = index == curve.size() - 1
                        ? task.price()
                        : task.price().multiply(curve.get(index)).setScale(8, RoundingMode.HALF_UP);
                inserted += insertSeed(task, taskClass, price, "dev-seed-v1-m" + minutesAgo,
                        now.minusMinutes(minutesAgo), now);
            }
        }
        return inserted;
    }

    @Scheduled(
            fixedDelayString = "${nexion.device.task-price-history.snapshot-ms:300000}",
            initialDelayString = "${nexion.device.task-price-history.initial-delay-ms:60000}")
    @Transactional
    public void snapshotCurrentPrices() {
        LocalDateTime bucket = fiveMinuteBucket(serverNow());
        mapper.snapshotActiveTaskPrices(bucket, "scheduled-" + BUCKET_FORMAT.format(bucket));
    }

    private int insertSeed(TaskPriceSeedRow task, String taskClass, BigDecimal price, String sampleKey,
                           LocalDateTime observedAt, LocalDateTime createdAt) {
        return mapper.insertTaskPriceHistory(
                task.taskId().trim(),
                taskClass,
                price,
                task.unit() == null || task.unit().isBlank() ? "/job" : task.unit().trim(),
                SOURCE_DEV_SEED,
                sampleKey,
                observedAt,
                createdAt);
    }

    private LocalDateTime serverNow() {
        return LocalDateTime.ofInstant(clock.instant(), SERVER_ZONE).withNano(0);
    }

    private static LocalDateTime fiveMinuteBucket(LocalDateTime value) {
        return value.withMinute(value.getMinute() - value.getMinute() % 5).withSecond(0).withNano(0);
    }

    private static String canonicalTaskClass(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "IMAGE_GEN", "IMAGE_GENERATION" -> "IG";
            case "VIDEO_RENDER", "VIDEO_GENERATION" -> "VG";
            case "LLM_INFERENCE" -> "LL";
            case "FINE_TUNE", "FINE_TUNING" -> "FT";
            case "EMBEDDING" -> "EM";
            case "SPEECH", "SPEECH_TO_TEXT" -> "SP";
            default -> normalized;
        };
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}

@Component
@Profile("dev")
@RequiredArgsConstructor
class DevelopmentTaskPriceHistoryInitializer implements ApplicationRunner {
    private final E2TaskPriceHistoryService historyService;

    @Override
    public void run(ApplicationArguments args) {
        historyService.seedDevelopmentHistory();
    }
}
