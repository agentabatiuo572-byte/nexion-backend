package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.team.application.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class PublishedContentRevisionRegressionTest {
    private final Map<String, String> stored = new HashMap<>();
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final AuditLogService audit = mock(AuditLogService.class);
    private final PublishedDeveloperDocsService developer = new PublishedDeveloperDocsService(config, environment, audit);
    private final PublishedHowContentService how = new PublishedHowContentService(config, environment, audit);
    private final PublishedRankHowPolicyService rank = new PublishedRankHowPolicyService(config, environment, audit,
            mock(VRankPromotionEngine.class), mock(LeadershipPoolConfigGuard.class));

    PublishedContentRevisionRegressionTest() {
        environment.setActiveProfiles("dev");
        when(config.activeValue(anyString())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
        when(config.activeValueForUpdate(anyString())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
        doAnswer(i -> { stored.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(config).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"developer", "rank"})
    void firstDocumentIsEditableWithoutPublishingAnything(String kind) {
        var view = kind.equals("developer") ? developer.adminView() : rank.adminView();
        assertThat(view.getData()).containsEntry("status", "UNPUBLISHED").containsEntry("version", "")
                .containsKey("revision").containsKey("locales");
        assertThat(read(kind).getCode()).isEqualTo(503);
        assertThat(stored).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"developer", "rank", "how"})
    void draftRetainsPublicRevisionAcrossRepeatedEditsAndRepublish(String kind) {
        assertThat(write(kind, "v1", "PUBLISHED", 0, "Original").getCode()).isZero();
        assertThat(write(kind, "v2", "DRAFT", 1, "Draft").getCode()).isZero();
        assertThat(read(kind).getData()).containsEntry("version", "v1");
        assertThat(write(kind, "v3", "DRAFT", 2, "Next draft").getCode()).isZero();
        assertThat(read(kind).getData()).containsEntry("version", "v1");
        assertThat(write(kind, "v3", "PUBLISHED", 3, "Approved").getCode()).isZero();
        assertThat(read(kind).getData()).containsEntry("version", "v3");
        assertThat(write(kind, "stale", "DRAFT", 3, "Stale").getCode()).isEqualTo(409);
        assertThat(read(kind).getData()).containsEntry("version", "v3");
    }

    @ParameterizedTest @ValueSource(strings = {"developer", "rank", "how"})
    void adminViewKeepsDraftEditableButSignalsTheRetainedPublicVersion(String kind) {
        assertThat(write(kind, "v1", "PUBLISHED", 0, "Original").getCode()).isZero();
        assertThat(write(kind, "v2", "DRAFT", 1, "Draft").getCode()).isZero();
        Map<String, Object> admin = admin(kind).getData();

        assertThat(admin).containsEntry("status", "DRAFT").containsEntry("version", "v2")
                .containsEntry("hasPublishedVersion", true)
                .doesNotContainKey("published");
        assertThat(((Number) admin.get("revision")).longValue()).isEqualTo(2L);
        assertThat(read(kind).getData()).containsEntry("version", "v1");
    }

    @org.junit.jupiter.api.Test
    void partialHowDraftRoundTripsWhilePublicReadStaysOnTheRetainedVersion() {
        assertThat(write("how", "v1", "PUBLISHED", 0, "Published copy").getCode()).isZero();

        Map<String, Object> partialDraft = Map.of("genesis-how", Map.of("locales", Map.of("en", Map.of("blocks",
                List.of(Map.of("id", "draft", "kind", "text", "title", "Draft", "body", "Draft-only copy"))))));
        assertThat(how.update("v2", "DRAFT", partialDraft, 1L, "Save one page as draft").getCode()).isZero();

        Map<String, Object> admin = how.adminView().getData();
        assertThat(admin).containsEntry("status", "DRAFT").containsEntry("version", "v2")
                .containsEntry("hasPublishedVersion", true).doesNotContainKey("published");
        assertThat(((Number) admin.get("revision")).longValue()).isEqualTo(2L);
        assertThat((Map<String, Object>) admin.get("contents")).containsOnlyKeys("genesis-how");

        Map<String, Object> publicContent = how.publicContent("genesis-how", "en").getData();
        assertThat(publicContent).containsEntry("version", "v1");
        assertThat(((List<Map<String, Object>>) publicContent.get("blocks")).get(0))
                .containsEntry("body", "Published copy");
    }

    @ParameterizedTest @ValueSource(strings = {"developer", "rank", "how"})
    void utf8LimitCountsTheRetainedPublishedSnapshotRatherThanOnlyTheIncomingDraft(String kind) {
        String body = retainedSnapshotBoundaryBody(kind);
        assertThat(write(kind, "draft-v1", "DRAFT", 0, body).getCode()).isZero();
        assertThat(stored.values()).allSatisfy(serialized ->
                assertThat(serialized.getBytes(StandardCharsets.UTF_8).length)
                        .isLessThanOrEqualTo(PublishedContentSnapshot.MAX_STORED_BYTES));

        stored.clear();
        assertThat(write(kind, "v1", "PUBLISHED", 0, body).getCode()).isZero();
        var before = new HashMap<>(stored);
        assertThat(write(kind, "v2", "DRAFT", 1, body).getCode()).isEqualTo(422);
        assertThat(stored).isEqualTo(before);
        assertThat(read(kind).getData()).containsEntry("version", "v1");
    }

    private String retainedSnapshotBoundaryBody(String kind) {
        return switch (kind) {
            case "developer" -> "界".repeat(3_000);
            case "rank" -> "界".repeat(7_000);
            default -> "界".repeat(3_000);
        };
    }

    private ApiResult<Map<String, Object>> write(String kind, String version, String status, long revision, String body) {
        Map<String, Object> locale = kind.equals("developer")
                ? Map.of("example", Map.of("request", body, "response", body), "endpoints", List.of(Map.of("method", "GET", "path", "/jobs")), "events", List.of("job.completed"))
                : Map.of("hero", "Rules", "sections", List.of(Map.of("id", "intro", "title", "Intro", "body", body, "order", 0)));
        Map<String, Object> locales = Map.of("en", locale, "zh", locale, "vi", locale);
        if (kind.equals("developer")) return developer.update(version, status, locales, revision, "Reviewed content change");
        if (kind.equals("rank")) return rank.update(version, status, locales, revision, "Reviewed content change");
        Map<String, Object> pages = new LinkedHashMap<>();
        for (String key : PublishedHowContentService.CONTENT_KEYS) pages.put(key, Map.of("locales", Map.of("en", Map.of("blocks",
                List.of(Map.of("id", "intro", "kind", "text", "title", "Intro", "body", body))))));
        return how.update(version, status, pages, revision, "Reviewed content change");
    }
    private ApiResult<Map<String, Object>> read(String kind) {
        return kind.equals("developer") ? developer.publicDocument("en")
                : kind.equals("rank") ? rank.publicPolicy("en") : how.publicContent("genesis-how", "en");
    }

    private ApiResult<Map<String, Object>> admin(String kind) {
        return kind.equals("developer") ? developer.adminView()
                : kind.equals("rank") ? rank.adminView() : how.adminView();
    }
}
