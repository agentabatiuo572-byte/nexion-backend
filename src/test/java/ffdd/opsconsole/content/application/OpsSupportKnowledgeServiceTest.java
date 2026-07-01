package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.SupportFaqView;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import ffdd.opsconsole.content.domain.SupportSlaView;
import ffdd.opsconsole.content.dto.SupportFaqStatusRequest;
import ffdd.opsconsole.content.dto.SupportFaqUpsertRequest;
import ffdd.opsconsole.content.dto.SupportKnowledgeDeleteRequest;
import ffdd.opsconsole.content.dto.SupportSlaUpdateRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsSupportKnowledgeServiceTest {
    private final FakeSupportKnowledgeRepository knowledgeRepository = new FakeSupportKnowledgeRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsSupportKnowledgeService service = service();

    private OpsSupportKnowledgeService service() {
        return new OpsSupportKnowledgeService(
                knowledgeRepository,
                auditLogService,
                Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC")),
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());
    }

    @Test
    void createFaqRequiresIdempotencyKey() {
        var result = service.createFaq(null, faqRequest("PUBLISHED"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void createFaqPersistsAndAudits() {
        var result = service.createFaq("idem-m4-faq", faqRequest("PUBLISHED"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).startsWith("FAQ-");
        assertThat(result.getData().status()).isEqualTo("PUBLISHED");
        assertThat(knowledgeRepository.faqs).hasSize(2);

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("M4_SUPPORT_FAQ_CREATED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("SUPPORT_KNOWLEDGE");
    }

    @Test
    void updateFaqChangesContent() {
        var result = service.updateFaq("FAQ-001", "idem-m4-faq-update", new SupportFaqUpsertRequest(
                "kyc",
                "Ticket Create",
                "KYC retry window?",
                "Upload a clear document and keep every corner visible.",
                "DRAFT",
                "Marina K.",
                "更新 KYC FAQ 文案"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().category()).isEqualTo("kyc");
        assertThat(result.getData().status()).isEqualTo("DRAFT");
    }

    @Test
    void publishRejectsSameStateWith409() {
        var result = service.updateFaqStatus("FAQ-001", "idem-m4-publish", new SupportFaqStatusRequest(
                "PUBLISHED",
                "Marina K.",
                "发布已有内容"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void statusChangePublishesDraft() {
        knowledgeRepository.faqs.set(0, new SupportFaqView(
                "FAQ-001",
                "withdrawal",
                "Why is withdrawal pending?",
                "Payment desk checks risk and chain state.",
                "DRAFT",
                "Help Center",
                now()));

        var result = service.updateFaqStatus("FAQ-001", "idem-m4-publish", new SupportFaqStatusRequest(
                "PUBLISHED",
                "Marina K.",
                "帮助中心发布"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void deleteFaqRequiresReason() {
        var result = service.deleteFaq("FAQ-001", "idem-m4-delete", new SupportKnowledgeDeleteRequest("Marina K.", "短"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void deleteFaqRemovesFromOverview() {
        var result = service.deleteFaq("FAQ-001", "idem-m4-delete", new SupportKnowledgeDeleteRequest("Marina K.", "归档重复 FAQ"));

        assertThat(result.getCode()).isZero();
        assertThat(service.overview().getData().faqs()).isEmpty();
    }

    @Test
    void updateSlaValidatesPositiveNumbers() {
        var result = service.updateSla("withdrawal", "idem-m4-sla", new SupportSlaUpdateRequest(
                0,
                12,
                "支付台",
                "D2 withdrawal review",
                "Marina K.",
                "同步 SLA"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void updateSlaPersistsAndAudits() {
        var result = service.updateSla("withdrawal", "idem-m4-sla", new SupportSlaUpdateRequest(
                10,
                8,
                "支付台",
                "D2 withdrawal review",
                "Marina K.",
                "提现 SLA 收紧"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().firstResponseMins()).isEqualTo(10);
        assertThat(result.getData().resolutionHours()).isEqualTo(8);
    }

    @Test
    void overviewListsBackendSources() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(knowledgeRepository.seedCalls).isZero();
        assertThat(result.getData().sources()).contains("nx_help_article", "nx_support_sla_rule");
        assertThat(result.getData().categories()).contains("withdrawal", "general");
    }

    private static SupportFaqUpsertRequest faqRequest(String status) {
        return new SupportFaqUpsertRequest(
                "withdrawal",
                "Help Center",
                "Why is withdrawal pending?",
                "Payment desk checks risk and chain state.",
                status,
                "Marina K.",
                "新增提现 FAQ");
    }

    private static LocalDateTime now() {
        return LocalDateTime.of(2026, 6, 18, 0, 0);
    }

    private static final class FakeSupportKnowledgeRepository implements SupportKnowledgeRepository {
        private int seedCalls;
        private final List<SupportFaqView> faqs = new ArrayList<>(List.of(new SupportFaqView(
                "FAQ-001",
                "withdrawal",
                "Why is withdrawal pending?",
                "Payment desk checks risk and chain state.",
                "PUBLISHED",
                "Help Center",
                now())));
        private final List<SupportSlaView> sla = new ArrayList<>(List.of(new SupportSlaView(
                "withdrawal",
                15,
                12,
                "支付台",
                "D2 withdrawal review",
                now())));

        @Override
        public void ensureSeedData(LocalDateTime now) {
            seedCalls += 1;
        }

        @Override
        public List<SupportFaqView> listFaqs() {
            return List.copyOf(faqs);
        }

        @Override
        public Optional<SupportFaqView> findFaq(String faqId) {
            return faqs.stream().filter(row -> row.id().equals(faqId)).findFirst();
        }

        @Override
        public SupportFaqView createFaq(String faqId, SupportFaqUpsertRequest request, LocalDateTime now) {
            SupportFaqView created = new SupportFaqView(
                    faqId,
                    request.category(),
                    request.question(),
                    request.answer(),
                    request.status(),
                    request.surface(),
                    now);
            faqs.add(0, created);
            return created;
        }

        @Override
        public void updateFaq(String faqId, SupportFaqUpsertRequest request, LocalDateTime now) {
            replaceFaq(faqId, new SupportFaqView(
                    faqId,
                    request.category(),
                    request.question(),
                    request.answer(),
                    request.status(),
                    request.surface(),
                    now));
        }

        @Override
        public void updateFaqStatus(String faqId, String status, LocalDateTime now) {
            SupportFaqView current = findFaq(faqId).orElseThrow();
            replaceFaq(faqId, new SupportFaqView(
                    current.id(),
                    current.category(),
                    current.question(),
                    current.answer(),
                    status,
                    current.surface(),
                    now));
        }

        @Override
        public void deleteFaq(String faqId, LocalDateTime now) {
            faqs.removeIf(row -> row.id().equals(faqId));
        }

        @Override
        public List<SupportSlaView> listSla() {
            return List.copyOf(sla);
        }

        @Override
        public void upsertSla(String category, SupportSlaUpdateRequest request, LocalDateTime now) {
            sla.removeIf(row -> row.category().equals(category));
            sla.add(new SupportSlaView(
                    category,
                    request.firstResponseMins(),
                    request.resolutionHours(),
                    request.queue(),
                    request.escalation(),
                    now));
        }

        private void replaceFaq(String faqId, SupportFaqView updated) {
            for (int index = 0; index < faqs.size(); index += 1) {
                if (faqs.get(index).id().equals(faqId)) {
                    faqs.set(index, updated);
                    return;
                }
            }
        }
    }
}
