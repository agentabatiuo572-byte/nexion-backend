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

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    @Test
    void publishesOnlyKnownKeysWithLocaleFallbackAndProvenance() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("how-it-works.published")).thenReturn(Optional.of(DOC));
        var result = new PublishedHowContentService(config, productionEnvironment(), mock(AuditLogService.class))
                .publicContent("team-binary-how", "zh-CN");
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("contentKey", "team-binary-how").containsEntry("locale", "en").containsEntry("source", "server");
        assertThat(result.getData().get("blocks")).asList().hasSize(1);
    }

    @Test
    void unknownRuntimeProfileCannotReadPublishedProductionHowContent() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("how-it-works.published")).thenReturn(Optional.of(DOC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");

        var result = new PublishedHowContentService(config, environment, mock(AuditLogService.class))
                .publicContent("genesis-how", "en");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("HOW_CONTENT_UNAVAILABLE");
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

    /**
     * 简报 #49:六个 contentKey 共用文档级 version,导致 genesis/复投/兑换 都显示
     * commissions-guide。每个 contentKey 必须能展示自己的发布修订。
     */
    @Test
    void eachContentKeyReportsItsOwnPublishedRevisionInsteadOfTheSharedDocumentVersion() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        String document = """
            {"version":"2026.08.31-commissions-guide","status":"PUBLISHED","revision":14,"sourceEnvironment":"PRODUCTION","runId":"","contents":{
              "genesis-how":{"version":"2026.09.01-genesis-guide","locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Genesis","body":"Genesis text"}]}}},
              "wallet-exchange-how":{"version":"2026.09.02-exchange-guide","locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Exchange","body":"Exchange text"}]}}},
              "wallet-repurchase-how":{"locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Repurchase","body":"Repurchase text"}]}}},
              "team-binary-how":{"locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Binary","body":"Binary text"}]}}},
              "team-commissions-how":{"locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Commissions","body":"Commissions text"}]}}},
              "team-unilevel-how":{"locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Unilevel","body":"Unilevel text"}]}}}
            }}
            """;
        when(config.activeValue("how-it-works.published")).thenReturn(Optional.of(document));
        var service = new PublishedHowContentService(config, productionEnvironment(), mock(AuditLogService.class));

        assertThat(service.publicContent("genesis-how", "en").getData())
                .containsEntry("version", "2026.09.01-genesis-guide");
        assertThat(service.publicContent("wallet-exchange-how", "en").getData())
                .containsEntry("version", "2026.09.02-exchange-guide");
        // 未单独声明修订的页面退回文档版本,而不是凭空编造。
        assertThat(service.publicContent("wallet-repurchase-how", "en").getData())
                .containsEntry("version", "2026.08.31-commissions-guide");
        assertThat(service.publicContent("team-commissions-how", "en").getData())
                .containsEntry("version", "2026.08.31-commissions-guide");
    }

    /**
     * 简报 #49:条目未声明自己的修订时,读侧必须**如实标注**版本来自文档级兜底,
     * 而不是让 genesis/复投/兑换 三页都静默显示同一个「commissions-guide」——
     * 运营与用户要能判断自己看的是本页专属修订,还是整个文档的共用版本。
     */
    @Test
    void entryWithoutOwnRevisionReportsTheDocumentFallbackExplicitly() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        String document = """
            {"version":"2026.08.31-commissions-guide","status":"PUBLISHED","revision":14,"sourceEnvironment":"PRODUCTION","runId":"","contents":{
              "genesis-how":{"version":"2026.09.01-genesis-guide","locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Genesis","body":"Genesis text"}]}}},
              "wallet-exchange-how":{"locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Exchange","body":"Exchange text"}]}}},
              "wallet-repurchase-how":{"locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Repurchase","body":"Repurchase text"}]}}},
              "team-binary-how":{"locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Binary","body":"Binary text"}]}}},
              "team-commissions-how":{"locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Commissions","body":"Commissions text"}]}}},
              "team-unilevel-how":{"locales":{"en":{"blocks":[{"id":"intro","kind":"text","title":"Unilevel","body":"Unilevel text"}]}}}
            }}
            """;
        when(config.activeValue("how-it-works.published")).thenReturn(Optional.of(document));
        var service = new PublishedHowContentService(config, productionEnvironment(), mock(AuditLogService.class));

        // 声明了本页修订:用本页的,并标注来源为条目。
        assertThat(service.publicContent("genesis-how", "en").getData())
                .containsEntry("version", "2026.09.01-genesis-guide")
                .containsEntry("versionSource", "ENTRY");
        // 未声明:退回文档版本,但**必须**标出来源,不能让人误以为是本页专属修订。
        assertThat(service.publicContent("wallet-exchange-how", "en").getData())
                .containsEntry("version", "2026.08.31-commissions-guide")
                .containsEntry("versionSource", "DOCUMENT_FALLBACK");
        assertThat(service.publicContent("wallet-repurchase-how", "en").getData())
                .containsEntry("versionSource", "DOCUMENT_FALLBACK");
    }

    /** 条目级 version 必须是有界非空文本;非法值整体拒绝,不落库。 */
    @Test
    void entryVersionRejectsBlankOrOverlongValuesWithoutPersisting() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValueForUpdate("how-it-works.published")).thenReturn(Optional.of("{}"));
        var service = new PublishedHowContentService(config, new MockEnvironment(), mock(AuditLogService.class));
        Map<String, Object> blocks = Map.of("locales", Map.of("en", Map.of(
                "blocks", java.util.List.of(Map.of("id", "intro", "kind", "text", "title", "Intro", "body", "Text")))));
        for (String bad : new String[]{"", "   ", "v".repeat(65)}) {
            Map<String, Object> contents = new java.util.LinkedHashMap<>();
            for (String key : java.util.List.of("genesis-how", "wallet-exchange-how", "wallet-repurchase-how",
                    "team-binary-how", "team-commissions-how", "team-unilevel-how")) {
                Map<String, Object> entry = new java.util.LinkedHashMap<>(blocks);
                if ("genesis-how".equals(key)) entry.put("version", bad);
                contents.put(key, entry);
            }
            assertThat(service.update("v2", "PUBLISHED", contents, 0L, "Publish reviewed how content").getCode())
                    .isEqualTo(422);
        }
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
