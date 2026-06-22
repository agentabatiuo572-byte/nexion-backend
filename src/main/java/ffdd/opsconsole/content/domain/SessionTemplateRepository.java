package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.SessionReplyTemplateCreateRequest;
import ffdd.opsconsole.content.dto.SessionScriptCreateRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SessionTemplateRepository {
    List<SessionScriptView> listScripts();

    Optional<SessionScriptView> findScript(String scriptId);

    SessionScriptView createScript(String scriptId, SessionScriptCreateRequest request, LocalDateTime now);

    void updateScriptStatus(String scriptId, String status, LocalDateTime now);

    void updateScriptAudience(String scriptId, String audience, LocalDateTime now);

    List<SessionReplyTemplateView> listReplyTemplates();

    Optional<SessionReplyTemplateView> findReplyTemplate(String templateId);

    SessionReplyTemplateView createReplyTemplate(String templateId, SessionReplyTemplateCreateRequest request, LocalDateTime now);

    void updateReplyTemplateStatus(String templateId, String status, LocalDateTime now);
}
