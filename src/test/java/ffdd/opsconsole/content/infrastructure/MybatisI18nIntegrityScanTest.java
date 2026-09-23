package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MybatisI18nIntegrityScanTest {
    @Test
    void rescanChecksPublishedCopyEvenWhenAValidNewerDraftExists() {
        I18nIntegrityIssueMapper issueMapper = mock(I18nIntegrityIssueMapper.class);
        when(issueMapper.selectList(any())).thenReturn(List.of());
        MybatisI18nLearningRepository repository = spy(new MybatisI18nLearningRepository(
                mock(I18nNamespaceMapper.class), mock(I18nMessageMapper.class),
                mock(I18nMessageVersionMapper.class), issueMapper,
                mock(I18nHardcodedFindingMapper.class), mock(HelpArticleMapper.class),
                mock(LearningCourseVersionMapper.class), mock(AppLearningMapper.class)));
        String badKey = "home.abExperimentBanner";
        I18nMessagePairView badPublished = pair(badKey, "ccccc",
                "Find the NexionBox that fits you now", "Tìm NexionBox phù hợp", "published", "v3");
        I18nMessagePairView validDraft = pair(badKey, "寻找适合您的 NexGridBox",
                "Find the NexGridBox that fits you", "Tìm NexGridBox phù hợp", "draft", "v4");
        I18nMessagePairView validPublished = pair("home.welcome", "欢迎使用 NexGrid",
                "Welcome to NexGrid", "Chào mừng đến với NexGrid", "published", "v2");
        doReturn(List.of(validDraft, validPublished)).when(repository).listMessagePairs();
        doReturn(Optional.of(badPublished)).when(repository).findPublishedMessagePair(badKey);
        doReturn(Optional.of(validPublished)).when(repository).findPublishedMessagePair("home.welcome");

        repository.recomputeIntegrity(LocalDateTime.of(2026, 9, 23, 0, 0));

        ArgumentCaptor<I18nIntegrityIssueEntity> issues = ArgumentCaptor.forClass(I18nIntegrityIssueEntity.class);
        verify(issueMapper, org.mockito.Mockito.times(6)).insert(issues.capture());
        assertThat(issues.getAllValues())
                .filteredOn(issue -> issue.getIssueCount() > 0)
                .extracting(I18nIntegrityIssueEntity::getIssueCode)
                .containsExactly("placeholder-text", "retired-brand");
        assertThat(issues.getAllValues())
                .filteredOn(issue -> issue.getIssueCount() > 0)
                .allSatisfy(issue -> assertThat(issue.getSamplesText()).isEqualTo(badKey));
    }

    private static I18nMessagePairView pair(String key, String zh, String en, String vi,
            String status, String version) {
        return new I18nMessagePairView(key, "home", en, zh, vi, status, version, List.of());
    }
}
