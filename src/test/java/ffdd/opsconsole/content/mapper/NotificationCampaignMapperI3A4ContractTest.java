package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NotificationCampaignMapperI3A4ContractTest {

    @Test
    void eventFactsAndActionReceiptsStayBoundToTheAuthenticatedOwner() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/content/mapper/NotificationCampaignMapper.java"),
                StandardCharsets.UTF_8);
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260722_i3_a4_event_closure.sql"), StandardCharsets.UTF_8);

        assertThat(source)
                .contains("n.id=#{notificationId} AND n.user_id=#{userId}")
                .contains("n.push_status IN ('DELIVERED','READ','SENT','SUCCESS')")
                .contains("LIMIT 1 FOR UPDATE")
                .contains("AND n.read_flag=0")
                .contains("INSERT IGNORE INTO nx_notification_action_receipt")
                .contains("idempotency_key=#{idempotencyKey}");
        assertThat(migration)
                .contains("UNIQUE KEY uk_notification_action_idem (idempotency_key)")
                .contains("UNIQUE KEY uk_notification_action_business (notification_id,user_id,action)");
    }
}
