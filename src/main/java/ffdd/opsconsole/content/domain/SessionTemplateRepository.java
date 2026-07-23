package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.SessionReplyTemplateCreateRequest;
import ffdd.opsconsole.content.dto.SessionReplyTemplateQueryRequest;
import ffdd.opsconsole.content.dto.SessionScriptCreateRequest;
import ffdd.opsconsole.content.dto.SessionScriptQueryRequest;
import ffdd.opsconsole.shared.api.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SessionTemplateRepository {
    void ensureSeedData(LocalDateTime now);

    List<SessionScriptView> listScripts();

    PageResult<SessionScriptView> pageScripts(SessionScriptQueryRequest request);

    Optional<SessionScriptView> findScript(String scriptId);

    default Optional<SessionScriptView> findScriptForUpdate(String scriptId) {
        return findScript(scriptId);
    }

    SessionScriptView createScript(String scriptId, SessionScriptCreateRequest request, LocalDateTime now);

    void updateScriptStatus(String scriptId, String status, LocalDateTime now);

    void updateScriptAudience(String scriptId, String audience, LocalDateTime now);

    List<SessionReplyTemplateView> listReplyTemplates();

    PageResult<SessionReplyTemplateView> pageReplyTemplates(SessionReplyTemplateQueryRequest request);

    Optional<SessionReplyTemplateView> findReplyTemplate(String templateId);

    default Optional<SessionReplyTemplateView> findReplyTemplateForUpdate(String templateId) {
        return findReplyTemplate(templateId);
    }

    SessionReplyTemplateView createReplyTemplate(String templateId, SessionReplyTemplateCreateRequest request, LocalDateTime now);

    void updateReplyTemplateStatus(String templateId, String status, LocalDateTime now);
}
