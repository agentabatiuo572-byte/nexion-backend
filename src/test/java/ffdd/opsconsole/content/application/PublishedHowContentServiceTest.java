package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PublishedHowContentServiceTest {
    private static final String DOC = """
        {"version":"2026.08.17.1","status":"PUBLISHED","revision":3,"sourceEnvironment":"PRODUCTION","runId":"","contents":{
          "genesis-how":{"locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Intro","body":"Server text"}]}}},
          "team-binary-how":{"locales":{"en":{"blocks":[{"id":"period","kind":"ruleRef","title":"Period","body":"{value}","ref":{"source":"canonical","key":"team.ui.F.binary.settlePeriod","version":"F3.2026.08.17"}}]}}}
        }}
        """;

    @Test
    void publishesOnlyKnownKeysWithLocaleFallbackAndProvenance() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("how-it-works.published")).thenReturn(Optional.of(DOC));
        var result = new PublishedHowContentService(config, new MockEnvironment(), mock(AuditLogService.class))
                .publicContent("team-binary-how", "zh-CN");
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("contentKey", "team-binary-how").containsEntry("locale", "en").containsEntry("source", "server");
        assertThat(result.getData().get("blocks")).asList().hasSize(1);
    }

    @Test
    void missingDraftOrMalformedCanonicalReferenceFailsClosed() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("how-it-works.published")).thenReturn(Optional.of("{\"version\":\"v1\",\"status\":\"DRAFT\",\"contents\":{}}"));
        var service = new PublishedHowContentService(config, new MockEnvironment(), mock(AuditLogService.class));
        assertThat(service.publicContent("genesis-how", "en").getCode()).isEqualTo(503);
        when(config.activeValue("how-it-works.published")).thenReturn(Optional.of("{\"version\":\"v1\",\"status\":\"PUBLISHED\",\"contents\":{\"genesis-how\":{\"locales\":{\"en\":{\"blocks\":[{\"id\":\"bad\",\"kind\":\"ruleRef\",\"title\":\"x\",\"body\":\"x\"}]}}}}}"));
        assertThat(service.publicContent("genesis-how", "en").getMessage()).isEqualTo("HOW_CONTENT_UNAVAILABLE");
    }

    @Test
    void updateUsesCasAndRejectsUnknownKeys() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValueForUpdate("how-it-works.published")).thenReturn(Optional.of("{\"revision\":2}"));
        var service = new PublishedHowContentService(config, new MockEnvironment(), mock(AuditLogService.class));
        Map<String,Object> onePage = Map.of("locales", Map.of("en", Map.of("blocks", java.util.List.of(Map.of("id","intro","kind","text","title","Intro","body","Text")))));
        Map<String,Object> contents = new java.util.LinkedHashMap<>();
        contents.put("genesis-how", onePage);
        contents.put("wallet-exchange-how", onePage);
        contents.put("wallet-repurchase-how", onePage);
        contents.put("team-binary-how", onePage);
        contents.put("team-commissions-how", onePage);
        contents.put("team-unilevel-how", onePage);
        assertThat(service.update("v2", "PUBLISHED", contents, 1L, "Publish reviewed how content").getCode()).isEqualTo(409);
        assertThat(service.update("v2", "PUBLISHED", Map.of("not-a-page", Map.of()), 2L, "Publish reviewed how content").getCode()).isEqualTo(422);
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void emptyAdminConfigBootstrapsACompleteEditableDocument() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("how-it-works.published")).thenReturn(Optional.of("{}"));

        var result = new PublishedHowContentService(config, new MockEnvironment(), mock(AuditLogService.class)).adminView();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "UNPUBLISHED")
                .containsEntry("version", "")
                .containsEntry("revision", 0)
                .containsEntry("source", "server")
                .containsEntry("configKey", "how-it-works.published");
        assertThat(result.getData().get("contents")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked") Map<String, Object> contents = (Map<String, Object>) result.getData().get("contents");
        assertThat(contents.keySet()).containsExactlyInAnyOrder(
                "genesis-how", "wallet-exchange-how", "wallet-repurchase-how",
                "team-binary-how", "team-commissions-how", "team-unilevel-how");
        contents.values().forEach(entry -> {
            @SuppressWarnings("unchecked") Map<String, Object> content = (Map<String, Object>) entry;
            assertThat(content).containsKey("locales");
            @SuppressWarnings("unchecked") Map<String, Object> locales = (Map<String, Object>) content.get("locales");
            assertThat(locales).containsKey("en");
            assertThat(locales.get("en")).isInstanceOf(Map.class);
        });
    }

    @Test
    void publishedUpdateRequiresAllSixContentKeys() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValueForUpdate("how-it-works.published")).thenReturn(Optional.of("{}"));
        var service = new PublishedHowContentService(config, new MockEnvironment(), mock(AuditLogService.class));
        Map<String, Object> oneKey = Map.of("genesis-how", Map.of("locales", Map.of("en", Map.of(
                "blocks", java.util.List.of(Map.of("id", "intro", "kind", "text", "title", "Intro", "body", "Text"))))));

        assertThat(service.update("v2", "PUBLISHED", oneKey, 0L, "Publish reviewed how content").getCode()).isEqualTo(422);
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
