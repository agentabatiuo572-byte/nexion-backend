package ffdd.opsconsole.risk.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RiskOpsMapperK1ContractTest {
    @Test
    void k1ReadsIpDevicePaymentAndAccountContextFromAuthoritativeTables() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/risk/mapper/RiskOpsMapper.java"));

        assertThat(source)
                .contains("'ip' AS layer", "nx_user_registration_otp")
                .contains("'device' AS layer", "nx_risk_decision")
                .contains("'payment' AS layer", "nx_wallet_bank_card")
                .contains("sponsor_user_id AS sponsorUserId")
                .contains("nx_referral_reward_settlement")
                .contains("nx_wallet_ledger");
    }

    @Test
    void registrationUsesConsumedOtpIpAndCurrentK1LimitBeforeCreatingAnAccount() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/auth/application/AppUserRegistrationService.java"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/auth/mapper/AppUserRegistrationMapper.java"));

        assertThat(service)
                .contains("consumedChallengeClientIp")
                .contains("maxSignupPerIp24h")
                .contains("countRegisteredAccountsByClientIp24h")
                .contains("USER_REGISTRATION_K1_IP_LIMIT");
        assertThat(mapper)
                .contains("FOR UPDATE")
                .contains("nx_admin_risk_param")
                .contains("countRegisteredAccountsByClientIp24h");
    }
}
