package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SseUrlTokenRemovalContractTest {

    @Test
    void securityChainDoesNotPromoteQueryTokensIntoAuthorizationHeaders() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/shared/security/SecurityConfig.java"));

        assertThat(source)
                .doesNotContain("SseTokenShimFilter")
                .doesNotContain("request.getParameter(\"token\")");
    }
}
