package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AdminMfaEnrollmentDisclosureContractTest {
    @Test
    void enrollmentResponseDoesNotExposeASeparatePlainTextSecret() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/auth/application/OpsAdminAuthService.java"));
        assertThat(source).doesNotContain("enrollment ? secret : null");
    }
}
