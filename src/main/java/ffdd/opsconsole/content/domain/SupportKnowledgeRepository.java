package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.SupportFaqUpsertRequest;
import ffdd.opsconsole.content.dto.SupportSlaUpdateRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SupportKnowledgeRepository {
    void ensureSeedData(LocalDateTime now);

    List<SupportFaqView> listFaqs();

    Optional<SupportFaqView> findFaq(String faqId);

    SupportFaqView createFaq(String faqId, SupportFaqUpsertRequest request, LocalDateTime now);

    void updateFaq(String faqId, SupportFaqUpsertRequest request, LocalDateTime now);

    default boolean updateFaqCas(String faqId, SupportFaqUpsertRequest request, String expectedStatus, Integer expectedVersion, LocalDateTime now) {
        updateFaq(faqId, request, now);
        return true;
    }

    void updateFaqStatus(String faqId, String status, LocalDateTime now);

    default boolean updateFaqStatusCas(String faqId, String status, String expectedStatus, Integer expectedVersion, LocalDateTime now) {
        updateFaqStatus(faqId, status, now);
        return true;
    }

    void deleteFaq(String faqId, LocalDateTime now);

    default boolean deleteFaqCas(String faqId, String expectedStatus, Integer expectedVersion, LocalDateTime now) {
        deleteFaq(faqId, now);
        return true;
    }

    List<SupportSlaView> listSla();

    default void insertSlaIfMissing(String category, SupportSlaUpdateRequest request, LocalDateTime now) {
        if (listSla().stream().noneMatch(row -> category.equals(row.category()))) {
            upsertSla(category, request, now);
        }
    }

    void upsertSla(String category, SupportSlaUpdateRequest request, LocalDateTime now);

    default boolean updateSlaCas(String category, SupportSlaUpdateRequest request, Long expectedVersion, LocalDateTime now) {
        upsertSla(category, request, now);
        return true;
    }
}
