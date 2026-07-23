package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class G2G3ClosureContractTest {
    private static String source(String relative) throws Exception {
        return Files.readString(Path.of(relative));
    }

    @Test
    void appExchangeAndPublicMarketAreServerCanonical() throws Exception {
        String controller = source("src/main/java/ffdd/opsconsole/market/web/AppExchangeController.java");
        String service = source("src/main/java/ffdd/opsconsole/market/application/AppExchangeService.java");
        String mapper = source("src/main/java/ffdd/opsconsole/market/mapper/AppExchangeMapper.java");
        assertThat(controller).contains("/api/config/exchange/caps", "/api/config/market/nex", "/api/exchange");
        assertThat(service).contains("@Transactional", "AdminIdempotencyService", "EventOutboxService",
                "exchange.swapped", "risk.kyc_review_triggered", "triggerCumulativeExchangeReview",
                "applyWalletDelta", "insertLedger");
        assertThat(mapper).contains("FOR UPDATE",
                "CONCAT('U', IF(id<100000000,LPAD(id,8,'0'),CAST(id AS CHAR)))", "nx_user_wallet",
                "nx_exchange_order", "nx_wallet_ledger");
        assertThat(mapper).doesNotContain("SELECT user_no FROM nx_user", "CONCAT('U', LPAD(id,8,'0'))");
    }

    @Test
    void canonicalUserNumberKeepsSevenEightNineAndElevenDigitIdsDistinct() {
        assertThat(java.util.List.of(1_234_567L, 12_345_678L, 123_456_789L, 60_723_152_626L)
                .stream().map(G2G3ClosureContractTest::canonicalUserNo).toList())
                .containsExactly("U01234567", "U12345678", "U123456789", "U60723152626")
                .doesNotHaveDuplicates();
    }

    @Test
    void adminCommandsAndSchedulerUsePersistentBoundaries() throws Exception {
        String boundary = source("src/main/java/ffdd/opsconsole/market/application/G2G3AdminCommandService.java");
        String scheduler = source("src/main/java/ffdd/opsconsole/market/application/G3ScheduledAdvanceService.java");
        assertThat(boundary).contains("AdminIdempotencyService", "EventOutboxService", "@Transactional",
                "admin.exchange_", "admin.nex_", "market.curve_advanced");
        assertThat(scheduler).contains("claimRunDate", "@Transactional", "market.curve_advanced");
    }

    @Test
    void migrationRegistersExecutionLockAndCanonicalEvents() throws Exception {
        String migration = source("scripts/migrations/20260722_g2_g3_closure.sql");
        assertThat(migration).contains("nx_g3_schedule_execution", "UNIQUE KEY uk_g3_schedule_run_date",
                "exchange.swapped", "admin.exchange_caps_changed", "admin.nex_price_curve_changed",
                "market.curve_advanced", "risk.kyc_review_triggered", "cumulative_usdt", "threshold_usdt");
    }

    private static String canonicalUserNo(long id) {
        return "U" + (id < 100_000_000L ? String.format("%08d", id) : Long.toString(id));
    }
}
