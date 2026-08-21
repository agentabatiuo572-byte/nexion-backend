package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PurchaseGateEnvironmentIsolationContractTest {
    @Test
    void allPurchaseFactMappersScopeTeamVolumeToTheOwnersEnvironment() throws Exception {
        for (String path : new String[] {
                "src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java",
                "src/main/java/ffdd/opsconsole/shared/canonical/mapper/AppBundleOrderMapper.java",
                "src/main/java/ffdd/opsconsole/device/mapper/AppTradeinMapper.java"}) {
            String sql = Files.readString(Path.of(path));
            assertThat(sql).contains("JOIN nx_user member", "member.sandbox=u.sandbox",
                    "member.status='ACTIVE'", "member.is_deleted=0");
        }
    }
}
