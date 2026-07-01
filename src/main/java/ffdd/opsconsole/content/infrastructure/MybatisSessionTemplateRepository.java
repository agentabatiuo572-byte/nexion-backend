package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.SessionReplyTemplateView;
import ffdd.opsconsole.content.domain.SessionScriptView;
import ffdd.opsconsole.content.domain.SessionTemplateRepository;
import ffdd.opsconsole.content.dto.SessionReplyTemplateCreateRequest;
import ffdd.opsconsole.content.dto.SessionReplyTemplateQueryRequest;
import ffdd.opsconsole.content.dto.SessionScriptCreateRequest;
import ffdd.opsconsole.content.dto.SessionScriptQueryRequest;
import ffdd.opsconsole.content.mapper.HelpArticleMapper;
import ffdd.opsconsole.shared.api.PageResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisSessionTemplateRepository implements SessionTemplateRepository {
    private static final String SCRIPT_FORMAT = "session_script";
    private static final String REPLY_TEMPLATE_FORMAT = "session_reply_template";

    private final HelpArticleMapper helpArticleMapper;

    @Override
    public void ensureSeedData(LocalDateTime now) {
        // Business rows must come from MySQL writes, not read-time demo seeds.
    }

    @Override
    public List<SessionScriptView> listScripts() {
        return helpArticleMapper.listSessionScripts();
    }

    @Override
    public PageResult<SessionScriptView> pageScripts(SessionScriptQueryRequest request) {
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        String status = normalizeStatus(request == null ? null : request.status());
        String keyword = normalizeKeyword(request == null ? null : request.keyword());
        long total = helpArticleMapper.countSessionScripts(status, keyword);
        List<SessionScriptView> records = total == 0
                ? List.of()
                : helpArticleMapper.pageSessionScripts(status, keyword, pageSize, (pageNum - 1) * pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
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
    public PageResult<SessionReplyTemplateView> pageReplyTemplates(SessionReplyTemplateQueryRequest request) {
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        String type = normalizeTemplateType(request == null ? null : request.type());
        String status = normalizeStatus(request == null ? null : request.status());
        String keyword = normalizeKeyword(request == null ? null : request.keyword());
        long total = helpArticleMapper.countSessionReplyTemplates(type, status, keyword);
        List<SessionReplyTemplateView> records = total == 0
                ? List.of()
                : helpArticleMapper.pageSessionReplyTemplates(type, status, keyword, pageSize, (pageNum - 1) * pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
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

    private long normalizePage(Long pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private long normalizeSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, 100);
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return List.of("published", "draft").contains(normalized) ? normalized : null;
    }

    private String normalizeTemplateType(String type) {
        if (!StringUtils.hasText(type)) {
            return null;
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return List.of("advisor", "support").contains(normalized) ? normalized : null;
    }

    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        return "%" + keyword.trim() + "%";
    }

    private SessionScriptCreateRequest script(String scriptGroup, String text, String ctaPath, String audience, String status) {
        return new SessionScriptCreateRequest(scriptGroup, text, ctaPath, audience, status, "system", "seed session script");
    }

    private SessionReplyTemplateCreateRequest replyTemplate(String type, String text, String status) {
        return new SessionReplyTemplateCreateRequest(type, text, status, "system", "seed reply template");
    }
}
