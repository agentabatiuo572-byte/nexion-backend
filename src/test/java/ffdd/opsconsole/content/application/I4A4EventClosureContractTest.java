package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class I4A4EventClosureContractTest {

    @Test
    void trustExposureAndGovernanceEventsUseTheGovernedA4Outbox() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/content/application/OpsTrustDisclosureService.java"),
                StandardCharsets.UTF_8);
        String controller = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/content/web/AppTrustSectionController.java"),
                StandardCharsets.UTF_8);
        String eventCenter = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/platform/application/OpsEventCenterService.java"),
                StandardCharsets.UTF_8);
        Path migrationPath = Path.of("scripts/migrations/20260722_i4_a4_event_closure.sql");

        assertThat(service)
                .contains("EventOutboxService")
                .contains("content.trust_section_viewed")
                .contains("admin.trust_content_published")
                .contains("admin.trust_content_archived")
                .contains("admin.trust_content_rolledback");
        assertThat(controller)
                .contains("/{sectionKey}/view")
                .contains("recordSectionView");
        assertThat(eventCenter)
                .contains("content.trust_section_viewed / admin.trust_content_published / archived / rolledback")
                .contains("A4 已注册 · I4")
                .doesNotContain("信任版块曝光(I4)占位中");
        assertThat(migrationPath).exists();

        String migration = Files.readString(migrationPath, StandardCharsets.UTF_8);
        assertThat(migration)
                .contains("'content.trust_section_viewed'", "'admin.trust_content_published'",
                        "'admin.trust_content_archived'", "'admin.trust_content_rolledback'")
                .contains("INSERT INTO nx_event_schema_registry")
                .contains("INSERT INTO nx_event_schema_property")
                .contains("INSERT INTO nx_event_domain_extension")
                .contains("'section_key'", "'from_version'", "'to_version'", "'operator_role'")
                .contains("is_server_authoritative");
    }
}
