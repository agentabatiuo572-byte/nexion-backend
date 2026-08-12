package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppUserAuthEnvironmentNamespaceContractTest {
    @Test
    void allPhoneAuthenticationLookupAndGuardKeysUseTheServerAudience() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/auth/application/AppUserAuthService.java"));

        assertThat(service).contains(".eq(UserEntity::getSandbox, audience == UserAuthEnvironment.SANDBOX ? 1 : 0)");
        assertThat(service).contains("loginKey(countryCode, phone, authNamespace())");
        assertThat(service).contains("namespace + ':' + countryCode + ':' + phone");
    }

    @Test
    void registrationOtpAndIpCountersAreEnvironmentScoped() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/auth/application/AppUserRegistrationService.java"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/auth/mapper/AppUserRegistrationMapper.java"));

        assertThat(service).contains("consumeValidChallengeInEnvironment");
        assertThat(service).contains("countRegisteredAccountsByClientIp24hInEnvironment");
        assertThat(mapper).contains("registration.auth_environment=#{authEnvironment}");
        assertThat(mapper).contains("user_account.sandbox=#{sandbox}");
    }
}
