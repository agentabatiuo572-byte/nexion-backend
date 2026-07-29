package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.mapper.AppLearningMapper;
import ffdd.opsconsole.content.mapper.HelpArticleMapper;
import ffdd.opsconsole.content.mapper.I18nHardcodedFindingMapper;
import ffdd.opsconsole.content.mapper.I18nIntegrityIssueMapper;
import ffdd.opsconsole.content.mapper.I18nMessageMapper;
import ffdd.opsconsole.content.mapper.I18nMessageVersionMapper;
import ffdd.opsconsole.content.mapper.I18nNamespaceMapper;
import ffdd.opsconsole.content.mapper.LearningCourseVersionMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MybatisI18nLearningRepositoryDraftCasTest {

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
                current.getId(),
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

        var saved = repository.saveMessagePair(
                current.getMessageKey(),
                "新中文",
                "New English",
                "Tiếng Việt mới",
                "draft",
                LocalDateTime.parse("2026-07-28T10:01:00"));

        assertThat(saved.version()).isEqualTo("v2");
        assertThat(saved.status()).isEqualTo("draft");
        assertThat(current.getIsDeleted()).isEqualTo(1);
        verify(versionMapper).retireDraftCas(
                current.getId(),
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
        when(versionMapper.retireDraftCas(any(Long.class), any(LocalDateTime.class))).thenReturn(0);

        MybatisI18nLearningRepository repository = new MybatisI18nLearningRepository(
                mock(I18nNamespaceMapper.class),
                mock(I18nMessageMapper.class),
                versionMapper,
                mock(I18nIntegrityIssueMapper.class),
                mock(I18nHardcodedFindingMapper.class),
                mock(HelpArticleMapper.class),
                mock(LearningCourseVersionMapper.class),
                mock(AppLearningMapper.class));

        assertThatThrownBy(() -> repository.saveMessagePair(
                stale.getMessageKey(),
                "竞争中文",
                "Concurrent English",
                "Tiếng Việt đồng thời",
                "draft",
                LocalDateTime.parse("2026-07-28T10:01:00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("I18N_MESSAGE_VERSION_CONFLICT");
        verify(versionMapper, never()).insert(any(I18nMessageVersionEntity.class));
    }
}
