package ffdd.opsconsole.content.domain;

import java.util.List;

public record SessionRuntimeTemplateOverview(
        SessionWorkbenchPolicyView workbenchPolicy,
        List<SessionScriptView> scripts,
        List<SessionReplyTemplateView> replyTemplates) {
}
