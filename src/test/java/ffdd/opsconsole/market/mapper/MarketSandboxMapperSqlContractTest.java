package ffdd.opsconsole.market.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MarketSandboxMapperSqlContractTest {
    @Test
    void migrationCreatesOnlyRunAndAccountScopedMarketSandboxFacts() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations/20260817_g1_g7_market_sandbox.sql"));
        assertThat(sql).contains("nx_market_sandbox_account", "nx_market_sandbox_position",
                "nx_market_sandbox_idempotency", "run_id", "user_id", "source_environment")
                .doesNotContain("nx_user_wallet", "nx_staking_position", "nx_wallet_ledger");
    }

    @Test
    void mapperCasAndIdempotencySqlCarryTheCompleteScope() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/market/mapper/MarketSandboxMapper.java"));
        assertThat(source).contains("run_id=#{runId}", "user_id=#{userId}", "version=#{expectedVersion}",
                "INSERT IGNORE", "request_hash", "source_environment");
    }
}
