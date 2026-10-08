package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;

public final class A2ReplayContext {
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> OPERATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<AvatarApproval> AVATAR = new ThreadLocal<>();

    public static void enterReplay() {
        enterReplay(null);
    }

    /** Binds the durable A2 ticket to the replay transaction for downstream audit/outbox correlation. */
    public static void enterReplay(String operationId) {
        AVATAR.remove();
        REPLAYING.set(true);
        if (operationId == null || operationId.isBlank()) {
            OPERATION_ID.remove();
        } else {
            OPERATION_ID.set(operationId.trim());
        }
    }

    public static void exitReplay() {
        AVATAR.remove();
        REPLAYING.remove();
        OPERATION_ID.remove();
    }

    public static boolean isReplaying() { return REPLAYING.get(); }
    public static String operationId() { return OPERATION_ID.get(); }

    static boolean isAvatarCommand(AuditReplayCommand command) {
        return command != null && "A".equals(command.domain()) && command.params() != null
                && command.params().get("avatarAssetId") != null
                && ("a1_account_create".equals(command.op()) || "a1_account_update_profile".equals(command.op()));
    }

    static boolean hasAvatarApproval() { return AVATAR.get() != null; }

    static AuditLockTarget avatarTarget(AuditReplayCommand command) {
        if (!isAvatarCommand(command)) return null;
        boolean created = "a1_account_create".equals(command.op());
        Object value = command.params().get(created ? "username" : "accountId");
        if (value == null) return null;
        String id = String.valueOf(value).trim();
        if (created) id = id.toLowerCase(Locale.ROOT);
        else {
            try {
                long numeric = Long.parseLong(id);
                if (numeric <= 0 || numeric > 9_007_199_254_740_991L) return null;
                id = String.valueOf(numeric);
            } catch (NumberFormatException ex) { return null; }
        }
        return id.isBlank() ? null : new AuditLockTarget("A", "account", id);
    }

    static Long authenticatedAdminId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"ADMIN".equals(details.get("subjectType"))) return null;
        try {
            long id = Long.parseLong(authentication.getName());
            return id > 0 && id <= 9_007_199_254_740_991L ? id : null;
        } catch (NumberFormatException ex) { return null; }
    }

    static boolean hasAuthority(String permission) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() && authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.equals(authority.getAuthority()));
    }

    // Only the locked, authorized approval path calls this; snapshot is a separate JSON read from that ticket.
    static void enterAvatarApproval(String operationId, long maker, long checker, AuditReplayCommand snapshot) {
        if (operationId == null || operationId.isBlank() || maker <= 0 || checker <= 0 || maker == checker
                || !Long.valueOf(checker).equals(authenticatedAdminId()) || !isAvatarCommand(snapshot)
                || !hasAuthority("platform_a1_write") || !hasAuthority("platform_a2_operation_approve"))
            throw avatarForbidden();
        enterReplay(operationId);
        AVATAR.set(new AvatarApproval(operationId, maker, checker, snapshot));
    }

    static void requireAvatarCommand(AuditReplayCommand command) {
        var approval = currentAvatar();
        if (approval == null || approval.matched || !approval.command.equals(command)) throw avatarForbidden();
        approval.matched = true;
    }

    /** Bind once, after a real create ID or the original update CAS has succeeded. */
    static void bindAvatarTarget(long admin, String assetId, boolean created) {
        var approval = currentAvatar();
        if (admin <= 0 || admin > 9_007_199_254_740_991L || approval == null || !approval.matched || approval.target != null
                || !assetId.equals(approval.command.params().get("avatarAssetId"))
                || created != "a1_account_create".equals(approval.command.op())) throw avatarForbidden();
        if (!created && !String.valueOf(admin).equals(avatarTarget(approval.command).id()))
            throw avatarForbidden();
        approval.target = admin;
    }

    /** Read-only attachment capability; a replay flag or operation ID alone never grants ownership. */
    public static Long approvedAvatarUploader(long checker, long admin, String assetId) {
        var approval = currentAvatar();
        if (approval == null || !approval.matched || approval.target == null || approval.target != admin
                || approval.checker != checker || !assetId.equals(approval.command.params().get("avatarAssetId")))
            throw avatarForbidden();
        return approval.maker;
    }

    private static AvatarApproval currentAvatar() {
        var approval = AVATAR.get();
        return isReplaying() && approval != null && approval.operationId.equals(operationId())
                && Long.valueOf(approval.checker).equals(authenticatedAdminId())
                && hasAuthority("platform_a1_write") && hasAuthority("platform_a2_operation_approve")
                ? approval : null;
    }

    private static BizException avatarForbidden() { return new BizException(403, "A2_AVATAR_APPROVAL_REQUIRED"); }

    private static final class AvatarApproval {
        final String operationId;
        final long maker;
        final long checker;
        final AuditReplayCommand command;
        boolean matched;
        Long target;
        AvatarApproval(String operationId, long maker, long checker, AuditReplayCommand command) {
            this.operationId = operationId;
            this.maker = maker;
            this.checker = checker;
            this.command = command;
        }
    }
}
