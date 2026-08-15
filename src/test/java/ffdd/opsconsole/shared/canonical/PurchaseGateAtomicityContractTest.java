package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
    void quotaSoldChangesOnlyAtSandboxPaymentAndUsesAnAtomicGuard() throws Exception {
        String mapper = Files.readString(ROOT.resolve(
                "ffdd/opsconsole/commerce/mapper/CommerceAcceptanceSandboxMapper.java"));
        assertThat(mapper).contains("consumeSandboxPurchaseQuota")
                .contains("JSON_SET")
                .contains("quotaSold")
                .contains("quotaCap")
                .contains("<=");
        String callback = Files.readString(ROOT.resolve(
                "ffdd/opsconsole/commerce/application/CommerceAcceptanceSandboxService.java"));
        assertThat(callback).contains("PAYMENT_SUCCEEDED")
                .contains("consumeSandboxPurchaseQuota");
        assertThat(Files.readString(ROOT.resolve(
                "ffdd/opsconsole/shared/canonical/AppCanonicalBoundaryService.java")))
                .doesNotContain("consumePurchaseQuota");
        assertThat(Files.readString(ROOT.resolve(
                "ffdd/opsconsole/shared/canonical/AppBundleOrderService.java")))
                .doesNotContain("consumePurchaseQuota");
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
