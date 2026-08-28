package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Makes the explicitly configured local-development economy operable through
 * the same D3 command, audit and outbox path used by the PC administration UI.
 * Production never registers this runner and existing healthy reserves are
 * never changed.
 */
@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "nexion.treasury.development-reserve.enabled", havingValue = "true")
@Slf4j
public class DevelopmentTreasuryReserveBootstrap implements ApplicationRunner {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final OpsTreasuryService treasuryService;
    private final Clock clock;
    @SuppressWarnings("ArchitectureConfigField")
    private final boolean enabled;
    @SuppressWarnings("ArchitectureConfigField")
    private final BigDecimal minimumReserveUsdt;

    public DevelopmentTreasuryReserveBootstrap(
            OpsTreasuryService treasuryService,
            Clock clock,
            @Value("${nexion.treasury.development-reserve.enabled:false}") boolean enabled,
            @Value("${nexion.treasury.development-reserve.minimum-usdt:0}") BigDecimal minimumReserveUsdt) {
        this.treasuryService = treasuryService;
        this.clock = clock;
        this.enabled = enabled;
        this.minimumReserveUsdt = safe(minimumReserveUsdt);
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureReserve();
    }

    int ensureReserve() {
        if (!enabled || minimumReserveUsdt.signum() <= 0) return 0;
        LocalDate businessDay = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
        ApiResult<Map<String, Object>> result = treasuryService.ensureDevelopmentReserve(
                minimumReserveUsdt, businessDay);
        if (result == null || result.getCode() != 0) {
            // The development reward flow is unusable without canonical D3 coverage. Fail startup
            // instead of advertising a healthy service that can only reject B1-protected writes.
            throw new IllegalStateException("DEVELOPMENT_TREASURY_RESERVE_UNAVAILABLE");
        }
        Map<String, Object> data = result.getData();
        if (data == null || !Boolean.TRUE.equals(data.get("injected"))) return 0;
        BigDecimal amount = safe(data.get("amount") instanceof BigDecimal value ? value : null);
        log.info("event=DEVELOPMENT_TREASURY_RESERVE_READY amountUsdt={}", amount);
        return 1;
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }
}
