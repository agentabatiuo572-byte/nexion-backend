package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class I1A4EventClosureContractTest {

    @Test
    void exposureAndPaidConversionArePublishedThroughTheGovernedA4Outbox() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/content/application/AppCopyExperimentService.java"),
                StandardCharsets.UTF_8);
        String eventCenter = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/platform/application/OpsEventCenterService.java"),
                StandardCharsets.UTF_8);
        Path migrationPath = Path.of("scripts/migrations/20260722_i1_a4_event_closure.sql");

        assertThat(service)
                .contains("EventOutboxService")
                .contains("content.variant_exposed")
                .contains("content.variant_converted")
                .contains("markExposedIfFirst")
                .contains("insertConversionIfAbsent");
        assertThat(eventCenter)
                .contains("content.variant_exposed / converted", "A4 已注册")
                .doesNotContain("content.variant_exposed / converted\", \"文案 A/B 曝光与转化(I1/I6)占位中");
        assertThat(migrationPath).exists();
        String migration = Files.readString(migrationPath, StandardCharsets.UTF_8);
        assertThat(migration)
                .contains("'content.variant_exposed'", "'content.variant_converted'")
                .contains("INSERT INTO nx_event_schema_registry")
                .contains("INSERT INTO nx_event_schema_property")
                .contains("INSERT INTO nx_event_domain_extension")
                .contains("is_server_authoritative");
    }

    @Test
    void everyPublishedI1CopyIsMaterializedIntoTheI6ThreeLanguageCatalog() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/content/application/OpsCopyAbService.java"),
                StandardCharsets.UTF_8);
        String migration = Files.readString(
                Path.of("scripts/migrations/20260722_i1_a4_event_closure.sql"),
                StandardCharsets.UTF_8);

        assertThat(service)
                .contains("I18nLearningRepository")
                .contains("syncPublishedI18n(updated)")
                .contains("syncPublishedI18n(created)")
                .contains("saveMessagePair(")
                .contains("\"published\"");
        assertThat(migration)
                .contains("I1/I6 closure")
                .contains("INSERT INTO nx_i18n_message")
                .contains("'zh-CN'", "'en-US'", "'vi-VN'")
                .contains("INSERT INTO nx_i18n_message_version")
                .contains("nx_content_copy_version");
    }
}
