package ffdd.opsconsole.common.boundary;

import java.util.List;
import java.util.Map;

/** Shared domain contract. Mutations join the caller's existing transaction; never activate a device. */
public interface PhoneReplacementContract extends DomainFacade {
    record Account(long userId, String sourceEnvironment, String runId) {
        public static Account production(long userId) { return new Account(userId, "PRODUCTION", ""); }
        public Account {
            if (userId <= 0 || !List.of("PRODUCTION", "SANDBOX").contains(sourceEnvironment)
                    || runId == null || runId.length() > 96
                    || "PRODUCTION".equals(sourceEnvironment) && !runId.isEmpty()
                    || "SANDBOX".equals(sourceEnvironment) && !runId.matches("[A-Za-z0-9._:-]{1,96}"))
                throw new IllegalArgumentException("account scope");
        }
    }
    record Binding(String installationId, Long userDeviceId, String hardwareKey, Long changedAt, long version) { }
    record Actor(String type, long id) { }
    record Policy(boolean available, Boolean allowReplacement, Long minReplacementIntervalDays,
                  Integer replacementWindowDays, Integer maxReplacementsInWindow, Long policyVersion) { }
    record BindingSummary(String installationId, Long userDeviceId, Long changedAt, long bindingVersion) { }
    record GrantSummary(String grantId, String requestId, long bindingVersion, boolean waiveCount, boolean waiveInterval,
                        long issuedAt, long expiresAt, String status, int remainingUses) { }
    record CurrentRequest(Request request, GrantSummary grant) { }
    record Eligibility(BindingSummary binding, Policy policy, long serverNow, Long usedCount, Long remainingCount,
                       boolean historyComplete, Long historyCompleteFrom, boolean canReplace,
                       Long nextAllowedAt, List<String> blockedReasons, GrantSummary grant) { }
    record Request(String requestId, String requestNo, long userId, String sourceEnvironment, String runId,
                   String targetInstallationId, long bindingVersion, String reason, long createdAt, long expiresAt,
                   String status, String grantId, String supportRef, String supportLinkStatus,
                   String closeReason, Long closedAt) { }
    record Grant(String grantId, String requestId, long userId, String sourceEnvironment, String runId,
                 String targetInstallationId, long bindingVersion, boolean waiveCount, boolean waiveInterval,
                 long issuedAt, long expiresAt, String status, String reason, String evidenceJson, long operatorId,
                 String consumedOperationId, Long consumedAt, Long terminalAt) { }
    record Operation(String operationId, long userId, String sourceEnvironment, String runId,
                     String actorType, long actorId, String commandType, String idempotencyKey,
                     String requestHash, String status, long submittedAt, Long completedAt,
                     String originalResultJson, String resourceType, String resourceId, String auditRef) { }
    record Receipt(String operationId, String commandType, String status, long submittedAt, Long completedAt,
                   Object originalResult, Object currentResource, String auditRef) { }
    /** Snapshot resolved through the content facade after current administrator authorization. */
    record VerifiedEvidence(String reference, long accountId, long verifiedByAdminId,
                            boolean explicitlyVerified, Map<String,Object> snapshot) { }
    record IssueInput(String requestId, long expectedBindingVersion, boolean waiveCount,
                      boolean waiveInterval, String reason) { }

    Policy currentPolicy();
    Policy lockCurrentPolicy();
    Map<String, String> validatePolicy(Policy candidate, long serverNow);
    /** Internal metadata only; callers must authorize the target account before returning any receipt. */
    Operation adminOperationContext(String operationId);
    Operation adminOperationContextByKey(long actorId, String commandType, String idempotencyKey, String sourceEnvironment, String runId);

    Receipt createRequest(Account account, String installationId, long expectedBindingVersion, String reason,
                          String idempotencyKey, Runnable currentAppCheck);
    Receipt linkSupport(Account account, String requestId, String supportRef, long verifiedSupportAccountId,
                        String idempotencyKey, Runnable currentAppCheck);
    Receipt issueGrant(Account account, long adminId, IssueInput input, VerifiedEvidence evidence, String idempotencyKey);
    Receipt closeRequest(Account account, long adminId, String requestId, String reason, String idempotencyKey);
    Receipt revokeGrant(Account account, long adminId, String grantId, String reason, String idempotencyKey);

    Eligibility eligibility(Account account, String targetInstallationId, String targetHardwareKey,
                            boolean currentExecutableApp, List<String> hardBlockedReasons);
    Receipt operation(Account account, String operationId);
    Receipt userOperation(Account account, String operationId);
    Receipt operationByKey(Account account, Actor actor, String commandType, String idempotencyKey);
    CurrentRequest currentRequest(Account account, String installationId);
    List<Request> requests(Account account, int offset, int limit);
    List<Map<String, Object>> replacementEvents(Account account, int offset, int limit);
}
