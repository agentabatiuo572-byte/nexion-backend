package ffdd.opsconsole.commerce.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CommerceAcceptanceSandboxControllerContractTest {
    @Test
    void callbackRequiresAdminWriteAuthorityAndCarriesAnAuditableReasonAndActor() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/web/CommerceAcceptanceSandboxController.java"));
        assertThat(source).contains("@PreAuthorize(\"hasAuthority('device_e4_write')\")",
                "request.reason()", "AdminActorResolver.resolve(null)",
                "String reason");
    }
}
