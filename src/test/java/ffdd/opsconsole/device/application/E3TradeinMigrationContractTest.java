package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class E3TradeinMigrationContractTest {
    private final String migration = read("scripts/migrations/20260721_e3_user_tradein_flow.sql");
    private final String schema = read("scripts/schema.sql");

    @Test
    void zeroAmountD4TraceIsAllowedOnlyForSuccessfulUsdtTradeinPurchase() {
        for (String sql : new String[] {migration, schema}) {
            assertThat(sql.replace("''", "'"))
                    .contains("amount > 0 OR")
                    .contains("amount = 0")
                    .contains("biz_type = 'TRADE_IN_PURCHASE'")
                    .contains("asset = 'USDT'")
                    .contains("direction = 'OUT'")
                    .contains("status = 'SUCCESS'");
        }
    }

    @Test
    void migrationReplacesTheLegacyPositiveOnlyConstraintIdempotently() {
        assertThat(migration)
                .contains("LOCATE('TRADE_IN_PURCHASE', UPPER(CHECK_CLAUSE))=0")
                .contains("DROP CHECK chk_wallet_ledger_positive_amount")
                .contains("ADD CONSTRAINT chk_wallet_ledger_positive_amount");
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
