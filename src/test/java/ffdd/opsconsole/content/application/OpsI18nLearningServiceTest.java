package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.I18nHardcodedFindingView;
import ffdd.opsconsole.content.domain.I18nIntegrityIssueView;
import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.domain.I18nMessagePairView;
import ffdd.opsconsole.content.domain.I18nNamespaceView;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.dto.I18nActionRequest;
import ffdd.opsconsole.content.dto.I18nIntegrityFixRequest;
import ffdd.opsconsole.content.dto.I18nLocalizedCopyRequest;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.dto.LearningFeaturedUpdateRequest;
import ffdd.opsconsole.content.dto.LearningRewardUpdateRequest;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsI18nLearningServiceTest {
    private final FakeI18nLearningRepository repository = new FakeI18nLearningRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final TreasuryCoverageFacade coverageFacade = () -> new TreasuryCoverageSnapshot(new BigDecimal("128.4"), new BigDecimal("100"));
    private final OpsI18nLearningService service = new OpsI18nLearningService(
            repository,
            auditLogService,
            coverageFacade,
            Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC")));

    @Test
    void overviewUsesBackendRecordsAndNoActivePremiumNamespace() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().namespaces()).extracting(I18nNamespaceView::ns).doesNotContain("premium");
        assertThat(result.getData().sources()).contains("nx_i18n_message", "nx_help_article");
        assertThat(result.getData().stats().integrityIssues()).isEqualTo(10);
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
        var result = service.publishLocalizedMessage("milestones.earnCross", "idem-i6-pub", copyRequest("完成 {amount} 并获得 {nex}", "Earn {nex} after {amount}"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("published");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I6_I18N_MESSAGE_PUBLISHED");
    }

    @Test
    void fixIntegrityMarksIssueFixed() {
        var result = service.fixIntegrity("missing-zh", "idem-i6-fix", new I18nIntegrityFixRequest(
                "补齐 {amount}",
                "Repair {amount}",
                "Marina K.",
                "修复缺失镜像"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("fixed");
    }

    @Test
    void rewardIncreaseBelowRedlineReturns422() {
        OpsI18nLearningService redlineService = new OpsI18nLearningService(
                repository,
                auditLogService,
                () -> new TreasuryCoverageSnapshot(new BigDecimal("88"), new BigDecimal("100")),
                Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC")));

        var result = redlineService.updateCourseReward("what-is-nexion", "idem-i7-reward", new LearningRewardUpdateRequest(
                new BigDecimal("40"),
                "Marina K.",
                "提高课程奖励"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
    }

    @Test
    void courseLifecycleCreatesPublishesArchivesAndRejectsRepeatedArchive() {
        var created = service.createCourse("new-compute-guide", "idem-i7-create", newCourseRequest("draft"));
        assertThat(created.getCode()).isZero();
        assertThat(created.getData().status()).isEqualTo("draft");

        var published = service.publishCourse("new-compute-guide", "idem-i7-publish", new I18nActionRequest("Marina K.", "发布课程新版"));
        assertThat(published.getCode()).isZero();
        assertThat(published.getData().status()).isEqualTo("published");

        var archived = service.archiveCourse("new-compute-guide", "idem-i7-archive", new I18nActionRequest("Marina K.", "下架课程归档"));
        assertThat(archived.getCode()).isZero();
        assertThat(archived.getData().status()).isEqualTo("archived");

        var repeated = service.archiveCourse("new-compute-guide", "idem-i7-archive-2", new I18nActionRequest("Marina K.", "重复下架课程"));
        assertThat(repeated.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void featuredCourseMustBePublished() {
        service.createCourse("draft-only", "idem-i7-create-2", newCourseRequest("draft"));

        var result = service.updateFeaturedCourse("idem-i7-featured", new LearningFeaturedUpdateRequest(
                "draft-only",
                "Marina K.",
                "切换推荐位课程"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    private static I18nLocalizedCopyRequest copyRequest(String zh, String en) {
        return new I18nLocalizedCopyRequest(zh, en, "Marina K.", "同步中英文词条");
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
                "新建课程草稿");
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
        private I18nMessagePairView pair = new I18nMessagePairView(
                "milestones.earnCross",
                "You crossed {amount} and earned {nex}",
                "累计 {amount} 奖励 {nex}",
                "published",
                "v4",
                List.of("{amount}", "{nex}"));

        @Override
        public List<I18nNamespaceView> listNamespaces() {
            return namespaces;
        }

        @Override
        public Optional<I18nMessagePairView> findMessagePair(String messageKey) {
            return pair.messageKey().equals(messageKey) ? Optional.of(pair) : Optional.empty();
        }

        @Override
        public I18nMessagePairView saveMessagePair(String messageKey, String zh, String en, String status, LocalDateTime now) {
            pair = new I18nMessagePairView(messageKey, en, zh, status, "v5", List.of("{amount}", "{nex}"));
            return pair;
        }

        @Override
        public I18nMessagePairView markMarketingExperiment(String messageKey, LocalDateTime now) {
            return pair;
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
                    request.bodyZh());
            courses.add(0, created);
            return created;
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
