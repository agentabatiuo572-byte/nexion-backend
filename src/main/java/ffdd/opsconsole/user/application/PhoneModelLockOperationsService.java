package ffdd.opsconsole.user.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.common.boundary.PhoneModelLockContract;
import ffdd.opsconsole.common.boundary.PhoneReplacementAdminReadContract;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.Account;
import ffdd.opsconsole.shared.exception.PhoneReplacementException;
import ffdd.opsconsole.shared.security.PhoneReplacementAdminAccess;
import ffdd.opsconsole.user.dto.PhoneReplacementCommands.Confirm;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/** C5 support: see each installation's locked phone model and release it for a genuinely replaced phone. */
@ApplicationService
@RequiredArgsConstructor
public class PhoneModelLockOperationsService {
    private final PhoneReplacementAdminAccess access;
    private final OpsUserService users;
    private final PhoneReplacementAdminReadContract views;
    private final PhoneModelLockContract locks;

    @Transactional(readOnly = true)
    public List<Map<String,Object>> locks(long userId) {
        access.require(PhoneReplacementOperationsService.READ);
        return locks.locks(account(userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> release(long userId, String installationId, Confirm input, String key) {
        long actor = access.require(PhoneReplacementOperationsService.GRANT);
        var account = account(userId);
        if (input == null || !Boolean.TRUE.equals(input.confirmed())) throw new PhoneReplacementException(422, "PHONE_REPLACEMENT_CONFIRMATION_REQUIRED");
        if (input.reason() == null || input.reason().trim().length() < 8 || input.reason().trim().length() > 200)
            throw new PhoneReplacementException(422, "PHONE_REPLACEMENT_REASON_INVALID");
        if (key == null || key.isBlank() || key.length() > 128) throw new PhoneReplacementException(422, "IDEMPOTENCY_KEY_REQUIRED");
        return locks.release(account, installationId, actor, input.reason().trim(), key.trim());
    }

    private Account account(long userId) {
        // The same C-domain support/customer data-scope gate as the phone replacement operations.
        var profile = users.profile(userId);
        if (profile.getCode() != 0 || profile.getData() == null)
            throw new PhoneReplacementException(profile.getCode() == 0 ? 404 : profile.getCode(), profile.getMessage());
        return views.account(userId);
    }
}
