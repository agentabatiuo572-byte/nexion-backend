package ffdd.opsconsole.content.domain;

import java.util.List;

public record I18nLearningOverview(
        I18nLearningStats stats,
        List<I18nNamespaceView> namespaces,
        List<I18nIntegrityIssueView> integrityIssues,
        List<I18nHardcodedFindingView> hardcodedFindings,
        I18nMessagePairView focusMessage,
        List<LearningCourseView> courses,
        TutorialRewardRange rewardRange,
        String featuredCourseId,
        List<LearningMetricView> metrics,
        List<String> categories,
        List<String> formats,
        List<String> levels,
        List<String> statuses,
        List<String> sources) {
}
