package ffdd.opsconsole.user.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.common.boundary.PhoneModelLockContract;
import ffdd.opsconsole.common.boundary.PhoneReplacementAdminReadContract;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.Account;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.PhoneReplacementException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.security.PhoneReplacementAdminAccess;
import ffdd.opsconsole.user.dto.PhoneReplacementCommands.ModelLockRelease;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** C5 support: see each installation's locked phone model and release it for a genuinely replaced phone. */
@ApplicationService
@RequiredArgsConstructor
public class PhoneModelLockOperationsService {
    private static final String RELEASE_SCOPE = "C5_PHONE_MODEL_LOCK_RELEASE";
    private static final Set<String> SUCCEEDED_STATUSES = Set.of("SUCCEEDED", "CONFIRMED_SUCCEEDED");
    private final PhoneReplacementAdminAccess access;
    private final OpsUserService users;
    private final PhoneReplacementAdminReadContract views;
    private final PhoneModelLockContract locks;
    private final AdminIdempotencyService idempotency;
    private final AdminIdempotencyRecordMapper receipts;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public List<Map<String,Object>> locks(long userId) {
        access.require(PhoneReplacementOperationsService.READ);
        return locks.locks(account(userId));
    }

    /** Result-unknown recovery: the receipt is only visible to the administrator who sent the command. */
    @Transactional(readOnly = true)
    public Map<String,Object> releaseReceipt(long userId, String installationId, String key) {
        long actor = access.require(PhoneReplacementOperationsService.READ);
        // Re-apply the current C5 customer data-scope/environment gate before any receipt is disclosed.
        var account = account(userId);
        String normalizedKey = receiptKey(key);
        AdminIdempotencyRecordEntity record = receipts.selectCurrent(RELEASE_SCOPE, normalizedKey);
        if (record == null || !Integer.valueOf(0).equals(record.getIsDeleted()))
            throw new PhoneReplacementException(404, "PHONE_MODEL_LOCK_RELEASE_NOT_FOUND");
        if (record.getOriginAdminId() == null || record.getOriginAdminId() != actor
                || !StringUtils.hasText(record.getOriginTarget())
                || !record.getOriginTarget().equals(releaseTarget(userId, installationId,
                        account.sourceEnvironment(), account.runId())))
            throw new PhoneReplacementException(403, "PHONE_MODEL_LOCK_RELEASE_RECEIPT_FORBIDDEN");
        if ("PROCESSING".equals(record.getStatus()) && record.getExpiresAt() != null
                && record.getExpiresAt().isBefore(LocalDateTime.now()))
            throw new PhoneReplacementException(409, "IDEMPOTENCY_RESULT_UNKNOWN");
        Map<String,Object> receipt = new LinkedHashMap<>();
        receipt.put("commandKey", normalizedKey);
        receipt.put("userId", userId);
        receipt.put("installationId", installationId);
        receipt.put("status", record.getStatus());
        receipt.put("response", originalResponse(record));
        return receipt;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> release(long userId, String installationId, ModelLockRelease input, String key) {
        long actor = access.require(PhoneReplacementOperationsService.GRANT);
        var account = account(userId);
        if (input == null || !Boolean.TRUE.equals(input.confirmed())) throw new PhoneReplacementException(422, "PHONE_REPLACEMENT_CONFIRMATION_REQUIRED");
        if (input.reason() == null || input.reason().trim().length() < 8 || input.reason().trim().length() > 200)
            throw new PhoneReplacementException(422, "PHONE_REPLACEMENT_REASON_INVALID");
        if (key == null || key.isBlank() || key.length() > 128) throw new PhoneReplacementException(422, "IDEMPOTENCY_KEY_REQUIRED");
        if (input.expectedRevision() == null || input.expectedRevision() < 0) throw new PhoneReplacementException(422, "PHONE_REPLACEMENT_COMMAND_INVALID");
        String normalizedKey = key.trim();
        String reason = input.reason().trim();
        // The command identity binds actor, user, installation, revision and the exact body. A retry with the
        // same key returns the stored receipt; the same key for a different request is a conflict.
        String requestHash = releaseHash(actor, userId, installationId, input.expectedRevision(), reason,
                account.sourceEnvironment(), account.runId());
        return idempotency.executeBound(RELEASE_SCOPE, normalizedKey, requestHash, Map.class,
                PhoneReplacementOperationsService.READ,
                releaseTarget(userId, installationId, account.sourceEnvironment(), account.runId()),
                () -> locks.release(account, installationId, input.expectedRevision(), actor, reason, normalizedKey));
    }

    private Map<String,Object> originalResponse(AdminIdempotencyRecordEntity record) {
        if (!SUCCEEDED_STATUSES.contains(record.getStatus()) || !StringUtils.hasText(record.getResponseJson()))
            return null;
        try {
            return json.readValue(record.getResponseJson(), Map.class);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static String releaseTarget(long userId, String installationId, String environment, String runId) {
        return "userId=" + userId + ";installationId=" + (installationId == null ? "" : installationId)
                + ";sourceEnvironment=" + environment + ";runId=" + runId;
    }

    private static String receiptKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || !key.equals(key.trim()))
            throw new PhoneReplacementException(422, "IDEMPOTENCY_KEY_REQUIRED");
        return key;
    }

    private static String releaseHash(long actor, long userId, String installationId, long expectedRevision, String reason,
                                      String environment, String runId) {
        String canonical = actor + "|" + userId + "|" + installationId + "|" + expectedRevision + "|" + reason
                + "|" + environment + "|" + runId;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", unavailable);
        }
    }

    private Account account(long userId) {
        // The same C-domain support/customer data-scope gate as the phone replacement operations.
        var profile = users.profile(userId);
        if (profile.getCode() != 0 || profile.getData() == null)
            throw new PhoneReplacementException(profile.getCode() == 0 ? 404 : profile.getCode(), profile.getMessage());
        return views.account(userId);
    }
}
