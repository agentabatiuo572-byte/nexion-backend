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
        if (listFaqs().isEmpty()) {
            createFaq("FAQ-M-SEED-001", faq(
                    "withdrawal",
                    "Help Center",
                    "Why is my withdrawal still pending?",
                    "Most pending withdrawals are waiting for payment desk review, network settlement, or KYC re-check. Support can attach a queue reference and ETA."),
                    now);
            createFaq("FAQ-M-SEED-002", faq(
                    "kyc",
                    "Ticket Create",
                    "What should I do after a KYC rejection?",
                    "Re-upload a clear document image, keep every corner visible, and avoid glare or cropped edges. Support can reset the retry link when the retry window is exhausted."),
                    now);
            createFaq("FAQ-M-SEED-003", faq(
                    "hardware",
                    "Nova",
                    "How do I recover a disconnected NexionBox?",
                    "Hold power for 10 seconds, re-pair the device in the app, then attach the LED pattern to a hardware support ticket if it stays offline."),
                    now);
        }
        if (listSla().isEmpty()) {
            upsertSla("withdrawal", sla(15, 12, "支付台", "D2 withdrawal review"), now);
            upsertSla("kyc", sla(30, 24, "合规台", "C4 KYC ledger"), now);
            upsertSla("hardware", sla(45, 48, "设备运维台", "E5 device ops"), now);
            upsertSla("genesis", sla(20, 18, "创世节点台", "G4 Genesis economy"), now);
            upsertSla("account", sla(30, 24, "账户台", "C5 security"), now);
            upsertSla("technical", sla(60, 72, "技术支持台", "A3 system config"), now);
        }
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
        entity.setLevel("support");
        entity.setFormat("faq");
        entity.setSurface(request.surface().trim());
        entity.setDurationMin(3);
        entity.setRewardNex(BigDecimal.ZERO);
        entity.setProgressPct(0);
        entity.setFeatured(0);
        entity.setEmoji("?");
        entity.setTint("#c6ff3a");
        entity.setSortOrder(helpArticleMapper.maxFaqSortOrder() + 10);
        entity.setStatus(toDbStatus(request.status()));
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
                now);
    }

    @Override
    public void updateFaqStatus(String faqId, String status, LocalDateTime now) {
        helpArticleMapper.updateFaqStatus(faqId, toDbStatus(status), now);
    }

    @Override
    public void deleteFaq(String faqId, LocalDateTime now) {
        helpArticleMapper.deleteFaq(faqId, now);
    }

    @Override
    public List<SupportSlaView> listSla() {
        return slaRuleMapper.listActive();
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
                now);
    }

    private int toDbStatus(String status) {
        return "DRAFT".equalsIgnoreCase(status) ? 0 : 1;
    }

    private String toViewStatus(Integer status) {
        return status != null && status == 0 ? "DRAFT" : "PUBLISHED";
    }

    private SupportFaqUpsertRequest faq(String category, String surface, String question, String answer) {
        return new SupportFaqUpsertRequest(category, surface, question, answer, "PUBLISHED", "system", "seed support knowledge");
    }

    private SupportSlaUpdateRequest sla(Integer firstResponseMins, Integer resolutionHours, String queue, String escalation) {
        return new SupportSlaUpdateRequest(firstResponseMins, resolutionHours, queue, escalation, "system", "seed support sla");
    }
}
