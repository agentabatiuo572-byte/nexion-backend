package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * zentao #78:试用 hero 写「3 天预计抵扣金 $116」,同屏 S1 商品卡写 $1.00/天。
 * 展示侧已改成优先用商品自己的日收益,但**没有任何迁移给 nx_product 写过
 * estimated_daily_usdt**(schema 默认 0),于是回落到库里存的运营旧值 38.52。
 * 本门钉住:迁移给试用目标商品写入目录权威日收益、把旧回落值对齐、并已注册进启动链。
 */
class H2TrialProductDailyYieldMigrationContractTest {
    private static final Path MIGRATION = Path.of("scripts/migrations/20260921_h2_trial_product_daily_yield.sql");
    private static final Path STARTUP = Path.of("scripts/apply_startup_schema_migrations.ps1");
    /** 与 AppTrialLifecycleService.CANONICAL_TRIAL_PRODUCT_ID 同源。 */
    private static final String TRIAL_PRODUCT = "stellarbox-s1";

    @Test
    void migrationSeedsTheCatalogueAuthoritativeYieldForTheTrialProduct() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains("UPDATE nx_product");
        assertThat(sql).contains("'" + TRIAL_PRODUCT + "'");
        assertThat(sql).contains("estimated_daily_usdt = 1");
        assertThat(sql).contains("daily_nex = 1");
        // 只补未配置的行,不覆盖运营后来显式配置的收益。
        assertThat(sql).contains("estimated_daily_usdt = 0");
    }

    @Test
    void migrationAlignsOnlyTheStaleFallbackPolicyValues() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains("nx_growth_trial_policy");
        // 只改仍等于旧值的行 —— 运营改过的值必须原样保留。
        assertThat(sql).contains("current_value = '38.52'");
        assertThat(sql).contains("current_value = '65'");
        // 商品值就绪才允许改回落值。
        assertThat(sql).contains("EXISTS (SELECT 1 FROM nx_product");
    }

    @Test
    void migrationRaisesInsteadOfSilentlyPassingWhenTheYieldIsMissing() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains("SIGNAL SQLSTATE ''45000''");
        assertThat(sql).contains("TRIAL_PRODUCT_DAILY_YIELD_MISSING");
    }

    @Test
    void migrationIsRegisteredInTheStartupChain() throws IOException {
        String startup = Files.readString(STARTUP, StandardCharsets.UTF_8);

        assertThat(startup).contains("20260921_h2_trial_product_daily_yield.sql");
    }
}
