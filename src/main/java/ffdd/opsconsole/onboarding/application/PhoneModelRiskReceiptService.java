package ffdd.opsconsole.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.PhoneReplacementAdminAccess;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Read-only receipt of one E6 risk-settings save. Never claims, resets, executes the writer or
 * infers a result from the current configuration; only the durable original response is returned.
 */
@Service
@RequiredArgsConstructor
public class PhoneModelRiskReceiptService {
    /** Same gate as the E6 recovery path: an active super administrator holding the investigation right. */
    static final String INVESTIGATE = "platform_a2_unknown_investigate";
    private static final String SCOPE = PhoneModelRiskAdminService.IDEMPOTENCY_SCOPE;

    private final AdminIdempotencyRecordMapper records;
    private final AdminIdempotencyTransactionExecutor executor;
    private final PhoneReplacementAdminAccess access;
    private final AdminPermissionCache permissions;
    private final SuperAdminAuthorization superAdministrators;
    private final ObjectMapper json;

    public record Receipt(String scope, String commandKey, String originalMethod, String originalPath,
                          String originalTarget, String originalAdminId, String requestHash, String status,
                          Map<String, Object> originalResponse) {}

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Receipt read(String commandKey) {
        long actor = access.require("device_e6_read");
        if (commandKey == null || commandKey.isBlank() || commandKey.length() > 128 || !commandKey.equals(commandKey.trim()))
            throw new BizException(422, "IDEMPOTENCY_KEY_INVALID");
        AdminIdempotencyRecordEntity record = records.selectCurrent(SCOPE, commandKey);
        if (record == null || !Integer.valueOf(0).equals(record.getIsDeleted()))
            throw new BizException(409, "PHONE_RISK_RECEIPT_NOT_FOUND");
        if (record.getOriginAdminId() == null || !StringUtils.hasText(record.getOriginReadAuthority())
                || !StringUtils.hasText(record.getOriginPath()))
            throw new BizException(409, "PHONE_RISK_RECEIPT_LEGACY_ORIGIN_UNPROVEN");
        if ("PROCESSING".equals(record.getStatus()) && record.getExpiresAt() != null
                && record.getExpiresAt().isBefore(LocalDateTime.now()))
            throw new BizException(409, "IDEMPOTENCY_RESULT_UNKNOWN");
        requireVisible(record, actor);
        return new Receipt(record.getScope(), record.getIdempotencyKey(), record.getOriginMethod(),
                record.getOriginPath(), record.getOriginTarget(), String.valueOf(record.getOriginAdminId()),
                record.getRequestHash(), record.getStatus(), originalResponse(record));
    }

    /** Only the originating administrator, or the existing super-administrator investigator policy. */
    private void requireVisible(AdminIdempotencyRecordEntity record, long actor) {
        if (record.getOriginAdminId() == actor) return;
        if (!permissions.getPermissionCodes(actor).contains("device_e6_read")) throw denied();
        if (superAdministrators.isSuperAdmin(SecurityContextHolder.getContext().getAuthentication())
                && permissions.getPermissionCodes(actor).contains(INVESTIGATE)) return;
        throw denied();
    }

    /** A legible original writer response in either successful state is projected as data; an absent or unreadable one stays null. */
    private Map<String, Object> originalResponse(AdminIdempotencyRecordEntity record) {
        // A generic investigation may confirm this receipt as CONFIRMED_SUCCEEDED while the
        // original success response_json is untouched; both successful states expose the same data.
        if (!Set.of("SUCCEEDED", "CONFIRMED_SUCCEEDED").contains(record.getStatus())
                || !StringUtils.hasText(record.getResponseJson())) return null;
        try {
            Object value = json.readValue(record.getResponseJson(), ApiResult.class).getData();
            if (!(value instanceof Map<?, ?> data)) return null;
            Map<String, Object> projected = new LinkedHashMap<>();
            projected.put("settings", data.get("settings"));
            projected.put("stats", data.get("stats"));
            return projected;
        } catch (Exception unreadable) {
            return null;
        }
    }

    private BizException denied() { return new BizException(403, "PHONE_RISK_RECEIPT_FORBIDDEN"); }
}
