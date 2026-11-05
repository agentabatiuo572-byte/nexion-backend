package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.dto.OpsDashboardExportRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Records permissioned download intent; snapshot data remains the PC's existing JSON. */
@Service
@RequiredArgsConstructor
public class OpsDashboardExportService {
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final A2RuntimePolicy reasonPolicy;
    private final Clock clock;

    public record ExportReceipt(String exportNo, String recordedAt, String format, String fileName) {}

    public ExportReceipt recordExport(String idempotencyKey, OpsDashboardExportRequest request) {
        Long adminId = A2ReplayContext.authenticatedAdminId();
        if (adminId == null) throw new BizException(403, "ADMIN_SUBJECT_REQUIRED");
        String rawReason = request == null ? null : request.reason();
        reasonPolicy.validateReason(rawReason);
        String reason = rawReason.trim();
        String actor = AdminActorResolver.resolve(null);
        String requestHash;
        try {
            requestHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(reason.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
        return idempotency.execute("B0_SNAPSHOT_EXPORT:" + adminId, idempotencyKey, requestHash, ExportReceipt.class, () -> {
            String exportNo = "B0-EXP-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
            String recordedAt = clock.instant().toString();
            String fileName = "uvel-overview-" + exportNo + ".json";
            audit.recordRequired(AuditLogWriteRequest.builder()
                    .action("B0_SNAPSHOT_EXPORT_RECORDED").resourceType("B0_SNAPSHOT_EXPORT")
                    .resourceId(exportNo).bizNo(exportNo).actorId(adminId).actorType("ADMIN").actorUsername(actor)
                    .result("SUCCESS").riskLevel("MEDIUM")
                    .detail(Map.of("sourceDomain", "B0", "format", "JSON", "fileName", fileName,
                            "recordedAt", recordedAt, "reason", reason, "idempotencyKey", idempotencyKey.trim()))
                    .build());
            return new ExportReceipt(exportNo, recordedAt, "JSON", fileName);
        });
    }
}
