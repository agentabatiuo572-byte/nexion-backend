package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * zentao #92:支付联调 SKU「HDPay1U」仍出现在 App 商城「更多型号」。
 * 发布门本身是对的(契约测试已覆盖该行:无有效收益即拦),所以库里那行必然配了收益。
 * 本门钉住:迁移把该行的收益清零、状态改为非在售、并已注册进启动链。
 */
class E1RetireHdpayTestSkuMigrationContractTest {
    private static final Path MIGRATION = Path.of("scripts/migrations/20260921_e1_retire_hdpay_test_sku.sql");
    private static final Path STARTUP = Path.of("scripts/apply_startup_schema_migrations.ps1");
    private static final String ACCEPTANCE_SKU = "hd1-0902";

    @Test
    void migrationZeroesTheYieldSoThePublishGateWithholdsTheRow() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains("UPDATE nx_product");
        assertThat(sql).contains("'" + ACCEPTANCE_SKU + "'");
        assertThat(sql).contains("estimated_daily_usdt = 0");
        assertThat(sql).contains("daily_nex = 0");
    }

    @Test
    void migrationTakesTheRowOffSaleWithoutTouchingHistoricalRows() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        // 与 DeviceCatalogMapper 的 on/非 on 映射一致。
        assertThat(sql).contains("SET status = 'DRAFT'");
        // 只动这一个产品号,且不动订单/持仓/账本。
        assertThat(sql).doesNotContain("nx_order");
        assertThat(sql).doesNotContain("nx_wallet_ledger");
        assertThat(sql).doesNotContain("nx_user_device");
    }

    @Test
    void migrationRaisesInsteadOfSilentlyPassingWhenTheSkuStaysPublishable() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains("SIGNAL SQLSTATE ''45000''");
        assertThat(sql).contains("HDPAY_TEST_SKU_STILL_PUBLISHABLE");
    }

    @Test
    void migrationIsRegisteredInTheStartupChain() throws IOException {
        String startup = Files.readString(STARTUP, StandardCharsets.UTF_8);

        assertThat(startup).contains("20260921_e1_retire_hdpay_test_sku.sql");
    }
}
