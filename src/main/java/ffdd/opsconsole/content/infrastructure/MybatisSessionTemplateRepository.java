package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.SessionReplyTemplateView;
import ffdd.opsconsole.content.domain.SessionScriptView;
import ffdd.opsconsole.content.domain.SessionTemplateRepository;
import ffdd.opsconsole.content.dto.SessionReplyTemplateCreateRequest;
import ffdd.opsconsole.content.dto.SessionScriptCreateRequest;
import ffdd.opsconsole.content.mapper.HelpArticleMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisSessionTemplateRepository implements SessionTemplateRepository {
    private static final String SCRIPT_FORMAT = "session_script";
    private static final String REPLY_TEMPLATE_FORMAT = "session_reply_template";

    private final HelpArticleMapper helpArticleMapper;

    @Override
    public List<SessionScriptView> listScripts() {
        return helpArticleMapper.listSessionScripts();
    }

    @Override
    public Optional<SessionScriptView> findScript(String scriptId) {
        return Optional.ofNullable(helpArticleMapper.findSessionScript(scriptId));
    }

    @Override
    public SessionScriptView createScript(String scriptId, SessionScriptCreateRequest request, LocalDateTime now) {
        HelpArticleEntity entity = baseEntity(scriptId, SCRIPT_FORMAT, now);
        entity.setTitle(request.scriptGroup().trim());
        entity.setContent(request.text().trim());
        entity.setCategory("advisor");
        entity.setLevel(request.audience().trim());
        entity.setSurface(request.ctaPath().trim());
        entity.setDurationMin(1);
        entity.setSortOrder(helpArticleMapper.maxSortOrderByFormat(SCRIPT_FORMAT) + 10);
        entity.setStatus(toDbStatus(request.status()));
        helpArticleMapper.insert(entity);
        return findScript(scriptId).orElse(new SessionScriptView(
                scriptId,
                entity.getTitle(),
                entity.getContent(),
                entity.getSurface(),
                toScriptStatus(entity.getStatus()),
                entity.getLevel(),
                now));
    }

    @Override
    public void updateScriptStatus(String scriptId, String status, LocalDateTime now) {
        helpArticleMapper.updateSessionScriptStatus(scriptId, toDbStatus(status), now);
    }

    @Override
    public void updateScriptAudience(String scriptId, String audience, LocalDateTime now) {
        helpArticleMapper.updateSessionScriptAudience(scriptId, audience.trim(), now);
    }

    @Override
    public List<SessionReplyTemplateView> listReplyTemplates() {
        return helpArticleMapper.listSessionReplyTemplates();
    }

    @Override
    public Optional<SessionReplyTemplateView> findReplyTemplate(String templateId) {
        return Optional.ofNullable(helpArticleMapper.findSessionReplyTemplate(templateId));
    }

    @Override
    public SessionReplyTemplateView createReplyTemplate(String templateId, SessionReplyTemplateCreateRequest request, LocalDateTime now) {
        String type = request.type().trim().toLowerCase(Locale.ROOT);
        HelpArticleEntity entity = baseEntity(templateId, REPLY_TEMPLATE_FORMAT, now);
        entity.setTitle(type);
        entity.setContent(request.text().trim());
        entity.setCategory("reply-template");
        entity.setLevel(type);
        entity.setSurface("Session Workbench");
        entity.setDurationMin(1);
        entity.setSortOrder(helpArticleMapper.maxSortOrderByFormat(REPLY_TEMPLATE_FORMAT) + 10);
        entity.setStatus(toDbStatus(request.status()));
        helpArticleMapper.insert(entity);
        return findReplyTemplate(templateId).orElse(new SessionReplyTemplateView(
                templateId,
                type,
                entity.getContent(),
                toScriptStatus(entity.getStatus()),
                now));
    }

    @Override
    public void updateReplyTemplateStatus(String templateId, String status, LocalDateTime now) {
        helpArticleMapper.updateSessionReplyTemplateStatus(templateId, toDbStatus(status), now);
    }

    private HelpArticleEntity baseEntity(String articleCode, String format, LocalDateTime now) {
        HelpArticleEntity entity = new HelpArticleEntity();
        entity.setArticleCode(articleCode);
        entity.setFormat(format);
        entity.setRewardNex(BigDecimal.ZERO);
        entity.setProgressPct(0);
        entity.setFeatured(0);
        entity.setEmoji("?");
        entity.setTint("#7dd3fc");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        return entity;
    }

    private int toDbStatus(String status) {
        return "published".equalsIgnoreCase(status) ? 1 : 0;
    }

    private String toScriptStatus(Integer status) {
        return status != null && status == 1 ? "published" : "draft";
    }
}
