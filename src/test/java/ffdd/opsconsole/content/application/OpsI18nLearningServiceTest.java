package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.I18nHardcodedFindingView;
import ffdd.opsconsole.content.domain.I18nIntegrityIssueView;
import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.domain.I18nMessagePairView;
import ffdd.opsconsole.content.domain.I18nNamespaceView;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.domain.LearningCourseVersionView;
import ffdd.opsconsole.content.dto.I18nActionRequest;
import ffdd.opsconsole.content.dto.I18nIntegrityFixRequest;
import ffdd.opsconsole.content.dto.I18nLocalizedCopyRequest;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.dto.LearningFeaturedUpdateRequest;
import ffdd.opsconsole.content.dto.LearningQuizQuestionRequest;
import ffdd.opsconsole.content.dto.LearningRewardUpdateRequest;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsI18nLearningServiceTest {
    private final FakeI18nLearningRepository repository = new FakeI18nLearningRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final TreasuryCoverageFacade coverageFacade = () -> new TreasuryCoverageSnapshot(new BigDecimal("128.4"), new BigDecimal("100"));
    private final AuditObjectLockMapper lockMapper = mock(AuditObjectLockMapper.class);
    private final OpsI18nLearningService service = new OpsI18nLearningService(
            repository,
            auditLogService,
            coverageFacade,
            Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC")),
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            lockMapper);

    @BeforeEach
    void stubLockGuard() {
        // A2 锁守卫默认放行:countActiveByTarget=0 表示无活跃锁,updateCourseReward 直通
        when(lockMapper.countActiveByTarget(anyString(), anyString(), anyString())).thenReturn(0);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedActorOverridesSpoofedRequestOperator() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("1", null, List.of());
        authentication.setDetails(Map.of("username", "superadmin", "subjectType", "ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        service.saveLocalizedDraft("milestones.earnCross", "idem-i6-authenticated-actor",
                new I18nLocalizedCopyRequest("中文 {amount}", "English {amount}", "Vietnamese {amount}",
                        "spoofed-attacker", "验证认证主体覆盖客户端操作者"));

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getActorUsername()).isEqualTo("superadmin");
    }

    @Test
    void overviewUsesBackendRecordsAndNoActivePremiumNamespace() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().namespaces()).extracting(I18nNamespaceView::ns).doesNotContain("premium");
        assertThat(result.getData().sources()).contains("nx_i18n_message", "nx_help_article");
        assertThat(result.getData().stats().integrityIssues()).isEqualTo(10);
        assertThat(repository.seedCalls).isZero();
    }

    @Test
    void saveLocalizedDraftRequiresIdempotency() {
        var result = service.saveLocalizedDraft("milestones.earnCross", null, copyRequest("完成 {amount} 并获得 {nex}", "Earn {nex} after {amount}"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void saveLocalizedDraftRejectsPlaceholderMismatch() {
        var result = service.saveLocalizedDraft("milestones.earnCross", "idem-i6-draft", copyRequest("完成 {amount}", "Earn {nex}"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("I18N_PLACEHOLDERS_MISMATCH");
    }

    @Test
    void publishLocalizedMessagePersistsAndAudits() {
        var saved = service.saveLocalizedDraft("milestones.earnCross", "idem-i6-draft-before-pub", copyRequest("完成 {amount} 并获得 {nex}", "Earn {nex} after {amount}"));
        var result = service.publishLocalizedMessage("milestones.earnCross", "idem-i6-pub",
                publishRequest("完成 {amount} 并获得 {nex}", "Earn {nex} after {amount}", saved.getData().version()));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("published");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, times(2)).recordRequired(captor.capture());
        assertThat(captor.getAllValues().get(1).getAction()).isEqualTo("I6_I18N_MESSAGE_PUBLISHED");
    }

    @Test
    void publishRejectsMissingOrChangedDraftWithoutMutation() {
        var missing = service.publishLocalizedMessage("new.message", "idem-i6-missing",
                publishRequest("中文", "English", "v1"));
        assertThat(missing.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());

        var saved = service.saveLocalizedDraft("milestones.earnCross", "idem-i6-save-conflict", copyRequest("中文 {amount}", "English {amount}"));
        var changed = service.publishLocalizedMessage("milestones.earnCross", "idem-i6-content-conflict",
                publishRequest("已变更 {amount}", "Changed {amount}", saved.getData().version()));
        assertThat(changed.getCode()).isEqualTo(409);
        assertThat(repository.findDraftMessagePair("milestones.earnCross")).get().extracting(I18nMessagePairView::zh).isEqualTo("中文 {amount}");
    }

    @Test
    void archiveOnlyArchivesPublishedAndKeepsNewerDraft() {
        service.saveLocalizedDraft("milestones.earnCross", "idem-i6-newer-draft", copyRequest("草稿 {amount} {nex}", "Draft {amount} {nex}"));
        var result = service.archiveLocalizedMessage("milestones.earnCross", "idem-i6-archive", new I18nActionRequest("Marina K.", "归档当前发布词条"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v4");
        assertThat(repository.findDraftMessagePair("milestones.earnCross")).isPresent();
        assertThat(repository.findPublishedMessagePair("milestones.earnCross")).isEmpty();
    }

    @Test
    void fixIntegrityMarksIssueFixed() {
        var result = service.fixIntegrity("missing-zh", "idem-i6-fix", new I18nIntegrityFixRequest(
                "milestones.earnCross",
                "补齐 {amount}",
                "Repair {amount}",
                "Sửa {amount}",
                "Marina K.",
                "修复缺失镜像并记录审计"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("fixed");
    }

    @Test
    void rewardIncreaseBelowRedlineReturns422() {
        OpsI18nLearningService redlineService = new OpsI18nLearningService(
                repository,
                auditLogService,
                () -> new TreasuryCoverageSnapshot(new BigDecimal("88"), new BigDecimal("100")),
                Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC")),
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                lockMapper);

        var result = redlineService.updateCourseReward("what-is-nexion", "idem-i7-reward", new LearningRewardUpdateRequest(
                new BigDecimal("40"),
                "Marina K.",
                "提高课程奖励并记录审计"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
    }

    @Test
    void rollbackRewardVersionBelowRedlineIsRejectedWithoutActivation() {
        I18nLearningRepository versionRepository = mock(I18nLearningRepository.class);
        LearningCourseView live = new LearningCourseView(
                "course-redline", "当前课程", "Basics", "Article", "Beginner", new BigDecimal("5"),
                false, "5 min", "v3", "published", "当前正文");
        LearningCourseUpsertRequest rollbackPayload = completeCourseRequest("draft");
        LearningCourseVersionView target = new LearningCourseVersionView(
                "course-redline", "v2", "SUPERSEDED", rollbackPayload, 1L,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 2, 0, 0));
        when(versionRepository.findCourse("course-redline")).thenReturn(Optional.of(live));
        when(versionRepository.findCourseVersion("course-redline", "v2")).thenReturn(Optional.of(target));
        OpsI18nLearningService redlineService = new OpsI18nLearningService(
                versionRepository,
                auditLogService,
                () -> new TreasuryCoverageSnapshot(new BigDecimal("88"), new BigDecimal("100")),
                Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC")),
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                lockMapper);

        var result = redlineService.rollbackCourseVersion(
                "course-redline", "v2", "idem-i7-rollback-redline",
                new I18nActionRequest("Marina K.", "回滚课程版本并校验资金红线"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        verify(versionRepository, never()).activateCourseVersion(
                anyString(), anyString(), anyString(), anyString(), anyLong(), any(LocalDateTime.class));
        ArgumentCaptor<AuditLogWriteRequest> auditCaptor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getResult()).isEqualTo("REJECTED");
        assertThat(((java.util.Map<?, ?>) auditCaptor.getValue().getDetail()).get("coverageRatio"))
                .isEqualTo(new BigDecimal("88"));
        assertThat(((java.util.Map<?, ?>) auditCaptor.getValue().getDetail()).get("redlinePct"))
                .isEqualTo(new BigDecimal("100"));
    }

    @Test
    void rollbackLowerRewardVersionBelowRedlineIsAllowed() {
        I18nLearningRepository versionRepository = mock(I18nLearningRepository.class);
        LearningCourseView live = new LearningCourseView(
                "course-safe-rollback", "当前课程", "Basics", "Article", "Beginner", new BigDecimal("20"),
                false, "5 min", "v3", "published", "当前正文");
        LearningCourseUpsertRequest rollbackPayload = completeCourseRequest("draft", new BigDecimal("5"));
        LearningCourseVersionView target = new LearningCourseVersionView(
                "course-safe-rollback", "v2", "SUPERSEDED", rollbackPayload, 1L,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 2, 0, 0));
        LearningCourseView rolledBack = new LearningCourseView(
                "course-safe-rollback", "历史课程", "Basics", "Article", "Beginner", new BigDecimal("5"),
                false, "5 min", "v2", "published", "历史正文");
        when(versionRepository.findCourse("course-safe-rollback")).thenReturn(Optional.of(live));
        when(versionRepository.findCourseVersion("course-safe-rollback", "v2")).thenReturn(Optional.of(target));
        when(versionRepository.activateCourseVersion(
                eq("course-safe-rollback"), eq("v2"), eq("SUPERSEDED"), eq("v3"), eq(0L), any(LocalDateTime.class)))
                .thenReturn(rolledBack);
        OpsI18nLearningService redlineService = new OpsI18nLearningService(
                versionRepository, auditLogService,
                () -> new TreasuryCoverageSnapshot(new BigDecimal("88"), new BigDecimal("100")),
                Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC")),
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(), lockMapper);

        var result = redlineService.rollbackCourseVersion(
                "course-safe-rollback", "v2", "idem-i7-safe-rollback",
                new I18nActionRequest("Marina K.", "降低课程奖励并安全回滚版本"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v2");
        assertThat(result.getData().rewardNex()).isEqualByComparingTo("5");
    }

    @Test
    void rollbackRejectsWhenCurrentVersionChangesBeforeActivation() {
        I18nLearningRepository versionRepository = mock(I18nLearningRepository.class);
        LearningCourseView live = new LearningCourseView(
                "course-concurrent", "当前课程", "Basics", "Article", "Beginner", new BigDecimal("20"),
                false, "5 min", "v3", "published", "当前正文");
        LearningCourseVersionView target = new LearningCourseVersionView(
                "course-concurrent", "v2", "SUPERSEDED", completeCourseRequest("draft", new BigDecimal("5")), 1L,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 2, 0, 0));
        when(versionRepository.findCourse("course-concurrent")).thenReturn(Optional.of(live));
        when(versionRepository.findCourseVersion("course-concurrent", "v2")).thenReturn(Optional.of(target));
        when(versionRepository.activateCourseVersion(
                eq("course-concurrent"), eq("v2"), eq("SUPERSEDED"), eq("v3"), eq(0L), any(LocalDateTime.class)))
                .thenThrow(new IllegalStateException("LEARNING_COURSE_CURRENT_VERSION_CONFLICT"));
        OpsI18nLearningService concurrentService = new OpsI18nLearningService(
                versionRepository, auditLogService, coverageFacade,
                Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC")),
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(), lockMapper);

        var result = concurrentService.rollbackCourseVersion(
                "course-concurrent", "v2", "idem-i7-concurrent-rollback",
                new I18nActionRequest("Marina K.", "并发回滚课程版本状态校验"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("LEARNING_COURSE_CURRENT_VERSION_CONFLICT");
        verify(auditLogService, never()).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void updateCourseRewardBlockedByA2ObjectLockReturns409() {
        when(lockMapper.countActiveByTarget("I", "learning_course", "what-is-nexion")).thenReturn(1);

        var result = service.updateCourseReward("what-is-nexion", "idem-i7-cap-locked", new LearningRewardUpdateRequest(
                new BigDecimal("25"),
                "Marina K.",
                "锁定课程调奖励并记录审计"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("OBJECT_LOCKED_BY_A2");
    }

    @Test
    void updateCourseRewardPassesThroughWhenNoLock() {
        // 默认无锁(countActiveByTarget=0),课程直通并写入(coverage 高于红线,无放大方向不触发 422)
        var result = service.updateCourseReward("what-is-nexion", "idem-i7-reward-nolock", new LearningRewardUpdateRequest(
                new BigDecimal("15"),
                "Marina K.",
                "无锁直通调奖励并记录审计"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().rewardNex()).isEqualByComparingTo("15");
    }

    @Test
    void courseLifecycleCreatesPublishesArchivesAndRejectsRepeatedArchive() {
        var created = service.createCourse("new-compute-guide", "idem-i7-create", completeCourseRequest("draft"));
        assertThat(created.getCode()).isZero();
        assertThat(created.getData().status()).isEqualTo("draft");
        assertThat(created.getData().titleVi()).isEqualTo("Hướng dẫn điện toán mới");
        assertThat(created.getData().bodyVi()).isEqualTo("Tìm hiểu phần thưởng thiết bị {amount}");

        var published = service.publishCourse("new-compute-guide", "idem-i7-publish", new I18nActionRequest("Marina K.", "发布课程新版并记录审计"));
        assertThat(published.getCode()).isZero();
        assertThat(published.getData().status()).isEqualTo("published");

        var archived = service.archiveCourse("new-compute-guide", "idem-i7-archive", new I18nActionRequest("Marina K.", "下架课程归档并记录审计"));
        assertThat(archived.getCode()).isZero();
        assertThat(archived.getData().status()).isEqualTo("archived");

        var repeated = service.archiveCourse("new-compute-guide", "idem-i7-archive-2", new I18nActionRequest("Marina K.", "重复下架课程并记录审计"));
        assertThat(repeated.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void createCourseRejectsDirectPublishedState() {
        var result = service.createCourse("direct-published", "idem-i7-direct", completeCourseRequest("published"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("LEARNING_COURSE_MUST_START_AS_DRAFT");
    }

    @Test
    void publishRejectsDraftWithoutStructuredQuiz() {
        var created = service.createCourse("quiz-missing", "idem-i7-quiz-create", newCourseRequest("draft"));
        assertThat(created.getCode()).isZero();

        var result = service.publishCourse("quiz-missing", "idem-i7-quiz-publish", new I18nActionRequest("Marina K.", "发布缺少测验课程"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("LEARNING_COURSE_QUIZ_INCOMPLETE");
    }

    @Test
    void draftCanBeUpdatedAndDeletedThroughRealCrud() {
        service.createCourse("crud-draft", "idem-i7-crud-create", newCourseRequest("draft"));

        var updated = service.updateCourseDraft("crud-draft", "idem-i7-crud-update", completeCourseRequest("draft"));
        assertThat(updated.getCode()).isZero();
        assertThat(updated.getData().quizQuestions()).hasSize(1);

        var deleted = service.deleteCourseDraft("crud-draft", "idem-i7-crud-delete", new I18nActionRequest("Marina K.", "删除未发布课程草稿"));
        assertThat(deleted.getCode()).isZero();
        assertThat(repository.findCourse("crud-draft")).isEmpty();
    }

    @Test
    void featuredCourseMustBePublished() {
        service.createCourse("draft-only", "idem-i7-create-2", newCourseRequest("draft"));

        var result = service.updateFeaturedCourse("idem-i7-featured", new LearningFeaturedUpdateRequest(
                "draft-only",
                "Marina K.",
                "切换推荐位课程并记录审计"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    private static I18nLocalizedCopyRequest copyRequest(String zh, String en) {
        return new I18nLocalizedCopyRequest(zh, en, en, "Marina K.", "同步三语词条并记录审计");
    }

    private static I18nLocalizedCopyRequest publishRequest(String zh, String en, String version) {
        return new I18nLocalizedCopyRequest(zh, en, en, version, "Marina K.", "发布三语词条并记录审计");
    }

    private static LearningCourseUpsertRequest newCourseRequest(String status) {
        return new LearningCourseUpsertRequest(
                "新计算指南",
                "New compute guide",
                "了解设备收益 {amount}",
                "Understand compute reward {amount}",
                "Basics",
                "Article",
                "Beginner",
                new BigDecimal("20"),
                "6 min",
                status,
                "Marina K.",
                "新建课程草稿并记录审计");
    }

    private static LearningCourseUpsertRequest completeCourseRequest(String status) {
        return completeCourseRequest(status, new BigDecimal("20"));
    }

    private static LearningCourseUpsertRequest completeCourseRequest(String status, BigDecimal rewardNex) {
        return new LearningCourseUpsertRequest(
                "新计算指南", "New compute guide", "了解设备收益 {amount}", "Understand compute reward {amount}",
                "Basics", "Article", "Beginner", rewardNex, "6 min", status,
                "Marina K.", "维护完整课程草稿",
                List.of(new LearningQuizQuestionRequest(
                        "q1", "设备收益来自哪里?", "Where do device earnings come from?",
                        List.of("设备算力任务", "手工充值"), List.of("Compute jobs", "Manual deposit"), 0)),
                60, 3, "通过全部必答题", "quiz.passed", null,
                "Hướng dẫn điện toán mới", "Tìm hiểu phần thưởng thiết bị {amount}");
    }

    private static final class FakeI18nLearningRepository implements I18nLearningRepository {
        private final List<I18nNamespaceView> namespaces = List.of(
                new I18nNamespaceView("home", 128, 100, "-", "06-09"),
                new I18nNamespaceView("marketing", 64, 95, "多版 x3", "06-05"),
                new I18nNamespaceView("learn", 36, 100, "-", "06-01"));
        private final List<I18nIntegrityIssueView> issues = new ArrayList<>(List.of(
                new I18nIntegrityIssueView("missing-zh", "缺镜像 (zh)", 3, List.of("marketing.referral.tagline"), "open"),
                new I18nIntegrityIssueView("missing-en", "缺镜像 (en)", 1, List.of("genesis.dividendNote"), "open"),
                new I18nIntegrityIssueView("placeholder", "占位符不匹配", 2, List.of("milestones.earnCross"), "open"),
                new I18nIntegrityIssueView("hardcoded", "疑似硬编码", 4, List.of("wallet empty state"), "open")));
        private final List<I18nHardcodedFindingView> findings = List.of(
                new I18nHardcodedFindingView("wallet 空态", "\"No transactions yet\"", "wallet.emptyState", "open"));
        private int seedCalls;
        private final List<LearningCourseView> courses = new ArrayList<>(List.of(new LearningCourseView(
                "what-is-nexion",
                "What is Nexion",
                "Basics",
                "Article",
                "Beginner",
                new BigDecimal("20"),
                true,
                "5 min",
                "v4",
                "published",
                "body")));
        private I18nMessagePairView publishedPair = new I18nMessagePairView(
                "milestones.earnCross",
                "milestones",
                "You crossed {amount} and earned {nex}",
                "累计 {amount} 奖励 {nex}",
                "Đạt {amount} và nhận {nex}",
                "published",
                "v4",
                List.of("{amount}", "{nex}"));
        private I18nMessagePairView draftPair;

        @Override
        public void ensureSeedData(LocalDateTime now) {
            seedCalls += 1;
        }

        @Override
        public List<I18nNamespaceView> listNamespaces() {
            return namespaces;
        }

        @Override
        public Optional<I18nMessagePairView> findMessagePair(String messageKey) {
            if (draftPair != null && draftPair.messageKey().equals(messageKey)) return Optional.of(draftPair);
            return publishedPair != null && publishedPair.messageKey().equals(messageKey) ? Optional.of(publishedPair) : Optional.empty();
        }

        @Override
        public Optional<I18nMessagePairView> findPublishedMessagePair(String messageKey) {
            return publishedPair != null && publishedPair.messageKey().equals(messageKey) ? Optional.of(publishedPair) : Optional.empty();
        }

        @Override
        public Optional<I18nMessagePairView> findDraftMessagePair(String messageKey) {
            return draftPair != null && draftPair.messageKey().equals(messageKey) ? Optional.of(draftPair) : Optional.empty();
        }

        @Override
        public List<I18nMessagePairView> listMessagePairs() {
            I18nMessagePairView latest = draftPair != null ? draftPair : publishedPair;
            return latest == null ? List.of() : List.of(latest);
        }

        @Override
        public I18nMessagePairView saveMessagePair(String messageKey, String zh, String en, String vi, String status, LocalDateTime now) {
            I18nMessagePairView saved = new I18nMessagePairView(messageKey, messageKey.split("\\.")[0], en, zh, vi, status, "v5", List.of("{amount}", "{nex}"));
            if ("published".equals(status)) {
                publishedPair = saved;
                draftPair = null;
            } else {
                draftPair = saved;
            }
            return saved;
        }

        @Override
        public I18nMessagePairView archiveMessage(String messageKey, LocalDateTime now) {
            I18nMessagePairView current = publishedPair;
            publishedPair = null;
            return new I18nMessagePairView(current.messageKey(), current.namespace(), current.en(), current.zh(), current.vi(), "archived", current.version(), current.placeholders());
        }

        @Override
        public List<I18nIntegrityIssueView> listIntegrityIssues() {
            return List.copyOf(issues);
        }

        @Override
        public I18nIntegrityIssueView markIssueFixed(String issueCode, LocalDateTime now) {
            for (int index = 0; index < issues.size(); index += 1) {
                I18nIntegrityIssueView current = issues.get(index);
                if (current.code().equals(issueCode)) {
                    I18nIntegrityIssueView fixed = new I18nIntegrityIssueView(
                            current.code(), current.kind(), current.cnt(), current.samples(), "fixed");
                    issues.set(index, fixed);
                    return fixed;
                }
            }
            throw new IllegalArgumentException(issueCode);
        }

        @Override
        public List<I18nHardcodedFindingView> listHardcodedFindings() {
            return findings;
        }

        @Override
        public List<LearningCourseView> listCourses() {
            return List.copyOf(courses);
        }

        @Override
        public Optional<LearningCourseView> findCourse(String courseId) {
            return courses.stream().filter(row -> row.id().equals(courseId)).findFirst();
        }

        @Override
        public LearningCourseView createCourse(String courseId, LearningCourseUpsertRequest request, LocalDateTime now) {
            LearningCourseView created = new LearningCourseView(
                    courseId,
                    request.titleZh(),
                    request.category(),
                    request.format(),
                    request.difficulty(),
                    request.rewardNex(),
                    false,
                    request.duration(),
                    "draft",
                    request.publishState(),
                    request.bodyZh(), request.titleZh(), request.titleEn(), request.bodyZh(), request.bodyEn(),
                    request.quizQuestions().stream().map(row -> new ffdd.opsconsole.content.domain.LearningQuizQuestionView(
                            row.questionId(), row.questionZh(), row.questionEn(), row.optionsZh(), row.optionsEn(), row.correctOptionIndex())).toList(),
                    request.passScore(), request.retryLimit(), request.completionCondition(), request.rewardEvent(), 0L,
                    request.titleVi(), request.bodyVi());
            courses.add(0, created);
            return created;
        }

        @Override
        public List<I18nIntegrityIssueView> recomputeIntegrity(LocalDateTime now) {
            issues.replaceAll(issue -> new I18nIntegrityIssueView(issue.code(), issue.kind(), 0, List.of(), "fixed"));
            return List.copyOf(issues);
        }

        @Override
        public LearningCourseView updateCourseDraft(String courseId, LearningCourseUpsertRequest request, LocalDateTime now) {
            LearningCourseView current = findCourse(courseId).orElseThrow();
            LearningCourseView updated = new LearningCourseView(
                    current.id(), request.titleZh(), request.category(), request.format(), request.difficulty(), request.rewardNex(),
                    current.featured(), request.duration(), current.version(), "draft", request.bodyZh(),
                    request.titleZh(), request.titleEn(), request.bodyZh(), request.bodyEn(),
                    request.quizQuestions().stream().map(row -> new ffdd.opsconsole.content.domain.LearningQuizQuestionView(
                            row.questionId(), row.questionZh(), row.questionEn(), row.optionsZh(), row.optionsEn(), row.correctOptionIndex())).toList(),
                    request.passScore(), request.retryLimit(), request.completionCondition(), request.rewardEvent(), current.revision() + 1,
                    request.titleVi(), request.bodyVi());
            replaceCourse(updated);
            return updated;
        }

        @Override
        public void deleteCourseDraft(String courseId, LocalDateTime now) {
            courses.removeIf(row -> row.id().equals(courseId));
        }

        @Override
        public LearningCourseView updateCourseStatus(String courseId, String status, LocalDateTime now) {
            LearningCourseView current = findCourse(courseId).orElseThrow();
            LearningCourseView updated = new LearningCourseView(
                    current.id(), current.title(), current.category(), current.format(), current.level(), current.rewardNex(),
                    current.featured(), current.duration(), current.version(), status, current.body());
            replaceCourse(updated);
            return updated;
        }

        @Override
        public LearningCourseView updateCourseReward(String courseId, BigDecimal rewardNex, LocalDateTime now) {
            LearningCourseView current = findCourse(courseId).orElseThrow();
            LearningCourseView updated = new LearningCourseView(
                    current.id(), current.title(), current.category(), current.format(), current.level(), rewardNex,
                    current.featured(), current.duration(), current.version(), current.status(), current.body());
            replaceCourse(updated);
            return updated;
        }

        @Override
        public LearningCourseView updateFeaturedCourse(String courseId, LocalDateTime now) {
            for (int index = 0; index < courses.size(); index += 1) {
                LearningCourseView current = courses.get(index);
                courses.set(index, new LearningCourseView(
                        current.id(), current.title(), current.category(), current.format(), current.level(), current.rewardNex(),
                        current.id().equals(courseId), current.duration(), current.version(), current.status(), current.body()));
            }
            return findCourse(courseId).orElseThrow();
        }

        private void replaceCourse(LearningCourseView updated) {
            for (int index = 0; index < courses.size(); index += 1) {
                if (courses.get(index).id().equals(updated.id())) {
                    courses.set(index, updated);
                    return;
                }
            }
        }
    }
}
