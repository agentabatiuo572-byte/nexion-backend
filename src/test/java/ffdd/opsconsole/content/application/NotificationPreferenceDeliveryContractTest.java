package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NotificationPreferenceDeliveryContractTest {
    @Test
    void notificationReadAndDeliverySqlMustApplyPerKindPreferenceWithDefaultEnabled() throws Exception {
        String campaign = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/mapper/NotificationCampaignMapper.java"));
        String nova = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/mapper/NovaSocialRuntimeMapper.java"));
        String payment = Files.readString(Path.of("src/main/java/ffdd/opsconsole/user/mapper/UserPaymentMethodMapper.java"));
        String preference = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/mapper/NotificationPreferenceMapper.java"));
        for (String source : new String[] { campaign, nova, payment }) {
            assertThat(source).contains("nx_user_preference");
            assertThat(source).contains("notify_system");
            assertThat(source).contains("COALESCE");
        }
        assertThat(campaign).contains("notify_commission", "notify_team", "notify_staking", "notify_market", "notify_genesis");
        assertThat(nova).contains("notify_commission", "notify_team", "notify_staking", "notify_market", "notify_genesis");
        assertThat(campaign).contains("notificationIds", "<foreach", "n.id IN", "notify_system");
        assertThat(preference).contains(
                "ON DUPLICATE KEY UPDATE",
                "CASE WHEN #{commission} IS NULL THEN notify_commission ELSE #{commission} END",
                "CASE WHEN #{system} IS NULL THEN notify_system ELSE #{system} END");
    }
}
