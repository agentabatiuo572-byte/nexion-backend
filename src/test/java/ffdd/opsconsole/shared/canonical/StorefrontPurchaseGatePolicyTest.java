package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.domain.DevicePurchaseGateView;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StorefrontPurchaseGatePolicyTest {
    private final StorefrontPurchaseGatePolicy policy = new StorefrontPurchaseGatePolicy();

    @Test
    void acceptsNormalSkuDtoNullOptionalsWithoutLosingRankEnforcement() throws Exception {
        String gate = new ObjectMapper().writeValueAsString(
                new DevicePurchaseGateView(2, null, null, "all", null, null, null, true));
        assertThat(gate).contains("\"activeDirectMin\":0", "\"teamComputePointsMin\":0",
                "\"quotaCap\":null", "\"quotaPeriod\":null");
        var qualified = new StorefrontPurchaseGatePolicy.Facts(5, 0, BigDecimal.ZERO);
        assertThat(policy.evaluate(gate, qualified)).isEqualTo(StorefrontPurchaseGatePolicy.Decision.open());
        assertThat(policy.project(gate, qualified).conditions()).singleElement()
                .satisfies(condition -> {
                    assertThat(condition.kind()).isEqualTo("rank");
                    assertThat(condition.required()).isEqualByComparingTo("2");
                    assertThat(condition.current()).isEqualByComparingTo("5");
                    assertThat(condition.met()).isTrue();
                });
        assertThat(policy.evaluate(gate, new StorefrontPurchaseGatePolicy.Facts(1, 0, BigDecimal.ZERO)).code())
                .isEqualTo("PURCHASE_GATE_NOT_MET");
        assertThat(policy.evaluate(gate, null).code()).isEqualTo("PURCHASE_GATE_FACTS_UNAVAILABLE");
        assertThat(policy.hasQuota(gate)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "either"})
    void zeroMinimumsCannotBypassPositiveRankAndDoNotAppearInProjection(String mode) {
        String gate = "{\"mode\":\"" + mode + "\",\"enforce\":true,"
                + "\"rankMin\":2,\"activeDirectMin\":0,\"teamComputePointsMin\":0}";
        var below = new StorefrontPurchaseGatePolicy.Facts(1, 0, BigDecimal.ZERO);
        var qualified = new StorefrontPurchaseGatePolicy.Facts(2, 0, BigDecimal.ZERO);
        assertThat(policy.evaluate(gate, below).code()).isEqualTo("PURCHASE_GATE_NOT_MET");
        assertThat(policy.evaluate(gate, qualified)).isEqualTo(StorefrontPurchaseGatePolicy.Decision.open());
        assertThat(policy.evaluate(gate, null).code()).isEqualTo("PURCHASE_GATE_FACTS_UNAVAILABLE");
        assertThat(policy.project(gate, below).conditions()).singleElement().satisfies(condition -> {
            assertThat(condition.kind()).isEqualTo("rank");
            assertThat(condition.met()).isFalse();
        });
        assertThat(policy.project(gate, null).decision().code()).isEqualTo("PURCHASE_GATE_FACTS_UNAVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "either"})
    void zeroMissingAndLegacyNullMinimumsNeedNoFactsButRetainQuota(String mode) {
        String controls = "{\"mode\":\"" + mode + "\",\"enforce\":true";
        for (String fields : new String[]{"", ",\"rankMin\":0,\"activeDirectMin\":0,\"teamComputePointsMin\":0",
                ",\"rankMin\":null,\"activeDirectMin\":null,\"teamComputePointsMin\":null"}) {
            String gate = controls + fields + "}";
            assertThat(policy.evaluate(gate, null)).isEqualTo(StorefrontPurchaseGatePolicy.Decision.open());
            assertThat(policy.project(gate, null).conditions()).isEmpty();
            assertThat(policy.project(gate, null).decision()).isEqualTo(StorefrontPurchaseGatePolicy.Decision.open());
            assertThat(policy.hasQuota(gate)).isFalse();
            String available = controls + fields + ",\"quotaCap\":2,\"quotaSold\":0,\"quotaPeriod\":\"lifetime\"}";
            assertThat(policy.evaluate(available, null)).isEqualTo(StorefrontPurchaseGatePolicy.Decision.open());
            String soldOut = controls + fields + ",\"quotaCap\":2,\"quotaSold\":2,\"quotaPeriod\":\"lifetime\"}";
            assertThat(policy.hasQuota(soldOut)).isTrue();
            assertThat(policy.evaluate(soldOut, null).code()).isEqualTo("PURCHASE_GATE_SOLD_OUT");
            assertThat(policy.project(soldOut, null).conditions()).singleElement()
                    .satisfies(condition -> assertThat(condition.kind()).isEqualTo("lifetimeQuota"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"rankMin\":null,\"activeDirectMin\":null,\"teamComputePointsMin\":null"})
    void dtoDefaultsOnlyMissingMinimumsToZeroAndPreservesUnlimitedQuota(String fields) throws Exception {
        var json = new ObjectMapper();
        var dto = json.readValue("{\"mode\":\"all\",\"enforce\":true" + fields + "}", DevicePurchaseGateView.class);
        assertThat(dto.rankMin()).isZero();
        assertThat(dto.activeDirectMin()).isZero();
        assertThat(dto.teamComputePointsMin()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.quotaCap()).isNull();
        assertThat(dto.quotaSold()).isNull();
        assertThat(json.writeValueAsString(dto)).contains("\"rankMin\":0", "\"activeDirectMin\":0",
                "\"teamComputePointsMin\":0", "\"quotaCap\":null", "\"quotaSold\":null");
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "either"})
    void legacyNullableMinimumsStillEnforcePositiveThreshold(String mode) {
        String gate = "{\"mode\":\"" + mode + "\",\"enforce\":true,"
                + "\"rankMin\":2,\"activeDirectMin\":null,\"teamComputePointsMin\":null}";
        assertThat(policy.evaluate(gate, new StorefrontPurchaseGatePolicy.Facts(1, 0, BigDecimal.ZERO)).code())
                .isEqualTo("PURCHASE_GATE_NOT_MET");
        assertThat(policy.project(gate, null).decision().code()).isEqualTo("PURCHASE_GATE_FACTS_UNAVAILABLE");
    }

    @Test
    void nullableRankDoesNotBypassDirectVolumeOrLifetimeQuota() throws Exception {
        var json = new ObjectMapper();
        var facts = new StorefrontPurchaseGatePolicy.Facts(5, 2, new BigDecimal("100"));
        String direct = json.writeValueAsString(
                new DevicePurchaseGateView(null, 3, null, "all", null, null, null, true));
        String volume = json.writeValueAsString(
                new DevicePurchaseGateView(null, null, new BigDecimal("200"), "either", null, null, null, true));
        for (String gate : new String[]{direct, volume}) {
            assertThat(policy.evaluate(gate, facts).code()).isEqualTo("PURCHASE_GATE_NOT_MET");
            assertThat(policy.project(gate, facts).conditions()).singleElement()
                    .satisfies(condition -> assertThat(condition.met()).isFalse());
        }
        String available = json.writeValueAsString(
                new DevicePurchaseGateView(null, null, null, "all", 2, 0, "lifetime", true));
        assertThat(policy.hasQuota(available)).isTrue();
        assertThat(policy.evaluate(available, facts)).isEqualTo(StorefrontPurchaseGatePolicy.Decision.open());
        String quota = json.writeValueAsString(
                new DevicePurchaseGateView(null, null, null, "all", 2, 2, "lifetime", true));
        assertThat(policy.hasQuota(quota)).isTrue();
        assertThat(policy.evaluate(quota, facts).code()).isEqualTo("PURCHASE_GATE_SOLD_OUT");
        assertThat(policy.project(quota, facts).conditions()).singleElement()
                .satisfies(condition -> assertThat(condition.kind()).isEqualTo("lifetimeQuota"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"rankMin\":\"2\"", "\"rankMin\":2.5", "\"rankMin\":9", "\"rankMin\":-1",
            "\"activeDirectMin\":false", "\"activeDirectMin\":1000001", "\"teamComputePointsMin\":\"200\"",
            "\"teamComputePointsMin\":-1", "\"quotaCap\":2,\"quotaSold\":null",
            "\"quotaCap\":null,\"quotaSold\":0", "\"quotaCap\":0,\"quotaSold\":0",
            "\"quotaCap\":2,\"quotaSold\":3", "\"quotaCap\":2,\"quotaSold\":0,\"quotaPeriod\":null",
            "\"quotaPeriod\":\"month\"", "\"quotaPeriod\":false",
            "\"unknownCondition\":null"
    })
    void explicitNullCompatibilityStillRejectsInvalidNonNullFields(String fields) {
        String gate = "{\"mode\":\"all\",\"enforce\":true," + fields + "}";
        var facts = new StorefrontPurchaseGatePolicy.Facts(5, 10, new BigDecimal("99999"));
        assertThat(policy.evaluate(gate, facts).code()).isEqualTo("PURCHASE_GATE_INVALID");
        assertThat(policy.project(gate, facts).decision().code()).isEqualTo("PURCHASE_GATE_INVALID");
        assertThat(policy.hasQuota(gate)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"mode\":null,\"enforce\":true}", "{\"mode\":\"all\",\"enforce\":null}"})
    void requiredControlFieldsCannotBeNull(String gate) {
        assertThat(policy.evaluate(gate, null).code()).isEqualTo("PURCHASE_GATE_INVALID");
        assertThat(policy.hasQuota(gate)).isFalse();
    }

    @Test
    void acceptsStructuredAllGateOnlyWhenServerFactsMeetEveryCondition() {
        var gate = "{\"rankMin\":2,\"activeDirectMin\":3,\"teamComputePointsMin\":50000,\"mode\":\"all\",\"enforce\":true}";
        assertThat(policy.evaluate(gate, new StorefrontPurchaseGatePolicy.Facts(2, 3, new BigDecimal("50000")))).isEqualTo(
                StorefrontPurchaseGatePolicy.Decision.open());
        assertThat(policy.evaluate(gate, new StorefrontPurchaseGatePolicy.Facts(2, 2, new BigDecimal("50000"))).code())
                .isEqualTo("PURCHASE_GATE_NOT_MET");
    }

    @Test
    void malformedOrUnknownGateFailsClosedAndQuotaCannotBeBypassed() {
        assertThat(policy.evaluate("{\"mode\":\"all\",\"enforce\":true,\"unknown\":1}", null).code())
                .isEqualTo("PURCHASE_GATE_INVALID");
        assertThat(policy.evaluate("{\"mode\":\"all\",\"enforce\":true,\"quotaCap\":2,\"quotaSold\":2}",
                new StorefrontPurchaseGatePolicy.Facts(0, 0, BigDecimal.ZERO)).code())
                .isEqualTo("PURCHASE_GATE_SOLD_OUT");
    }

    @Test
    void displayOnlyGateNeverBlocksOrConsumesQuotaAfterStructuralValidation() {
        String gate = "{\"rankMin\":8,\"quotaCap\":1,\"quotaSold\":1,\"mode\":\"all\",\"enforce\":false}";
        assertThat(policy.evaluate(gate, new StorefrontPurchaseGatePolicy.Facts(0, 0, BigDecimal.ZERO)))
                .isEqualTo(StorefrontPurchaseGatePolicy.Decision.open());
        assertThat(policy.hasQuota(gate)).isFalse();
        assertThat(policy.evaluate("{\"mode\":\"all\",\"enforce\":false,\"unknown\":1}", null).code())
                .isEqualTo("PURCHASE_GATE_INVALID");
    }

    @Test
    void negativeRankOrDirectMinimumFailsClosedAndQuotaProbeCannotBypassStructure() {
        assertThat(policy.evaluate("{\"rankMin\":-1,\"mode\":\"all\",\"enforce\":true}",
                new StorefrontPurchaseGatePolicy.Facts(99, 99, BigDecimal.ZERO)).code())
                .isEqualTo("PURCHASE_GATE_INVALID");
        assertThat(policy.evaluate("{\"activeDirectMin\":-1,\"mode\":\"all\",\"enforce\":true}",
                new StorefrontPurchaseGatePolicy.Facts(99, 99, BigDecimal.ZERO)).code())
                .isEqualTo("PURCHASE_GATE_INVALID");
        assertThat(policy.hasQuota("{\"rankMin\":-1,\"quotaCap\":2,\"quotaSold\":0,\"mode\":\"all\",\"enforce\":true}"))
                .isFalse();
    }

    @Test
    void unsupportedMonthlyQuotaFailsClosedUntilAperiodicCounterExists() {
        String monthGate = "{\"mode\":\"all\",\"enforce\":true,\"quotaCap\":2,"
                + "\"quotaSold\":0,\"quotaPeriod\":\"month\"}";
        assertThat(policy.evaluate(monthGate, new StorefrontPurchaseGatePolicy.Facts(0, 0, BigDecimal.ZERO)).code())
                .isEqualTo("PURCHASE_GATE_INVALID");
        assertThat(policy.hasQuota(monthGate)).isFalse();
    }
}
