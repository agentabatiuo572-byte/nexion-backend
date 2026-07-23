package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SecurityConfigOpenApiContractTest {
    @Test
    void signedPaymentIngressIsExactPostAllowlistNotOpenApiWildcard() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/shared/security/SecurityConfig.java"));

        assertThat(source).doesNotContain("/openapi/v1/**");
        assertThat(source).contains(
                ".requestMatchers(HttpMethod.POST,",
                "\"/openapi/v1/topups/card/admission\"",
                "\"/openapi/v1/topups/card/settlements\"",
                "\"/openapi/v1/topups/card/failures\"",
                "\"/openapi/v1/topups/card/chargebacks\"",
                "\"/openapi/v1/topups/provider-statements\"");
    }
}
