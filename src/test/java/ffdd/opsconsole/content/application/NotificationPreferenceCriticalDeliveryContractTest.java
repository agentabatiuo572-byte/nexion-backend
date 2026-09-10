package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NotificationPreferenceCriticalDeliveryContractTest {
    @Test
    void criticalNotificationsBypassOnlyTheUserMuteFilterAcrossTheirLifecycle() throws Exception {
        String campaign = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/mapper/NotificationCampaignMapper.java"));
        String nova = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/mapper/NovaSocialRuntimeMapper.java"));

        assertThat(method(campaign, "int insertDisclosureReackNotifications"))
                .contains("'SYSTEM', 'critical'")
                .doesNotContain("COALESCE(pref.notify_system, 1) = 1");
        assertThat(method(campaign, "int insertCampaignNotifications"))
                .contains("LOWER(#{priority}) = 'critical'")
                .contains("ELSE pref.notify_system END, 1) = 1");

        for (String signature : new String[] {
                "int markCampaignNotificationsDelivered",
                "int countNotificationsByBizNo",
                "List<NotificationEventFact> selectNotificationEventFactsByBizNo",
                "List<AppNotificationView> selectUserNotifications",
                "long countUnreadForUser",
                "NotificationEventFact lockNotificationEventFact",
                "List<NotificationEventFact> lockUnreadNotificationEventFacts",
                "int markAllUserNotificationsRead" }) {
            assertThat(method(campaign, signature))
                    .contains("LOWER(COALESCE(n.priority, '')) = 'critical'")
                    .contains("ELSE pref.notify_system END, 1) = 1");
        }
        for (String signature : new String[] { "int markNotificationsDelivered", "List<NotificationEventFact> notificationFacts" }) {
            assertThat(method(nova, signature))
                    .contains("LOWER(COALESCE(n.priority, '')) = 'critical'")
                    .contains("ELSE pref.notify_system END, 1) = 1");
        }
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).as(signature).isGreaterThanOrEqualTo(0);
        int annotationStart = source.lastIndexOf("    @", start);
        int next = source.indexOf("\n    @", start + signature.length());
        return source.substring(annotationStart, next < 0 ? source.length() : next);
    }
}
