package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.SupportFaqUpsertRequest;
import ffdd.opsconsole.content.dto.SupportSlaUpdateRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SupportKnowledgeRepository {
    List<SupportFaqView> listFaqs();

    Optional<SupportFaqView> findFaq(String faqId);

    SupportFaqView createFaq(String faqId, SupportFaqUpsertRequest request, LocalDateTime now);

    void updateFaq(String faqId, SupportFaqUpsertRequest request, LocalDateTime now);

    void updateFaqStatus(String faqId, String status, LocalDateTime now);

    void deleteFaq(String faqId, LocalDateTime now);

    List<SupportSlaView> listSla();

    void upsertSla(String category, SupportSlaUpdateRequest request, LocalDateTime now);
}
