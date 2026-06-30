package ffdd.opsconsole.content.domain;

import java.util.List;

public record SessionTemplateOverview(
        List<SessionCategoryView> categories,
        SessionAdvisorPolicyView advisorPolicy,
        SessionWorkbenchPolicyView workbenchPolicy,
        List<String> audienceOptions,
        List<SessionSegmentField> segmentFields,
        List<SessionCtaOption> ctaOptions,
        List<String> scriptGroups,
        List<String> templateTypes,
        List<String> statusOptions,
        List<SessionScriptView> scripts,
        List<SessionReplyTemplateView> replyTemplates,
        List<String> sources) {
}
