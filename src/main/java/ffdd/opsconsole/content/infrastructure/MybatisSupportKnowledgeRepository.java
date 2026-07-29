package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.SupportFaqView;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import ffdd.opsconsole.content.domain.SupportSlaView;
import ffdd.opsconsole.content.dto.SupportFaqUpsertRequest;
import ffdd.opsconsole.content.dto.SupportSlaUpdateRequest;
import ffdd.opsconsole.content.mapper.HelpArticleMapper;
import ffdd.opsconsole.content.mapper.SupportSlaRuleMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisSupportKnowledgeRepository implements SupportKnowledgeRepository {
    private final HelpArticleMapper helpArticleMapper;
    private final SupportSlaRuleMapper slaRuleMapper;

    @Override
    public void ensureSeedData(LocalDateTime now) {
        // Business rows must come from MySQL writes, not read-time demo seeds.
    }

    @Override
    public List<SupportFaqView> listFaqs() {
        return helpArticleMapper.listFaqs();
    }

    @Override
    public Optional<SupportFaqView> findFaq(String faqId) {
        return Optional.ofNullable(helpArticleMapper.findFaq(faqId));
    }

    @Override
    public SupportFaqView createFaq(String faqId, SupportFaqUpsertRequest request, LocalDateTime now) {
        HelpArticleEntity entity = new HelpArticleEntity();
        entity.setArticleCode(faqId);
        entity.setTitle(request.question().trim());
        entity.setContent(request.answer().trim());
        entity.setCategory(request.category().trim().toLowerCase(Locale.ROOT));
        entity.setLevel(request.language());
        entity.setFormat("faq");
        entity.setSurface(request.surface().trim());
        entity.setDurationMin(3);
        entity.setRewardNex(BigDecimal.ZERO);
        entity.setProgressPct(0);
        entity.setFeatured(0);
        entity.setEmoji("?");
        entity.setTint("#c6ff3a");
        entity.setSortOrder(request.sortOrder());
        entity.setStatus(toDbStatus(request.status()));
        entity.setVersionNo(1);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        helpArticleMapper.insert(entity);
        return findFaq(faqId).orElse(new SupportFaqView(
                faqId,
                entity.getCategory(),
                entity.getTitle(),
                entity.getContent(),
                toViewStatus(entity.getStatus()),
                entity.getSurface(),
                entity.getLevel(),
                entity.getSortOrder(),
                entity.getVersionNo(),
                now));
    }

    @Override
    public void updateFaq(String faqId, SupportFaqUpsertRequest request, LocalDateTime now) {
        helpArticleMapper.updateFaq(
                faqId,
                request.category().trim().toLowerCase(Locale.ROOT),
                request.surface().trim(),
                request.question().trim(),
                request.answer().trim(),
                toDbStatus(request.status()),
                request.language(),
                request.sortOrder(),
                toDbStatus(request.expectedStatus()),
                request.expectedVersion(),
                now);
    }

    @Override
    public boolean updateFaqCas(String faqId, SupportFaqUpsertRequest request, String expectedStatus, Integer expectedVersion, LocalDateTime now) {
        return helpArticleMapper.updateFaq(
                faqId, request.category().trim().toLowerCase(Locale.ROOT), request.surface().trim(),
                request.question().trim(), request.answer().trim(), toDbStatus(request.status()),
                request.language(), request.sortOrder(), toDbStatus(expectedStatus), expectedVersion, now) == 1;
    }

    @Override
    public void updateFaqStatus(String faqId, String status, LocalDateTime now) {
        throw new UnsupportedOperationException("FAQ status writes require CAS");
    }

    @Override
    public boolean updateFaqStatusCas(String faqId, String status, String expectedStatus, Integer expectedVersion, LocalDateTime now) {
        return helpArticleMapper.updateFaqStatus(faqId, toDbStatus(status), toDbStatus(expectedStatus), expectedVersion, now) == 1;
    }

    @Override
    public void deleteFaq(String faqId, LocalDateTime now) {
        throw new UnsupportedOperationException("FAQ deletes require CAS");
    }

    @Override
    public boolean deleteFaqCas(String faqId, String expectedStatus, Integer expectedVersion, LocalDateTime now) {
        return helpArticleMapper.deleteFaq(faqId, toDbStatus(expectedStatus), expectedVersion, now) == 1;
    }

    @Override
    public List<SupportSlaView> listSla() {
        return slaRuleMapper.listActive();
    }

    @Override
    public void insertSlaIfMissing(String category, SupportSlaUpdateRequest request, LocalDateTime now) {
        slaRuleMapper.insertIgnoreRule(
                category.trim().toLowerCase(Locale.ROOT),
                request.firstResponseMins(),
                request.resolutionHours(),
                request.queue().trim(),
                request.escalation().trim(),
                now);
    }

    @Override
    public void upsertSla(String category, SupportSlaUpdateRequest request, LocalDateTime now) {
        String normalized = category.trim().toLowerCase(Locale.ROOT);
        if (slaRuleMapper.findIdByCategory(normalized) == null) {
            SupportSlaRuleEntity entity = new SupportSlaRuleEntity();
            entity.setCategory(normalized);
            entity.setFirstResponseMins(request.firstResponseMins());
            entity.setResolutionHours(request.resolutionHours());
            entity.setQueue(request.queue().trim());
            entity.setEscalation(request.escalation().trim());
            entity.setVersion(1L);
            entity.setStatus(1);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            entity.setIsDeleted(0);
            slaRuleMapper.insert(entity);
            return;
        }
        slaRuleMapper.updateRule(
                normalized,
                request.firstResponseMins(),
                request.resolutionHours(),
                request.queue().trim(),
                request.escalation().trim(),
                request.expectedVersion(),
                now);
    }

    @Override
    public boolean updateSlaCas(String category, SupportSlaUpdateRequest request, Long expectedVersion, LocalDateTime now) {
        return slaRuleMapper.updateRule(
                category.trim().toLowerCase(Locale.ROOT), request.firstResponseMins(), request.resolutionHours(),
                request.queue().trim(), request.escalation().trim(), expectedVersion, now) == 1;
    }

    private int toDbStatus(String status) {
        return "DRAFT".equalsIgnoreCase(status) ? 0 : 1;
    }

    private String toViewStatus(Integer status) {
        return status != null && status == 0 ? "DRAFT" : "PUBLISHED";
    }

    private SupportFaqUpsertRequest faq(String category, String surface, String question, String answer) {
        return new SupportFaqUpsertRequest(category, surface, question, answer, "PUBLISHED", "zh-CN", maxSortOrder(), "system", "seed support knowledge");
    }

    private int maxSortOrder() {
        return helpArticleMapper.maxFaqSortOrder() + 10;
    }

    private SupportSlaUpdateRequest sla(Integer firstResponseMins, Integer resolutionHours, String queue, String escalation) {
        return new SupportSlaUpdateRequest(firstResponseMins, resolutionHours, queue, escalation, "system", "seed support sla");
    }
}
