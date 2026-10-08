package ffdd.opsconsole.content.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** Internal financial evidence only; the calling application authorizes its root customer scope. */
public interface SupportPaymentCaptureHistoryFacade extends DomainFacade {
    List<Envelope> readNewFinancialProofs(Collection<Long> customerIds);

    record Identity(String factId, long customerId, String kind, String source, long ledgerId,
        String sourceBusinessId, String orderNo, String orderType, String originalFactId,
        String currency, BigDecimal amount, LocalDateTime succeededAt, String sourceBusinessZone,
        String successTimeField, int fractionalSecondDigits) { }

    /** Raw financial JSON is for finance validation, never an external details or aggregate response. */
    record Envelope(Identity identity, String sourcePartition, LocalDateTime captureDbUtc,
        String captureMode, String captureSchemaVersion, String sourceFactJson, String beforeSourceJson,
        String evidenceCaptureMode, String evidenceSchemaVersion) { }
}
