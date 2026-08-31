package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.Transactional;

class DevelopmentCommissionHowInitializerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final AuditLogService audit = mock(AuditLogService.class);

    private Map<String, Object> baseline() throws Exception {
        try (var stream = getClass().getResourceAsStream("/policies/commissions-how-2026.08.31.json")) {
            return JSON.readValue(stream, new TypeReference<>() {});
        }
    }

    private Map<String, Object> original() throws Exception {
        var baseline = baseline();
        Map<String, Object> contents = new LinkedHashMap<>();
        for (String key : PublishedHowContentService.CONTENT_KEYS) {
            contents.put(key, Map.of("locales", Map.of("en", Map.of("blocks", List.of(
                    Map.of("id", "intro", "kind", "text", "title", key, "body", "Keep this PC-authored content"))))));
        }
        contents.put("team-commissions-how", baseline.get("previousEntry"));
        return new LinkedHashMap<>(Map.of("version", baseline.get("previousVersion"), "status", "PUBLISHED",
                "revision", 7, "sourceEnvironment", "PRODUCTION", "runId", "", "contents", contents));
    }

    private void run(Map<String, Object> document) throws Exception {
        when(config.activeValueForUpdate(PublishedHowContentService.CONFIG_KEY))
                .thenReturn(Optional.of(JSON.writeValueAsString(document)));
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        new DevelopmentCommissionHowInitializer(config,
                new PublishedHowContentService(config, environment, audit)).run(null);
    }

    @Test
    void upgradesExactBaselineWithCasAndRequiredSystemAuditPreservingOtherFivePages() throws Exception {
        var before = original();
        run(before);
        var serialized = ArgumentCaptor.forClass(String.class);
        verify(config).upsertAdminValue(eq(PublishedHowContentService.CONFIG_KEY), serialized.capture(), eq("JSON"), eq("published_content"), anyString());
        var after = JSON.readTree(serialized.getValue());
        assertThat(after.path("revision").asLong()).isEqualTo(8);
        for (String key : PublishedHowContentService.CONTENT_KEYS) {
            if (!key.equals("team-commissions-how")) assertThat(after.path("contents").path(key)).isEqualTo(JSON.valueToTree(before).path("contents").path(key));
        }
        assertThat(after.path("contents").path("team-commissions-how")).isEqualTo(JSON.valueToTree(baseline().get("entry")));
        var request = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequired(request.capture());
        assertThat(request.getValue().getActorType()).isEqualTo("SYSTEM");
        assertThat(request.getValue().getActorUsername()).isEqualTo("development-baseline");
        assertThat(request.getValue().getAction()).isEqualTo("HOW_CONTENT_PUBLISHED_CHANGED");
    }

    @Test
    void skipsEditedDraftSandboxAndAlreadyUpgradedDocuments() throws Exception {
        for (String mutation : List.of("edited", "draft", "sandbox", "version", "revision")) {
            var doc = original();
            switch (mutation) {
                case "edited" -> {
                    var contents = new LinkedHashMap<>((Map<String, Object>) doc.get("contents"));
                    contents.put("team-commissions-how", Map.of("locales", Map.of()));
                    doc.put("contents", contents);
                }
                case "draft" -> doc.put("status", "DRAFT");
                case "sandbox" -> doc.put("sourceEnvironment", "SANDBOX");
                case "version" -> doc.put("version", "operator-new-version");
                case "revision" -> doc.put("revision", 1.5);
            }
            run(doc);
        }
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void doesNotCreateOrReviveMissingDisabledDeletedOrMalformedContent() {
        var service = mock(PublishedHowContentService.class);
        when(config.activeValueForUpdate(PublishedHowContentService.CONFIG_KEY)).thenReturn(Optional.empty(), Optional.of("not-json"));
        var initializer = new DevelopmentCommissionHowInitializer(config, service);
        initializer.run(null);
        initializer.run(null);
        verifyNoInteractions(service, audit);
        verify(config, never()).insertAdminValueIfMissing(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void publicationAuditFailurePropagatesSoTransactionRollsBack() throws Exception {
        doThrow(new IllegalStateException("AUDIT_UNAVAILABLE")).when(audit).recordRequired(any());
        assertThatThrownBy(() -> run(original())).isInstanceOf(IllegalStateException.class).hasMessage("AUDIT_UNAVAILABLE");
        assertThat(DevelopmentCommissionHowInitializer.class.getMethod("run", org.springframework.boot.ApplicationArguments.class)
                .getAnnotation(Transactional.class).rollbackFor()).contains(Exception.class);
        assertThat(DevelopmentCommissionHowInitializer.class.getAnnotation(Profile.class).value()).containsExactly("dev");
    }

    @Test
    void threeLocalesHaveAllSemanticSlotsAndNeverPublishPrototypeIncomePromises() throws Exception {
        var entry = JSON.valueToTree(baseline().get("entry"));
        var ids = List.of("hero", "overview", "channels", "network", "binary", "peer", "cultivation", "leadership", "genesis",
                "lifecycle", "cooling", "unlocked", "withdrawn", "cooling-note", "example", "example-day", "example-network",
                "example-cultivation", "example-peer", "example-leadership", "example-total", "example-note", "faq",
                "faq-order", "faq-withdraw", "faq-reversal", "faq-cultivation", "footer");
        for (String locale : List.of("zh", "en", "vi")) {
            var blocks = entry.path("locales").path(locale).path("blocks");
            assertThat(blocks.findValuesAsText("id")).containsExactlyElementsOf(ids);
            assertThat(blocks.toString()).contains("{networkRates}", "{binaryRules}", "{coolingDays}", "{leadershipRules}")
                    .doesNotContain("$79", "2,000", "30天", "13%", "10%");
        }
    }
}
