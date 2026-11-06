package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Locale;
import java.util.Set;

public record H7VoucherDisposition(String voucherId, long expectedVersion) {
    public static final String DELETE = "h7_voucher_delete";
    public static final String REVOKE_AVAILABLE = "h7_voucher_revoke_available";
    public static final String AUTHORITY = "growth_h7_write";
    private static final Set<String> OPERATIONS = Set.of(DELETE, REVOKE_AVAILABLE);

    public static boolean isOperation(AuditReplayCommand command) {
        return command != null && command.op() != null
                && OPERATIONS.contains(command.op().trim().toLowerCase(Locale.ROOT));
    }

    public static H7VoucherDisposition parse(AuditReplayCommand command) {
        if (command == null || !"H".equals(command.domain()) || command.op() == null
                || !OPERATIONS.contains(command.op()) || command.params() == null
                || !command.params().keySet().equals(Set.of("voucherId", "expectedVersion"))
                || !(command.params().get("voucherId") instanceof String id)
                || !id.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new BizException(422, "H7_VOUCHER_DISPOSITION_INVALID");
        }
        Object version = command.params().get("expectedVersion");
        if (!(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() <= 0) {
            throw new BizException(422, "H7_VOUCHER_EXPECTED_VERSION_INVALID");
        }
        return new H7VoucherDisposition(id, ((Number) version).longValue());
    }

    public AuditLockTarget target() {
        return new AuditLockTarget("H", "voucher", voucherId);
    }

    public static boolean canRecover(Set<String> grants) {
        return grants.contains(AUTHORITY);
    }
}
