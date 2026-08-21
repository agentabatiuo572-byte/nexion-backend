package ffdd.opsconsole.developer.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeveloperWebhookDeliveryMapperContractTest {
    @Test
    void terminalTransitionsRetainAtAsNonDueTerminalTimestampForNotNullSchema() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/developer/mapper/AppDeveloperWebhookDeliveryMapper.java"));

        assertThat(source).contains("status='DEAD'", "d.next_retry_at=#{at}");
        assertThat(source).contains("status='NOT_DELIVERED'", "d.next_retry_at=#{at}");
        assertThat(source).doesNotContain("d.next_retry_at=NULL");
        assertThat(source).contains("WHERE status IN ('PENDING','RETRYING') AND next_retry_at<=#{now}");
        assertThat(source).contains("status='RETRYING'", "status='DELIVERING'", "updated_at<=#{cutoff}",
                "DELIVERY_LEASE_EXPIRED");

        String migration = Files.readString(Path.of("scripts/migrations/20260816_developer_api_keys_webhooks.sql"));
        assertThat(migration).contains("next_retry_at DATETIME NOT NULL");
    }

    @Test
    void canonicalOutboxScanMatchesCanonicalNameAndAdvancesByIdCursor() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/shared/outbox/mapper/EventOutboxMapper.java"));

        assertThat(source).contains("LOWER(event_type) = LOWER(#{canonicalType})",
                "LOWER(event_name) = LOWER(#{canonicalType})", "id &gt; #{afterId}",
                "next_retry_at &lt;= NOW()", "ORDER BY id ASC");
    }
}
