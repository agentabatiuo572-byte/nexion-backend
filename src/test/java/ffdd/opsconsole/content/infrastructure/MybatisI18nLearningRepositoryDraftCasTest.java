package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.I18nMessagePairView;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import ffdd.opsconsole.content.mapper.HelpArticleMapper;
import ffdd.opsconsole.content.mapper.I18nHardcodedFindingMapper;
import ffdd.opsconsole.content.mapper.I18nIntegrityIssueMapper;
import ffdd.opsconsole.content.mapper.I18nMessageMapper;
import ffdd.opsconsole.content.mapper.I18nMessageVersionMapper;
import ffdd.opsconsole.content.mapper.I18nNamespaceMapper;
import ffdd.opsconsole.content.mapper.LearningCourseVersionMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class MybatisI18nLearningRepositoryDraftCasTest {

    @Test
    void malformedStoredQuizFailsClosedInsteadOfPublishingAQuizlessCourse() {
        HelpArticleMapper helpArticleMapper = mock(HelpArticleMapper.class);
        HelpArticleEntity course = new HelpArticleEntity();
        course.setArticleCode("learn.basics.malformed-quiz");
        course.setTitle("损坏测验");
        course.setContent("正文");
        course.setCategory("basics");
        course.setFormat("article");
        course.setLevel("beginner");
        course.setStatus(1);
        course.setIsDeleted(0);
        course.setSortOrder(10);
        course.setVersionNo(1);
        course.setQuizJson("{not-json");
        when(helpArticleMapper.selectList(any())).thenReturn(List.of(course));
        MybatisI18nLearningRepository repository = new MybatisI18nLearningRepository(
                mock(I18nNamespaceMapper.class),
                mock(I18nMessageMapper.class),
                mock(I18nMessageVersionMapper.class),
                mock(I18nIntegrityIssueMapper.class),
                mock(I18nHardcodedFindingMapper.class),
                helpArticleMapper,
                mock(LearningCourseVersionMapper.class),
                mock(AppLearningMapper.class));

        assertThatThrownBy(repository::listCourses)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LEARNING_COURSE_QUIZ_INVALID");
    }

    @Test
    void editingDraftAdvancesServerVersionAndRetiresPreviousDraft() {
        I18nMessageVersionMapper versionMapper = mock(I18nMessageVersionMapper.class);
        I18nMessageVersionEntity current = new I18nMessageVersionEntity();
        current.setId(31L);
        current.setMessageKey("acceptance.i6.concurrent");
        current.setVersionNo(1);
        current.setZhValue("旧中文");
        current.setEnValue("Old English");
        current.setViValue("Tiếng Việt cũ");
        current.setStatus("DRAFT");
        current.setIsDeleted(0);
        current.setCreatedAt(LocalDateTime.parse("2026-07-28T10:00:00"));
        current.setUpdatedAt(LocalDateTime.parse("2026-07-28T10:00:00"));
        when(versionMapper.selectOne(any())).thenReturn(current);
        when(versionMapper.retireDraftCas(
                current.getMessageKey(),
                current.getVersionNo(),
                LocalDateTime.parse("2026-07-28T10:01:00"))).thenReturn(1);

        MybatisI18nLearningRepository repository = new MybatisI18nLearningRepository(
                mock(I18nNamespaceMapper.class),
                mock(I18nMessageMapper.class),
                versionMapper,
                mock(I18nIntegrityIssueMapper.class),
                mock(I18nHardcodedFindingMapper.class),
                mock(HelpArticleMapper.class),
                mock(LearningCourseVersionMapper.class),
                mock(AppLearningMapper.class));

        var saved = repository.saveMessageDraftCas(
                current.getMessageKey(),
                "新中文",
                "New English",
                "Tiếng Việt mới",
                "v1",
                LocalDateTime.parse("2026-07-28T10:01:00"));

        assertThat(saved.version()).isEqualTo("v2");
        assertThat(saved.status()).isEqualTo("draft");
        verify(versionMapper).retireDraftCas(
                current.getMessageKey(),
                current.getVersionNo(),
                LocalDateTime.parse("2026-07-28T10:01:00"));
        ArgumentCaptor<I18nMessageVersionEntity> inserted =
                ArgumentCaptor.forClass(I18nMessageVersionEntity.class);
        verify(versionMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getVersionNo()).isEqualTo(2);
        assertThat(inserted.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(inserted.getValue().getIsDeleted()).isZero();
        assertThat(inserted.getValue().getZhValue()).isEqualTo("新中文");
    }

    @Test
    void staleDraftCannotBeRetiredTwiceOrInsertAnotherVersion() {
        I18nMessageVersionMapper versionMapper = mock(I18nMessageVersionMapper.class);
        I18nMessageVersionEntity stale = new I18nMessageVersionEntity();
        stale.setId(32L);
        stale.setMessageKey("acceptance.i6.concurrent");
        stale.setVersionNo(1);
        stale.setZhValue("旧中文");
        stale.setEnValue("Old English");
        stale.setViValue("Tiếng Việt cũ");
        stale.setStatus("DRAFT");
        stale.setIsDeleted(0);
        stale.setCreatedAt(LocalDateTime.parse("2026-07-28T10:00:00"));
        stale.setUpdatedAt(LocalDateTime.parse("2026-07-28T10:00:00"));
        when(versionMapper.selectOne(any())).thenReturn(stale);
        when(versionMapper.retireDraftCas(
                anyString(), anyInt(), any(LocalDateTime.class))).thenReturn(0);

        MybatisI18nLearningRepository repository = new MybatisI18nLearningRepository(
                mock(I18nNamespaceMapper.class),
                mock(I18nMessageMapper.class),
                versionMapper,
                mock(I18nIntegrityIssueMapper.class),
                mock(I18nHardcodedFindingMapper.class),
                mock(HelpArticleMapper.class),
                mock(LearningCourseVersionMapper.class),
                mock(AppLearningMapper.class));

        assertThatThrownBy(() -> repository.saveMessageDraftCas(
                stale.getMessageKey(),
                "竞争中文",
                "Concurrent English",
                "Tiếng Việt đồng thời",
                "v1",
                LocalDateTime.parse("2026-07-28T10:01:00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("I18N_MESSAGE_VERSION_CONFLICT");
        verify(versionMapper, never()).insert(any(I18nMessageVersionEntity.class));
        verify(versionMapper).retireDraftCas(
                stale.getMessageKey(),
                stale.getVersionNo(),
                LocalDateTime.parse("2026-07-28T10:01:00"));
    }

    @Test
    void unexpectedInsertFailureAfterWinningRetirementEscapesForTransactionRollback() {
        I18nMessageVersionMapper versionMapper = mock(I18nMessageVersionMapper.class);
        I18nMessageVersionEntity current = draft(33L, 1);
        when(versionMapper.selectOne(any())).thenReturn(current);
        when(versionMapper.retireDraftCas(
                current.getMessageKey(), current.getVersionNo(), LocalDateTime.parse("2026-07-28T10:01:00")))
                .thenReturn(1);
        doThrow(new DuplicateKeyException("unexpected next-version collision"))
                .when(versionMapper).insert(any(I18nMessageVersionEntity.class));
        MybatisI18nLearningRepository repository = repository(versionMapper);

        assertThatThrownBy(() -> repository.saveMessageDraftCas(
                current.getMessageKey(),
                "新中文",
                "New English",
                "Tiếng Việt mới",
                "v1",
                LocalDateTime.parse("2026-07-28T10:01:00")))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("unexpected next-version collision");
    }

    @Test
    void firstDraftDuplicateIsAConflictWithoutAnyRetirement() {
        I18nMessageVersionMapper versionMapper = mock(I18nMessageVersionMapper.class);
        doThrow(new DuplicateKeyException("concurrent v1 winner"))
                .when(versionMapper).insert(any(I18nMessageVersionEntity.class));
        MybatisI18nLearningRepository repository = repository(versionMapper);

        assertThatThrownBy(() -> repository.saveMessageDraftCas(
                "acceptance.i6.first",
                "新中文",
                "New English",
                "Tiếng Việt mới",
                null,
                LocalDateTime.parse("2026-07-28T10:01:00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("I18N_MESSAGE_VERSION_CONFLICT")
                .hasCauseInstanceOf(DuplicateKeyException.class);
        verify(versionMapper, never()).retireDraftCas(anyString(), anyInt(), any(LocalDateTime.class));
    }

    @Test
    void publishedVersionCreatesTheNextDraftWithOneMysqlInsertSelectCas() {
        I18nMessageVersionMapper versionMapper = mock(I18nMessageVersionMapper.class);
        I18nMessageVersionEntity published = draft(34L, 7);
        published.setStatus("PUBLISHED");
        when(versionMapper.selectOne(any())).thenReturn(published);
        when(versionMapper.insertDraftCas(
                published.getMessageKey(),
                7,
                8,
                "新中文",
                "New English",
                "Tiếng Việt mới",
                LocalDateTime.parse("2026-07-28T10:01:00"))).thenReturn(1);
        MybatisI18nLearningRepository repository = repository(versionMapper);

        I18nMessagePairView saved = repository.saveMessageDraftCas(
                published.getMessageKey(),
                "新中文",
                "New English",
                "Tiếng Việt mới",
                "v7",
                LocalDateTime.parse("2026-07-28T10:01:00"));

        assertThat(saved.version()).isEqualTo("v8");
        assertThat(saved.status()).isEqualTo("draft");
        verify(versionMapper, never()).retireDraftCas(anyString(), anyInt(), any(LocalDateTime.class));
        verify(versionMapper, never()).insert(any(I18nMessageVersionEntity.class));
    }

    private static I18nMessageVersionEntity draft(Long id, int versionNo) {
        I18nMessageVersionEntity row = new I18nMessageVersionEntity();
        row.setId(id);
        row.setMessageKey("acceptance.i6.concurrent");
        row.setVersionNo(versionNo);
        row.setZhValue("旧中文");
        row.setEnValue("Old English");
        row.setViValue("Tiếng Việt cũ");
        row.setStatus("DRAFT");
        row.setIsDeleted(0);
        row.setCreatedAt(LocalDateTime.parse("2026-07-28T10:00:00"));
        row.setUpdatedAt(LocalDateTime.parse("2026-07-28T10:00:00"));
        return row;
    }

    private static MybatisI18nLearningRepository repository(I18nMessageVersionMapper versionMapper) {
        return new MybatisI18nLearningRepository(
                mock(I18nNamespaceMapper.class),
                mock(I18nMessageMapper.class),
                versionMapper,
                mock(I18nIntegrityIssueMapper.class),
                mock(I18nHardcodedFindingMapper.class),
                mock(HelpArticleMapper.class),
                mock(LearningCourseVersionMapper.class),
                mock(AppLearningMapper.class));
    }
}
