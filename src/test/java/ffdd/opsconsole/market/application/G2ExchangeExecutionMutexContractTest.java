package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class G2ExchangeExecutionMutexContractTest {
    @Test
    void migrationIsReachableFromExistingAndFreshDatabaseBootPaths() throws Exception {
        String migration = source("scripts/migrations/20260811_g2_exchange_execution_mutex.sql");
        String startup = source("scripts/apply_startup_schema_migrations.ps1");
        String baseline = source("scripts/schema.sql");

        assertThat(migration).contains("nx_admin_operation_mutex", "G2_EXCHANGE_EXECUTION");
        assertThat(startup).contains("20260811_g2_exchange_execution_mutex.sql");
        assertThat(baseline).contains("('G2_EXCHANGE_EXECUTION')");
    }

    @Test
    void appAndBatchUseTheSameAuthoritativeMysqlRowBeforeCapAccounting() throws Exception {
        String mapper = source("src/main/java/ffdd/opsconsole/market/mapper/AppExchangeMapper.java");
        String app = source("src/main/java/ffdd/opsconsole/market/application/AppExchangeService.java");
        String batch = source("src/main/java/ffdd/opsconsole/market/application/G2ExchangeQueueBatchService.java");

        assertThat(mapper).contains(
                "WHERE lock_key='G2_EXCHANGE_EXECUTION' FOR UPDATE",
                "String lockExchangeExecutionMutex()");
        assertThat(app.indexOf("mapper.lockExchangeExecutionMutex()"))
                .isLessThan(app.indexOf("mapper.platformTodayUsdt()"));
        assertThat(batch.indexOf("mapper.lockExchangeExecutionMutex()"))
                .isLessThan(batch.indexOf("mapper.platformTodayUsdt()"));
    }

    private String source(String relative) throws Exception {
        return Files.readString(Path.of(relative));
    }
}
