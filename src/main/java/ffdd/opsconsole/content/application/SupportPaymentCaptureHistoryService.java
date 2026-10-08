package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade;
import ffdd.opsconsole.content.mapper.SupportPaymentCaptureHistoryMapper;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;

/** The finance caller owns the RR snapshot; this reader has no writer dependency or ownership gate. */
@ApplicationService
@RequiredArgsConstructor
public class SupportPaymentCaptureHistoryService implements SupportPaymentCaptureHistoryFacade {
    private static final String SCHEMA_VERSION = "support-payment-attribution-v1";
    private final SupportPaymentCaptureHistoryMapper mapper;

    @Override
    public List<Envelope> readNewFinancialProofs(Collection<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()
                || customerIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("INVALID_CAPTURE_HISTORY_SCOPE");
        var scope = new TreeSet<>(customerIds);
        var rows = mapper.readNewFinancialProofs(List.copyOf(scope));
        if (rows == null) throw new IllegalStateException("INVALID_CAPTURE_HISTORY_ROW");
        if (rows.stream().anyMatch(row -> row != null && row.customerId() != null && !scope.contains(row.customerId())))
            throw new IllegalStateException("INVALID_CAPTURE_HISTORY_SCOPE");
        return rows.stream().map(SupportPaymentCaptureHistoryService::envelope).toList();
    }

    private static Envelope envelope(SupportPaymentCaptureHistoryMapper.Row row) {
        if (row == null || row.customerId() == null
                || row.ledgerId() == null || row.ledgerId() <= 0 || !present(row.factId())
                || !present(row.kind()) || !present(row.source()) || !present(row.sourceBusinessId())
                || !present(row.currency()) || row.amount() == null || row.amount().signum() <= 0
                || row.succeededAt() == null || !present(row.sourceBusinessZone())
                || !present(row.successTimeField()) || row.fractionalSecondDigits() == null
                || row.fractionalSecondDigits() < 0 || row.fractionalSecondDigits() > 6
                || row.captureDbUtc() == null || !present(row.sourceFactJson()) || !present(row.beforeSourceJson()))
            throw new IllegalStateException("INVALID_CAPTURE_HISTORY_ROW");
        if (!"NEW_SUCCESS".equals(row.captureMode()) || !"NEW_SUCCESS".equals(row.evidenceCaptureMode())
                || !SCHEMA_VERSION.equals(row.captureSchemaVersion()) || !SCHEMA_VERSION.equals(row.evidenceSchemaVersion()))
            throw new IllegalStateException("INVALID_CAPTURE_HISTORY_SCHEMA");
        var identity = new Identity(row.factId(), row.customerId(), row.kind(), row.source(), row.ledgerId(),
            row.sourceBusinessId(), row.orderNo(), row.orderType(), row.originalFactId(), row.currency(),
            row.amount(), row.succeededAt(), row.sourceBusinessZone(), row.successTimeField(), row.fractionalSecondDigits());
        return new Envelope(identity, row.sourcePartition(), row.captureDbUtc(), row.captureMode(),
            row.captureSchemaVersion(), row.sourceFactJson(), row.beforeSourceJson(),
            row.evidenceCaptureMode(), row.evidenceSchemaVersion());
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
