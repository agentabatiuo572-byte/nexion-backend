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
    /**
     * The same published version confirmed under any locale for this scope.
     *
     * <p>Every locale carries its own version row, so a user who accepted v6 in
     * one language used to be asked to accept "v6" again after switching
     * languages: the receipt is keyed by locale and the new locale has none. The
     * confirmation is a statement about the published version, not about the
     * language it was rendered in, so the check falls back to the version label
     * within the same environment/run/jurisdiction scope.
     */
    Optional<LegalTermsAcknowledgement> findAckByVersion(Long userId, String sourceEnvironment, String runId,
                                                         String locale, String jurisdiction, String version);
    LegalTermsAcknowledgement saveAck(Long userId, String sourceEnvironment, String runId, String locale, String jurisdiction, String version, String idempotencyKey, LocalDateTime now);

    record LegalTermsAcknowledgement(String version, LocalDateTime acknowledgedAt, String idempotencyKey) { }
}
