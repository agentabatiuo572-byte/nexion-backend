package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PublishedPrivacyPolicyServiceTest {
    @Test
    void publishingRequiresTheDefaultLanguageButDraftsMayRemainIncomplete() {
        InMemoryConfig config = new InMemoryConfig();
        var service = service(config, productionEnvironment());
        var chineseOnly = Map.<String, Object>of("zh", content("Chinese draft"));
        assertThat(service.update("v1", "PUBLISHED", chineseOnly, 0L, "Publish reviewed policy").getCode()).isEqualTo(422);
        assertThat(config.writes).isZero();
        assertThat(service.update("v1", "DRAFT", chineseOnly, 0L, "Save incomplete policy draft").getCode()).isZero();
    }

    @Test
    void draftPreservesThePreviouslyPublishedSnapshotUntilAnExplicitRevoke() {
        InMemoryConfig config = new InMemoryConfig();
        PublishedPrivacyPolicyService service = service(config, productionEnvironment());

        assertThat(service.update("v1", "PUBLISHED", locales("Published body"), 0L,
                "Publish reviewed privacy policy").getCode()).isZero();
        assertThat(service.update("v2", "DRAFT", locales("Unpublished draft body"), 1L,
                "Save reviewed replacement draft").getCode()).isZero();

        var publicResult = service.publicPolicy("en-US");
        assertThat(publicResult.getCode()).isZero();
        assertThat(publicResult.getData()).containsEntry("version", "v1").containsEntry("locale", "en");
        assertThat(((List<?>) publicResult.getData().get("sections")).get(0).toString()).contains("Published body");

        var adminResult = service.adminView();
        assertThat(adminResult.getData()).containsEntry("status", "DRAFT").containsEntry("hasPublishedVersion", true)
                .doesNotContainKey("published");

        assertThat(service.update("v2", "UNPUBLISHED", locales("Unpublished draft body"), 2L,
                "Revoke published privacy policy").getCode()).isZero();
        assertThat(service.publicPolicy("en").getCode()).isEqualTo(503);
        assertThat(service.publicPolicy("en").getMessage()).isEqualTo("PRIVACY_POLICY_UNAVAILABLE");
    }

    @Test
    void resolvesExactThenBaseLocaleAndNeverFallsBackToMalformedPublishedContent() {
        InMemoryConfig config = new InMemoryConfig();
        PublishedPrivacyPolicyService service = service(config, productionEnvironment());
        Map<String, Object> locales = Map.of(
                "en", content("English body"),
                "zh", content("Chinese body"));

        assertThat(service.update("v1", "PUBLISHED", locales, 0L,
                "Publish reviewed bilingual privacy policy").getCode()).isZero();
        var fallback = service.publicPolicy("zh-CN");
        assertThat(fallback.getCode()).isZero();
        assertThat(fallback.getData()).containsEntry("locale", "zh");

        config.values.put("legal.privacy-policy.published", """
                {"version":"v1","status":"PUBLISHED","sourceEnvironment":"PRODUCTION","runId":"",
                 "locales":{"en":{"hero":"Valid hero","sections":[{"id":"one","title":"Title","body":"","order":0}]}}}
                """);
        assertThat(service.publicPolicy("en").getCode()).isEqualTo(503);
    }

    @Test
    void staleRevisionUsesTheLockedReadAndCannotWriteOverTheCurrentDocument() {
        InMemoryConfig config = new InMemoryConfig();
        config.values.put("legal.privacy-policy.published", "{\"revision\":4}");
        PublishedPrivacyPolicyService service = service(config, productionEnvironment());

        var result = service.update("v5", "PUBLISHED", locales("Next body"), 3L,
                "Publish reviewed privacy policy");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("PRIVACY_POLICY_VERSION_CONFLICT");
        assertThat(config.lockedReads).isEqualTo(1);
        assertThat(config.writes).isZero();
    }

    @Test
    void rejectsOversizedMultibytePublicationWithoutReplacingTheCurrentPolicy() {
        InMemoryConfig config = new InMemoryConfig();
        PublishedPrivacyPolicyService service = service(config, productionEnvironment());
        assertThat(service.update("v1", "PUBLISHED", locales("Current published body"), 0L,
                "Publish reviewed privacy policy").getCode()).isZero();
        String before = config.values.get("legal.privacy-policy.published");
        Map<String, Object> oversized = Map.of("en", content("English body"), "zh", Map.of("hero", "隐私政策", "sections", List.of(
                Map.of("id", "one", "title", "收集", "body", "文".repeat(6000), "order", 0),
                Map.of("id", "two", "title", "保留", "body", "文".repeat(6000), "order", 1))));

        var result = service.update("v2", "PUBLISHED", oversized, 1L, "Publish a reviewed replacement policy");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("PRIVACY_POLICY_TOO_LARGE");
        assertThat(config.writes).isEqualTo(1);
        assertThat(config.values.get("legal.privacy-policy.published")).isEqualTo(before);
        assertThat(service.publicPolicy("en").getData()).containsEntry("version", "v1");
    }

    @Test
    void isolatedTestProfileRequiresAndReturnsItsRunScopedProvenance() {
        InMemoryConfig config = new InMemoryConfig();
        MockEnvironment environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "privacy-run-001");
        environment.setActiveProfiles("test");
        PublishedPrivacyPolicyService service = service(config, environment);

        assertThat(service.update("v1", "PUBLISHED", locales("Sandbox body"), 0L,
                "Publish reviewed privacy policy").getCode()).isZero();
        assertThat(service.publicPolicy("en").getData())
                .containsEntry("sourceEnvironment", "SANDBOX").containsEntry("runId", "privacy-run-001");

        MockEnvironment missingRun = new MockEnvironment();
        missingRun.setActiveProfiles("test");
        assertThat(service(config, missingRun).publicPolicy("en").getCode()).isEqualTo(503);
    }

    private PublishedPrivacyPolicyService service(InMemoryConfig config, MockEnvironment environment) {
        return new PublishedPrivacyPolicyService(config, environment, mock(AuditLogService.class));
    }

    private MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private Map<String, Object> locales(String body) { return Map.of("en", content(body)); }

    private Map<String, Object> content(String body) {
        return Map.of("hero", "Privacy overview", "sections", List.of(
                Map.of("id", "collection", "title", "Collection", "body", body, "order", 0)));
    }

    private static final class InMemoryConfig implements PlatformConfigFacade {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private int lockedReads;
        private int writes;

        @Override public Optional<String> activeValue(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public Optional<String> activeValueForUpdate(String key) {
            lockedReads++;
            return activeValue(key);
        }
        @Override public void upsertAdminValue(String key, String value, String type, String group, String remark) {
            writes++;
            values.put(key, value);
        }
    }
}
