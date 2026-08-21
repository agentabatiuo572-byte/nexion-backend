package ffdd.opsconsole.market.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Contract for restart-equivalent authority: a new service instance must use the same SQL scope. */
class MarketSandboxPersistenceContractTest {
    @Test
    void sandboxAuthorityHasNoProcessLocalStateAndEveryAggregateLookupIsScoped() throws Exception {
        String staking = Files.readString(Path.of("src/main/java/ffdd/opsconsole/market/application/AppStakingService.java"), StandardCharsets.UTF_8);
        String repurchase = Files.readString(Path.of("src/main/java/ffdd/opsconsole/market/application/AppRepurchaseService.java"), StandardCharsets.UTF_8);
        String mapper = Files.readString(Path.of("src/main/java/ffdd/opsconsole/market/mapper/MarketSandboxMapper.java"), StandardCharsets.UTF_8);
        assertThat(staking + repurchase).doesNotContain("MarketSandboxState", "ConcurrentHashMap", "static final Map");
        assertThat(mapper).contains("domain_key=#{domain}", "run_id=#{runId}", "user_id=#{userId}", "version=#{expectedVersion}");
        assertThat(mapper).contains("INSERT IGNORE INTO nx_market_sandbox_idempotency");
    }
}
