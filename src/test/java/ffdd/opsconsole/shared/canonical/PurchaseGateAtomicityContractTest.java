package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.shared.canonical.mapper.AppBundleOrderMapper;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class PurchaseGateAtomicityContractTest {
    private static final Path ROOT = Path.of("src/main/java");

    @Test
    void canonicalUserLockOnlyAdmitsActiveUsers() throws Exception {
        String source = Files.readString(ROOT.resolve(
                "ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"));
        assertThat(source).contains("status='ACTIVE'");
        String sandbox = Files.readString(ROOT.resolve(
                "ffdd/opsconsole/commerce/mapper/CommerceAcceptanceSandboxMapper.java"));
        assertThat(sandbox).contains("sandbox=1 AND status='ACTIVE'");
    }

    @Test
    void canonicalAndSandboxQuotaSoldUseLifetimeAtomicGuards() throws Exception {
        String mapper = Files.readString(ROOT.resolve(
                "ffdd/opsconsole/commerce/mapper/CommerceAcceptanceSandboxMapper.java"));
        assertThat(mapper).contains("consumeSandboxPurchaseQuota")
                .contains("JSON_SET")
                .contains("quotaSold")
                .contains("quotaCap")
                .contains("quotaPeriod")
                .contains("c.purchase_gate_json")
                .doesNotContain("JSON_EXTRACT(s.purchase_gate_json,'$.quotaCap')")
                .contains("<=");
        String callback = Files.readString(ROOT.resolve(
                "ffdd/opsconsole/commerce/application/CommerceAcceptanceSandboxService.java"));
        assertThat(callback).contains("PAYMENT_SUCCEEDED")
                .contains("consumeSandboxPurchaseQuota");
        String canonical = Files.readString(ROOT.resolve(
                "ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"));
        assertThat(canonical).contains("consumePurchaseQuota")
                .contains("UPDATE nx_admin_device_sku")
                .contains("JSON_SET")
                .contains("quotaSold")
                .contains("quotaCap")
                .contains("quotaPeriod")
                .contains("<=");
        String bundle = Files.readString(ROOT.resolve(
                "ffdd/opsconsole/shared/canonical/mapper/AppBundleOrderMapper.java"));
        assertThat(bundle).contains("consumePurchaseQuota")
                .contains("UPDATE nx_admin_device_sku")
                .contains("JSON_SET")
                .contains("quotaSold")
                .contains("quotaCap")
                .contains("quotaPeriod")
                .contains("<=");
        assertThat(Files.readString(ROOT.resolve(
                "ffdd/opsconsole/shared/canonical/AppCanonicalBoundaryService.java")))
                .contains("reserveCanonicalPurchaseQuota")
                .contains("consumePurchaseQuota");
        assertThat(Files.readString(ROOT.resolve(
                "ffdd/opsconsole/shared/canonical/AppBundleOrderService.java")))
                .contains("reserveCanonicalPurchaseQuota")
                .contains("consumePurchaseQuota");
    }

    @Test
    void productionGatePolicyIsLockedBeforeEligibilityAndQuotaMutation() throws Exception {
        for (var mapper : java.util.List.of(CanonicalStateMapper.class, AppBundleOrderMapper.class)) {
            String sql = String.join(" ", mapper.getMethod("purchaseGateJson", String.class)
                    .getAnnotation(Select.class).value());
            assertThat(sql).contains("nx_admin_device_sku", "FOR UPDATE");
        }
        String tradein = String.join(" ", AppTradeinMapper.class
                .getMethod("lockPurchaseGateJson", String.class)
                .getAnnotation(Select.class).value());
        assertThat(tradein).contains("nx_admin_device_sku", "FOR UPDATE");
    }

    @Test
    void purchaseFactsEscapesTheMysqlRankKeyword() throws Exception {
        for (String mapper : java.util.List.of(
                "ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java",
                "ffdd/opsconsole/shared/canonical/mapper/AppBundleOrderMapper.java",
                "ffdd/opsconsole/device/mapper/AppTradeinMapper.java",
                "ffdd/opsconsole/commerce/mapper/CommerceAcceptanceSandboxMapper.java")) {
            String source = Files.readString(ROOT.resolve(mapper));
            assertThat(source)
                    .contains("AS `rank`")
                    .doesNotContain("AS rank,")
                    .doesNotContain("UNSIGNED) rank,");
        }
    }

    @Test
    void localSandboxBundleIsAnIsolatedOrderStateMachine() throws Exception {
        String source = Files.readString(ROOT.resolve(
                "ffdd/opsconsole/shared/canonical/AppBundleOrderService.java"));
        assertThat(source).doesNotContain("BUNDLE_CHECKOUT_SANDBOX_UNSUPPORTED")
                .contains("source")
                .contains("SANDBOX")
                .contains("CommerceAcceptanceSandboxMapper");
    }
}
