package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.device.mapper.DeviceCatalogMapper;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/**
 * Defends the storefront publish gate as a real, shared contract: every
 * user-facing read must carry it, and the reason vocabulary the E1 operator
 * projection exposes must match the gate's own decision.
 */
class StorefrontProductPublishGateContractTest {

    @Test
    void everyUserFacingStorefrontReadCarriesThePublishGate() throws Exception {
        assertThat(select(AppTradeinMapper.class, "listPurchasableCatalogTargets"))
                .contains(StorefrontProductPublishGate.PUBLISHABLE_SQL);
        assertThat(select(AppHomeOverviewMapper.class, "marketProducts"))
                .contains(StorefrontProductPublishGate.PUBLISHABLE_SQL);
    }

    @Test
    void publishGateWithheldRowsAreReportedWithTheirReasonRatherThanDroppedSilently() throws Exception {
        String sql = select(AppTradeinMapper.class, "listPublishBlockedCatalogTargets");
        assertThat(sql)
                .contains(StorefrontProductPublishGate.BLOCKED_SQL)
                .contains(StorefrontProductPublishGate.BLOCK_REASON_SQL)
                .contains(StorefrontProductPublishGate.TEST_IDENTIFIER_REASON)
                .contains(StorefrontProductPublishGate.NO_EFFECTIVE_EARNINGS_REASON);
    }

    @Test
    void e1SkuProjectionExposesTheSameGateDecisionOperatorsSee() {
        assertThat(DeviceCatalogMapper.SKU_COLUMNS)
                .contains(StorefrontProductPublishGate.BLOCKED_SQL + " AS publishBlocked")
                .contains(StorefrontProductPublishGate.BLOCK_REASON_SQL + " AS publishBlockReason");
    }

    @Test
    void testIdentifiersAreWholeTokensSoOrdinaryProductNamesStayPublishable() {
        // Real catalogue rows must never be withheld by the test-identity rule.
        assertThat(gate("stellarbox-s1", "NexGridBox S1", "1", "1").publishable()).isTrue();
        assertThat(gate("stellarbox-pro-v2", "NexionBox Pro v2", "18", "110").publishable()).isTrue();
        assertThat(gate("stellarrack-p1", "StellarRack P1", "45", "300").publishable()).isTrue();
        assertThat(gate("cloud-share", "Cloud Share", "0.19", "3").publishable()).isTrue();
        // Substring markers are not markers.
        assertThat(gate("latest-box", "Latest Box", "5", "0").publishable()).isTrue();
        assertThat(gate("testament-box", "Testament Box", "5", "0").publishable()).isTrue();
    }

    @Test
    void withheldRowsCarryTheReasonThatDemandsOperatorAction() {
        assertThat(gate("stellarbox-test", "NexionBox Test", "12.3", "24"))
                .isEqualTo(new StorefrontProductPublishGate.Decision(
                        false, StorefrontProductPublishGate.TEST_IDENTIFIER_REASON));
        assertThat(gate("demo-box", "Nexion Demo", "12.3", "24"))
                .isEqualTo(new StorefrontProductPublishGate.Decision(
                        false, StorefrontProductPublishGate.TEST_IDENTIFIER_REASON));
        // The reported HDPay1U row: on sale and priced, but no configured yield.
        assertThat(gate("hd1-0902", "HDPay1U", "0", "0"))
                .isEqualTo(new StorefrontProductPublishGate.Decision(
                        false, StorefrontProductPublishGate.NO_EFFECTIVE_EARNINGS_REASON));
    }

    @Test
    void eitherCurrencyAloneIsEnoughToSellAProduct() {
        assertThat(gate("usdt-box", "USDT Box", "0", "3").publishable()).isTrue();
        assertThat(gate("nex-box", "NEX Box", "0.5", "0").publishable()).isTrue();
        // Unconfigured yield is treated as zero, matching the SQL COALESCE.
        assertThat(gate("unset-box", "Unset Box", null, null).publishable()).isFalse();
    }

    private static StorefrontProductPublishGate.Decision gate(
            String productNo, String name, String dailyUsdt, String dailyNex) {
        return StorefrontProductPublishGate.evaluate(productNo, name,
                dailyUsdt == null ? null : new BigDecimal(dailyUsdt),
                dailyNex == null ? null : new BigDecimal(dailyNex));
    }

    private static String select(Class<?> type, String name) throws Exception {
        Method method = type.getMethod(name);
        return String.join("\n", method.getAnnotation(Select.class).value());
    }
}
