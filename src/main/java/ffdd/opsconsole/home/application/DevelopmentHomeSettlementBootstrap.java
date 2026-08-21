package ffdd.opsconsole.home.application;

import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper.DevelopmentSettlement;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the fixed local Passkey account useful without frontend mock values.
 * The rows live in nx_compute_task/nx_compute_receipt and are read by the same
 * Java overview query as every other settled earning. The dev profile and an
 * explicit property are both required, so production can never run this seed.
 */
@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "nexion.home.development-settlement.enabled", havingValue = "true")
@Slf4j
public class DevelopmentHomeSettlementBootstrap implements ApplicationRunner {
    private static final String FIXED_COUNTRY_CODE = "+86";
    private static final String FIXED_PHONE = "18708173775";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<BigDecimal> REWARDS = List.of(
            new BigDecimal("68.40"), new BigDecimal("77.25"), new BigDecimal("55.90"),
            new BigDecimal("64.34"), new BigDecimal("58.00"));
    private static final List<String> CLIENTS = List.of(
            "Gemma AI Support", "NexGrid Vision", "NexGrid RAG", "Gemma AI Support", "NexGrid Embed");
    private static final List<String> MODELS = List.of(
            "gemma4-e4b-ctx32k", "vision-render-v2", "nexion-rag-v1", "gemma4-e4b-ctx32k", "embed-v3");

    private final DevelopmentHomeSettlementMapper mapper;
    private final Clock clock;
    @SuppressWarnings("ArchitectureConfigField")
    private final String countryCode;
    @SuppressWarnings("ArchitectureConfigField")
    private final String phone;
    @SuppressWarnings("ArchitectureConfigField")
    private final boolean enabled;

    public DevelopmentHomeSettlementBootstrap(
            DevelopmentHomeSettlementMapper mapper,
            Clock clock,
            @Value("${nexion.auth.development-passkey-account.country-code:}") String countryCode,
            @Value("${nexion.auth.development-passkey-account.phone:}") String phone,
            @Value("${nexion.home.development-settlement.enabled:false}") boolean enabled) {
        this.mapper = mapper;
        this.clock = clock;
        this.countryCode = countryCode == null ? "" : countryCode.trim();
        this.phone = phone == null ? "" : phone.trim();
        this.enabled = enabled;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        seedToday();
    }

    @Scheduled(cron = "5 0 0 * * *", zone = "Asia/Shanghai")
    @Transactional(rollbackFor = Exception.class)
    public synchronized int seedToday() {
        if (!enabled || !FIXED_COUNTRY_CODE.equals(countryCode) || !FIXED_PHONE.equals(phone)) {
            log.warn("event=DEVELOPMENT_HOME_SETTLEMENT_SKIPPED reason=configuration_unavailable");
            return 0;
        }
        Long userId = mapper.findDevelopmentUserId(countryCode, phone);
        if (userId == null || userId <= 0) {
            log.warn("event=DEVELOPMENT_HOME_SETTLEMENT_SKIPPED reason=account_unavailable");
            return 0;
        }
        Long deviceId = mapper.findDevelopmentHomeDeviceId(userId);
        if (deviceId == null || deviceId <= 0) {
            mapper.ensureDevelopmentDevice(userId, "DEV-HOME-PHONE-" + userId);
            deviceId = mapper.findDevelopmentHomeDeviceId(userId);
        }
        if (deviceId == null || deviceId <= 0) {
            log.warn("event=DEVELOPMENT_HOME_SETTLEMENT_SKIPPED reason=device_unavailable");
            return 0;
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE);
        LocalDate today = now.toLocalDate();
        LocalDateTime dayStart = today.atStartOfDay();
        String prefix = "DEV-HOME-" + DAY_KEY.format(today) + "-" + userId + "-";
        int inserted = 0;
        for (int index = 0; index < REWARDS.size(); index++) {
            String sequence = String.format("%02d", index + 1);
            String taskNo = prefix + sequence;
            LocalDateTime completedAt = now.minusMinutes(3L + 11L * index);
            if (completedAt.isBefore(dayStart)) completedAt = dayStart;
            LocalDateTime startedAt = completedAt.minusSeconds(45L + 5L * index);
            if (startedAt.isBefore(dayStart)) startedAt = dayStart;
            DevelopmentSettlement row = new DevelopmentSettlement(
                    taskNo, "R-" + taskNo, userId, deviceId, "LLM_INFERENCE",
                    "Development settled compute task " + sequence, MODELS.get(index), CLIENTS.get(index),
                    REWARDS.get(index), 45 + 5 * index, "PRODUCTION", sha256(taskNo), startedAt, completedAt);
            mapper.insertCompletedTask(row);
            inserted += mapper.insertSettledReceipt(row);
        }
        log.info("event=DEVELOPMENT_HOME_SETTLEMENT_READY insertedReceipts={}", inserted);
        return inserted;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
