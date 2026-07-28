package ffdd.opsconsole.team.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppBinaryControllerContractTest {
    @Test
    void exposesAuthenticatedCurrentUserOnlyAndNeverAcceptsAnOwnerParameter() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/team/web/AppBinaryController.java"));

        assertThat(source).contains("@GetMapping(\"/api/team/binary\")");
        assertThat(source).contains("\"USER\".equals", "authentication.getPrincipal()");
        assertThat(source).doesNotContain("@RequestParam", "@PathVariable", "ownerUserId");
    }
}
