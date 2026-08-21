package ffdd.opsconsole.content.terms.domain;

import ffdd.opsconsole.content.terms.dto.LegalTermsDraftRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LegalTermsRepository {
    List<LegalTermsVersionView> list(String locale, String jurisdiction);
    Optional<LegalTermsVersionView> findPublished(String locale, String jurisdiction);
    Optional<LegalTermsVersionView> findVersion(String locale, String jurisdiction, String version);
    long currentRevision(String locale, String jurisdiction, String version);
    LegalTermsVersionView saveDraft(LegalTermsDraftRequest request, long expectedRevision, String operator, LocalDateTime now);
    LegalTermsVersionView publish(String locale, String jurisdiction, String version, long expectedRevision, String operator, LocalDateTime now);
    LegalTermsVersionView revoke(String locale, String jurisdiction, String version, long expectedRevision, String operator, LocalDateTime now);
    Optional<LegalTermsAcknowledgement> findAck(Long userId, String sourceEnvironment, String runId, String locale, String jurisdiction);
    LegalTermsAcknowledgement saveAck(Long userId, String sourceEnvironment, String runId, String locale, String jurisdiction, String version, String idempotencyKey, LocalDateTime now);

    record LegalTermsAcknowledgement(String version, LocalDateTime acknowledgedAt, String idempotencyKey) { }
}
