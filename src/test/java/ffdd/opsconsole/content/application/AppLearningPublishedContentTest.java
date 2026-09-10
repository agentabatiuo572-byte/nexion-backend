package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.I18nMessagePairView;
import ffdd.opsconsole.content.infrastructure.HelpArticleEntity;
import ffdd.opsconsole.content.infrastructure.MybatisI18nLearningRepository;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import ffdd.opsconsole.content.mapper.HelpArticleMapper;
import ffdd.opsconsole.content.mapper.I18nHardcodedFindingMapper;
import ffdd.opsconsole.content.mapper.I18nIntegrityIssueMapper;
import ffdd.opsconsole.content.mapper.I18nMessageMapper;
import ffdd.opsconsole.content.mapper.I18nMessageVersionMapper;
import ffdd.opsconsole.content.mapper.I18nNamespaceMapper;
import ffdd.opsconsole.content.mapper.LearningCourseVersionMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AppLearningPublishedContentTest {
    private static final long USER_ID = 42L;

    @Test
    void appPublishedListAndDetailNeverProjectNewerDraftTranslations() {
        HelpArticleMapper articles = mock(HelpArticleMapper.class);
        AppLearningMapper learningMapper = mock(AppLearningMapper.class);
        MybatisI18nLearningRepository repository = spy(new MybatisI18nLearningRepository(
                mock(I18nNamespaceMapper.class), mock(I18nMessageMapper.class), mock(I18nMessageVersionMapper.class),
                mock(I18nIntegrityIssueMapper.class), mock(I18nHardcodedFindingMapper.class), articles,
                mock(LearningCourseVersionMapper.class), learningMapper));
        LearningAcceptanceSandboxGate sandboxGate = mock(LearningAcceptanceSandboxGate.class);
        AppLearningService service = new AppLearningService(repository, learningMapper,
                mock(TreasuryLedgerPostingFacade.class), mock(EventOutboxService.class),
                mock(EarningsReleaseService.class), mock(AdminIdempotencyService.class), sandboxGate,
                mock(LearningSandboxQuizIdempotencyService.class), "published-content-test");

        when(articles.selectList(any())).thenReturn(List.of(publishedCourse(), draftCourse()));
        when(learningMapper.readRewardEnvironment(USER_ID)).thenReturn("PRODUCTION");
        when(learningMapper.listProgress(USER_ID)).thenReturn(List.of());
        when(learningMapper.sumGrantedReward(USER_ID)).thenReturn(BigDecimal.ZERO);

        I18nMessagePairView publishedTitle = publishedPair("learn.published.title", "PUBLISHED_EN", "PUBLISHED_ZH", "");
        I18nMessagePairView publishedBody = publishedPair("learn.published.body", "", "PUBLISHED_BODY_ZH", "PUBLISHED_BODY_VI");
        I18nMessagePairView draftTitle = draftPair("learn.published.title", "DRAFT_EN", "DRAFT_ZH", "DRAFT_VI");
        I18nMessagePairView draftBody = draftPair("learn.published.body", "DRAFT_BODY_EN", "DRAFT_BODY_ZH", "DRAFT_BODY_VI");
        stubPair(repository, "learn.published.title", draftTitle, publishedTitle);
        stubPair(repository, "learn.published.body", draftBody, publishedBody);
        stubPair(repository, "learn.draft.title", draftPair("learn.draft.title", "DRAFT_ONLY_EN", "DRAFT_ONLY_ZH", "DRAFT_ONLY_VI"), null);
        stubPair(repository, "learn.draft.body", draftPair("learn.draft.body", "DRAFT_ONLY_BODY_EN", "DRAFT_ONLY_BODY_ZH", "DRAFT_ONLY_BODY_VI"), null);

        // PC's non-public lookup deliberately still sees the current draft, proving the fixture has both versions.
        assertThat(repository.findCourse("published")).get().extracting("titleZh", "bodyZh")
                .containsExactly("DRAFT_ZH", "DRAFT_BODY_ZH");

        assertThat(service.overview(USER_ID, "zh").getData().courses()).singleElement()
                .extracting("title", "body").containsExactly("PUBLISHED_ZH", "PUBLISHED_BODY_ZH");
        assertThat(service.overview(USER_ID, "vi").getData().courses()).singleElement()
                .extracting("title", "body").containsExactly("PUBLISHED_ZH", "PUBLISHED_BODY_VI");
        assertThat(service.overview(USER_ID, "en").getData().courses()).singleElement()
                .extracting("title", "body").containsExactly("PUBLISHED_EN", "PUBLISHED_BODY_ZH");

        assertThat(service.course(USER_ID, "published", "zh").getData())
                .extracting("title", "body").containsExactly("PUBLISHED_ZH", "PUBLISHED_BODY_ZH");
        assertThat(service.course(USER_ID, "published", "vi").getData())
                .extracting("title", "body").containsExactly("PUBLISHED_ZH", "PUBLISHED_BODY_VI");
        assertThat(service.course(USER_ID, "published", "en").getData())
                .extracting("title", "body").containsExactly("PUBLISHED_EN", "PUBLISHED_BODY_ZH");

        assertThat(service.course(USER_ID, "draft", "zh").getCode()).isEqualTo(404);
    }

    private static void stubPair(
            MybatisI18nLearningRepository repository,
            String key,
            I18nMessagePairView latest,
            I18nMessagePairView published) {
        doReturn(Optional.of(latest)).when(repository).findMessagePair(key);
        doReturn(published == null ? Optional.empty() : Optional.of(published))
                .when(repository).findPublishedMessagePair(key);
    }

    private static I18nMessagePairView publishedPair(String key, String en, String zh, String vi) {
        return new I18nMessagePairView(key, "learn", en, zh, vi, "published", "v1", List.of());
    }

    private static I18nMessagePairView draftPair(String key, String en, String zh, String vi) {
        return new I18nMessagePairView(key, "learn", en, zh, vi, "draft", "v2", List.of());
    }

    private static HelpArticleEntity publishedCourse() {
        return course("learn.course.published", 1, "ENTITY_TITLE", "ENTITY_BODY", 10);
    }

    private static HelpArticleEntity draftCourse() {
        return course("learn.course.draft", 0, "DRAFT_ENTITY_TITLE", "DRAFT_ENTITY_BODY", 20);
    }

    private static HelpArticleEntity course(String code, int status, String title, String body, int sortOrder) {
        HelpArticleEntity entity = new HelpArticleEntity();
        entity.setArticleCode(code);
        entity.setStatus(status);
        entity.setTitle(title);
        entity.setContent(body);
        entity.setCategory("basics");
        entity.setFormat("article");
        entity.setLevel("beginner");
        entity.setDurationMin(5);
        entity.setRewardNex(BigDecimal.TEN);
        entity.setSortOrder(sortOrder);
        entity.setVersionNo(1);
        entity.setRevision(1L);
        entity.setIsDeleted(0);
        return entity;
    }
}
