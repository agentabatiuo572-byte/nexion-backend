package ffdd.opsconsole.content.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record I18nLearningOverview(
        I18nLearningStats stats,
        List<I18nNamespaceView> namespaces,
        List<I18nIntegrityIssueView> integrityIssues,
        List<I18nHardcodedFindingView> hardcodedFindings,
        I18nMessagePairView focusMessage,
        List<I18nMessagePairView> messages,
        List<LearningCourseView> courses,
        TutorialRewardRange rewardRange,
        String featuredCourseId,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<LearningMetricView> metrics,
        List<String> categories,
        List<String> formats,
        List<String> levels,
        List<String> statuses,
        List<String> sources,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime lastScanAt,
        Map<String, I18nMessagePairView> publishedMessages) {
    public I18nLearningOverview(
            I18nLearningStats stats, List<I18nNamespaceView> namespaces, List<I18nIntegrityIssueView> integrityIssues,
            List<I18nHardcodedFindingView> hardcodedFindings, I18nMessagePairView focusMessage,
            List<I18nMessagePairView> messages, List<LearningCourseView> courses, TutorialRewardRange rewardRange,
            String featuredCourseId, List<LearningMetricView> metrics, List<String> categories, List<String> formats,
            List<String> levels, List<String> statuses, List<String> sources) {
        this(stats, namespaces, integrityIssues, hardcodedFindings, focusMessage, messages, courses, rewardRange,
                featuredCourseId, metrics, categories, formats, levels, statuses, sources, null, Map.of());
    }
}
