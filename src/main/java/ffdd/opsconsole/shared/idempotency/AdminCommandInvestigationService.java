package ffdd.opsconsole.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.A2AccessPolicy;
import ffdd.opsconsole.platform.mapper.AuditOperationTicketMapper;
import ffdd.opsconsole.platform.infrastructure.AuditOperationTicketEntity;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.mapper.AdminCommandInvestigationMapper;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdminCommandInvestigationService {
    public static final String INVESTIGATE = "platform_a2_unknown_investigate";
    private final AdminIdempotencyRecordMapper records;
    private final AdminCommandInvestigationMapper investigations;
    private final AdminPermissionCache permissions;
    private final AdminOperatorRoleResolver administrators;
    private final SuperAdminAuthorization superAdministrators;
    private final AuditLogService audit;
    private final A2AccessPolicy a2;
    private final AuditOperationTicketMapper tickets;
    private final ObjectMapper json;
    private final Clock clock;

    public record Request(String scope, String commandKey, String requestHash, String conclusion,
                          String evidenceReference, Long evidenceAuditId) {}

    public record Receipt(long originalRecordId, String scope, String commandKey, String requestHash,
                          String originalMethod, String originalPath, String originalTarget, String originalTargets, long originalAdminId,
                          String originalStatus, boolean locked, boolean newOperationAllowed,
                          AdminCommandInvestigationEntity investigation) {}

    /** Looks up server-owned bindings; a client need not reproduce a business service's hash algorithm. */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<Receipt> discover(String key, String method, String path) {
        long actor = requireAdmin();
        if (key == null || key.isBlank() || key.length() > 128 || !key.equals(key.trim())
                || !Set.of("POST", "PUT", "PATCH", "DELETE").contains(String.valueOf(method))
                || path == null || !path.startsWith("/api/admin/") || path.length() > 512)
            throw new BizException(422, "INVESTIGATION_ORIGINAL_LOCATOR_INVALID");
        boolean investigator = isInvestigator(actor);
        return investigations.findOriginals(key, method, path, actor, investigator).stream()
                .filter(original -> AdminCommandOrigin.supported(original.getScope()) && original.getOriginAdminId() != null
                        && StringUtils.hasText(original.getOriginReadAuthority()))
                .map(original -> {
                    requireReader(original, actor);
                    var latest = investigations.latest(original.getId());
                    requireFactReader(original, latest);
                    return receipt(original, latest);
                }).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Receipt read(String scope, String key, String hash) {
        long actor = requireAdmin();
        validateIdentity(scope, key, hash, false);
        requireInitialReader(scope, actor);
        var original = requireOriginal(records.selectCurrent(scope, key), hash);
        requireReader(original, actor);
        var latest = investigations.latest(original.getId());
        requireFactReader(original, latest);
        return receipt(original, latest);
    }

    /** Records evidence and fences the old command. Never invokes a business writer or accepts a result payload. */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Receipt investigate(Request request) {
        long actor = requireAdmin();
        if (!isInvestigator(actor)) throw denied();
        if (request == null) throw new BizException(422, "INVESTIGATION_REQUEST_REQUIRED");
        validateIdentity(request.scope(), request.commandKey(), request.requestHash(), true);
        requireInitialReader(request.scope(), actor);
        if (!Set.of("SUCCEEDED", "NOT_EXECUTED", "STILL_UNKNOWN").contains(String.valueOf(request.conclusion()))
                || !StringUtils.hasText(request.evidenceReference()) || request.evidenceReference().trim().length() > 512
                || request.evidenceAuditId() != null && request.evidenceAuditId() < 1)
            throw new BizException(422, "INVESTIGATION_EVIDENCE_OR_CONCLUSION_INVALID");
        var original = requireOriginal(investigations.lockOriginal(request.scope(), request.commandKey()), request.requestHash());
        requireReader(original, actor);
        var latest = investigations.latest(original.getId());
        requireFactReader(original, latest);
        if (original.getStatus().startsWith("CONFIRMED_")) return receipt(original, latest);
        // An active writer owns its outcome. A manual investigation must not abort or race that writer.
        if ("PROCESSING".equals(original.getStatus())) throw new BizException(409, "INVESTIGATION_COMMAND_STILL_PROCESSING");
        boolean known = Set.of("SUCCEEDED", "FAILED").contains(original.getStatus());
        if (known && ("STILL_UNKNOWN".equals(request.conclusion())
                || "NOT_EXECUTED".equals(request.conclusion()) && !provenNotExecuted(original)))
            throw new BizException(409, "INVESTIGATION_KNOWN_RESULT_CONFLICT");

        var entry = new AdminCommandInvestigationEntity();
        entry.setOriginalRecordId(original.getId());
        entry.setScope(original.getScope());
        entry.setCommandKey(original.getIdempotencyKey());
        entry.setRequestHash(original.getRequestHash());
        entry.setOriginalMethod(original.getOriginMethod());
        entry.setOriginalPath(original.getOriginPath());
        entry.setOriginalTarget(original.getOriginTarget());
        entry.setOriginalTargets(original.getOriginTargets());
        entry.setOriginalAdminId(original.getOriginAdminId());
        entry.setInvestigatorId(actor);
        entry.setInvestigatedAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
        entry.setRequestedConclusion(request.conclusion());
        entry.setEvidenceReference(request.evidenceReference().trim());
        entry.setConclusion("STILL_UNKNOWN");
        entry.setEvidenceType("INSUFFICIENT_EVIDENCE");
        entry.setIsDeleted(0);

        if ("SUCCEEDED".equals(request.conclusion())) {
            if (successfulReceipt(original)) {
                entry.setConclusion("SUCCEEDED");
                entry.setEvidenceType("ORIGINAL_DURABLE_RECEIPT");
                if ("A2_COMMAND".equals(original.getScope())) {
                    entry.setFactType("A2_OPERATION");
                    entry.setFactId(receiptTicketId(original));
                } else {
                    entry.setFactType("ADMIN_RECEIPT");
                    entry.setFactId(original.getId().toString());
                }
                entry.setFactAction(original.getScope());
            } else if (request.evidenceAuditId() != null) {
                var fact = investigations.successEvidence(request.evidenceAuditId(), original);
                if (allowedFact(original.getScope(), fact)) {
                    entry.setConclusion("SUCCEEDED");
                    entry.setEvidenceType("SUCCESS_AUDIT:" + fact.getId());
                    entry.setFactType(fact.getResourceType());
                    entry.setFactId(fact.getResourceId());
                    entry.setFactAction(fact.getAction());
                }
            }
        } else if ("NOT_EXECUTED".equals(request.conclusion()) && provenNotExecuted(original)) {
            entry.setConclusion("NOT_EXECUTED");
            entry.setEvidenceType(original.getResponseJson() == null
                    ? "CONFIRMED_LOCAL_TRANSACTION_ROLLBACK" : "ORIGINAL_LOCAL_NONEXECUTION_RECEIPT");
            entry.setFactType("ADMIN_RECEIPT");
            entry.setFactId(original.getId().toString());
            entry.setFactAction(original.getScope());
        }
        requireFactReader(original, entry);
        if (known && "STILL_UNKNOWN".equals(entry.getConclusion()))
            throw new BizException(409, "INVESTIGATION_KNOWN_RESULT_CONFLICT");
        String state = switch (entry.getConclusion()) {
            case "SUCCEEDED" -> "CONFIRMED_SUCCEEDED";
            case "NOT_EXECUTED" -> "CONFIRMED_NOT_EXECUTED";
            default -> "UNKNOWN";
        };
        if (investigations.conclude(original.getId(), original.getRequestHash(), state) != 1)
            throw new BizException(409, "INVESTIGATION_ORIGINAL_STATE_CHANGED");
        investigations.insert(entry);
        audit.recordRequired(AuditLogWriteRequest.builder().action("A2_UNKNOWN_OPERATION_INVESTIGATED")
                .resourceType("ADMIN_COMMAND_INVESTIGATION").resourceId(entry.getId().toString())
                .result("SUCCESS").riskLevel("HIGH").detail(Map.of(
                        "originalRecordId", original.getId(), "scope", original.getScope(),
                        "idempotencyKey", original.getIdempotencyKey(), "requestHash", original.getRequestHash(),
                        "conclusion", entry.getConclusion(), "evidenceType", entry.getEvidenceType(),
                        "evidenceReference", entry.getEvidenceReference())).build());
        original.setStatus(state);
        return receipt(original, entry);
    }

    private boolean successfulReceipt(AdminIdempotencyRecordEntity original) {
        if (!"SUCCEEDED".equals(original.getStatus()) || !StringUtils.hasText(original.getResponseJson())) return false;
        try {
            var value = json.readTree(original.getResponseJson());
            if (value == null || !value.isObject() || value.isEmpty()) return false;
            if (value.has("code") && (!value.path("code").isInt() || value.path("code").asInt() != 0)) return false;
            if ("A2_COMMAND".equals(original.getScope())) {
                var ticket = tickets.selectActiveByOperationId(value.path("id").asText(""));
                if (ticket != null && !a2.canAccessTicket(ticket)) throw denied();
                return ticket != null;
            }
            return true;
        } catch (JsonProcessingException invalid) {
            return false;
        }
    }

    private String receiptTicketId(AdminIdempotencyRecordEntity original) {
        try { return json.readTree(original.getResponseJson()).path("id").asText(); }
        catch (JsonProcessingException invalid) { throw new BizException(409, "INVESTIGATION_RECEIPT_UNREADABLE"); }
    }

    private boolean provenNotExecuted(AdminIdempotencyRecordEntity original) {
        if (!AdminCommandOrigin.localConfiguration(original.getScope())) return false;
        if (original.getResponseJson() == null) return Integer.valueOf(1).equals(original.getRollbackConfirmed());
        try {
            var response = json.readTree(original.getResponseJson());
            int code = response.path("code").asInt(0);
            String reason = response.path("message").asText("");
            // These exact local branches return before any business write. Other errors prove nothing.
            return "I5_JURISDICTION_CREATE".equals(original.getScope()) && code == 409
                    && "DISCLOSURE_JURISDICTION_CODE_EXISTS".equals(reason)
                    || "I5_JURISDICTION_UPDATE".equals(original.getScope())
                    && (code == 404 && "DISCLOSURE_JURISDICTION_NOT_FOUND".equals(reason)
                        || code == 409 && "DISCLOSURE_JURISDICTION_ARCHIVED".equals(reason));
        } catch (JsonProcessingException invalid) { return false; }
    }

    private boolean allowedFact(String scope, AuditLogRecord fact) {
        if (fact == null || !StringUtils.hasText(fact.getResourceId()) || !StringUtils.hasText(fact.getResourceType())) return false;
        String action = fact.getAction();
        if ("A2_COMMAND".equals(scope)) return "A2_OPERATION".equals(fact.getResourceType())
                && Set.of("A2_OPERATION_PROPOSED", "A2_OPERATION_EXECUTED", "A2_OPERATION_APPROVED",
                        "A2_OPERATION_REJECTED", "A2_OPERATION_WITHDRAWN").contains(action);
        if ("L_BI_REPORT_CREATE".equals(scope)) return "BI_REPORT".equals(fact.getResourceType())
                && Set.of("L_BI_REPORT_CREATE", "ADMIN.REPORT_EXPORTED").contains(action);
        if (scope.startsWith("L_BI_REPORT_ACTION_")) return "BI_REPORT".equals(fact.getResourceType())
                && action.equals("L_BI_REPORT_" + scope.substring("L_BI_REPORT_ACTION_".length()));
        if (scope.startsWith("E2_POOL_")) return Set.of("E2_TASK_POOL_RANDOM_ADDED", "E2_TASK_POOL_STATUS_CHANGED").contains(action);
        // AuditLogService stores action canonically uppercase, so the whitelist must match the
        // persisted value (e.g. ADMIN.DEVICE_PAUSED) rather than the lowercase writer alias.
        if (scope.startsWith("E5_DEVICE_")) return (scope.startsWith("E5_DEVICE_BATCH_") ? "USER_DEVICE_BATCH" : "DEVICE").equals(fact.getResourceType())
                && Set.of("ADMIN.DEVICE_ACTIVATED", "ADMIN.DEVICE_DEACTIVATION_REQUESTED",
                        "ADMIN.DEVICE_DEACTIVATED", "ADMIN.DEVICE_PAUSED", "ADMIN.DEVICE_RESUMED").contains(action);
        if ("E6_PHONE_RISK_SETTINGS".equals(scope)) return "CONFIG".equals(fact.getResourceType())
                && "ADMIN.PHONE_RISK_SETTINGS_UPDATED".equals(action)
                && "E.compute.phoneRisk.settings".equals(fact.getResourceId());
        return Set.of("D2_WITHDRAWAL_CHAIN_CONFIRMED", "D2_WITHDRAWAL_REVIEW_APPROVE", "D2_WITHDRAWAL_REVIEW_REJECT",
                "D2_WITHDRAWAL_REVIEW_REFUND", "D2_WITHDRAWAL_REVIEW_DELAY", "D2_WITHDRAWAL_REVIEW_FREEZE",
                "D3_FORECAST_CONFIG_CHANGED", "D3_TREASURY_RESERVE_INJECTION", "D3_RECONCILIATION_EXPORTED", "D3_LIABILITIES_EXPORTED", "D3_SENSITIVE_EXPORT_A2_AUDITED",
                "E4_ORDER_STATE_CHANGED", "H2_TRIAL_PARAM_CHANGED", "H2_TRIAL_SESSION_CANCELLED", "H2_TRIAL_AUTO_PUSH_KILLED",
                "K1_MULTI_ACCOUNT_PARAM_CHANGED", "K1_CLUSTER_STATUS_CHANGED", "K1_IP_WHITELIST_UPSERTED",
                "K1_CLUSTER_REVIEW_NOTED", "K1_IP_WHITELIST_DISABLED", "J1_KILLSWITCH_TOGGLED",
                "I5_JURISDICTION_CREATED", "I5_JURISDICTION_UPDATED", "I5_DISCLOSURE_DRAFT_SAVED", "I5_DISCLOSURE_DRAFT_DELETED",
                "I5_DISCLOSURE_PUBLISHED", "I5_DISCLOSURE_MATRIX_CONFIGURED", "I5_DISCLOSURE_MATRIX_ARCHIVED", "I5_DISCLOSURE_GATE_CHANGED",
                "C2_USER_STATUS_CHANGED", "C2_USER_SESSIONS_REVOKED", "C2_ACCOUNT_LIST_UPSERTED", "C2_ACCOUNT_LIST_REMOVED",
                "C2_USER_IMPERSONATION_TERMINATED").contains(action)
                && action.startsWith((scope.startsWith("GROWTH:H2:") ? "H2" : scope.substring(0, 2)) + "_");
    }

    private void requireFactReader(AdminIdempotencyRecordEntity original, AdminCommandInvestigationEntity entry) {
        if ("A2_COMMAND".equals(original.getScope()) && entry != null && "A2_OPERATION".equals(entry.getFactType())) {
            var ticket = tickets.selectActiveByOperationId(entry.getFactId());
            if (ticket == null || !a2.canAccessTicket(ticket)) throw denied();
        }
    }

    private void requireReader(AdminIdempotencyRecordEntity original, long actor) {
        Set<String> grants = permissions.getPermissionCodes(actor);
        if (!grants.contains(original.getOriginReadAuthority())
                || !Long.valueOf(actor).equals(original.getOriginAdminId()) && !isInvestigator(actor)) throw denied();
        if ("A2_COMMAND".equals(original.getScope())) {
            a2.requireProposalReader();
            var ticket = originalA2Ticket(original);
            if (ticket != null) {
                if (!a2.canAccessTicket(ticket)) throw denied();
            } else {
                var scope = a2.current();
                String authority = original.getOriginReadAuthority();
                String domain = authority.substring(authority.indexOf('_') + 1, authority.indexOf('_') + 2).toUpperCase(java.util.Locale.ROOT);
                if (scope.ownActorOnly() ? !Long.valueOf(actor).equals(original.getOriginAdminId()) : !scope.canSee(domain, null)) throw denied();
            }
        }
    }

    private AuditOperationTicketEntity originalA2Ticket(AdminIdempotencyRecordEntity original) {
        if (StringUtils.hasText(original.getResponseJson())) {
            try {
                String id = json.readTree(original.getResponseJson()).path("id").asText("");
                if (!id.isEmpty()) {
                    var ticket = tickets.selectActiveByOperationId(id);
                    if (ticket != null) return ticket;
                }
            } catch (JsonProcessingException invalid) { /* Unreadable receipts grant no row visibility. */ }
        }
        if (StringUtils.hasText(original.getOriginTargets())) {
            try {
                for (var target : json.readTree(original.getOriginTargets())) {
                    if ("A2".equals(target.path("domain").asText()) && "operation".equals(target.path("type").asText())) {
                        var ticket = tickets.selectActiveByOperationId(target.path("id").asText());
                        if (ticket != null) return ticket;
                    }
                }
            } catch (JsonProcessingException invalid) { /* Unreadable targets grant no row visibility. */ }
        }
        var ids = investigations.originalA2Operations(original);
        if (ids.size() > 1) throw denied();
        return ids.isEmpty() ? null : tickets.selectActiveByOperationId(ids.get(0));
    }

    private void requireInitialReader(String scope, long actor) {
        if ("A2_COMMAND".equals(scope)) {
            a2.requireProposalReader();
            return;
        }
        String authority = scope.startsWith("L_BI_") ? "bi_l1_read" : AdminCommandOrigin.readAuthority(scope);
        if (authority == null || !permissions.getPermissionCodes(actor).contains(authority)) throw denied();
    }

    private long requireAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?, ?> details)
                || !"ADMIN".equals(details.get("subjectType"))) throw denied();
        long actor;
        try { actor = Long.parseLong(auth.getName()); }
        catch (NumberFormatException invalid) { throw denied(); }
        if (!administrators.isActiveAdministrator(actor)) throw denied();
        return actor;
    }

    private boolean isInvestigator(long actor) {
        return superAdministrators.isSuperAdmin(SecurityContextHolder.getContext().getAuthentication())
                && permissions.getPermissionCodes(actor).contains(INVESTIGATE);
    }

    private AdminIdempotencyRecordEntity requireOriginal(AdminIdempotencyRecordEntity original, String hash) {
        if (original == null || !Integer.valueOf(0).equals(original.getIsDeleted()))
            throw new BizException(409, "INVESTIGATION_ORIGINAL_NOT_FOUND_REMAINS_LOCKED");
        if (hash != null && !hash.equals(original.getRequestHash())) throw new BizException(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        if (original.getOriginAdminId() == null || !StringUtils.hasText(original.getOriginReadAuthority()))
            throw new BizException(409, "INVESTIGATION_LEGACY_ORIGIN_UNPROVEN_REMAINS_LOCKED");
        return original;
    }

    private void validateIdentity(String scope, String key, String hash, boolean requireHash) {
        if (scope == null || !scope.matches("[A-Z0-9_.:-]{1,96}") || !AdminCommandOrigin.supported(scope)
                || key == null || key.isBlank() || key.length() > 128 || !key.equals(key.trim())
                || requireHash && hash == null || hash != null && !hash.matches("[a-fA-F0-9]{64}"))
            throw new BizException(422, "INVESTIGATION_ORIGINAL_IDENTITY_INVALID");
    }

    private Receipt receipt(AdminIdempotencyRecordEntity original, AdminCommandInvestigationEntity latest) {
        boolean locked = Set.of("UNKNOWN", "PROCESSING").contains(original.getStatus());
        return new Receipt(original.getId(), original.getScope(), original.getIdempotencyKey(), original.getRequestHash(),
                original.getOriginMethod(), original.getOriginPath(), original.getOriginTarget(), original.getOriginTargets(), original.getOriginAdminId(),
                original.getStatus(), locked, "CONFIRMED_NOT_EXECUTED".equals(original.getStatus()), latest);
    }

    private BizException denied() { return new BizException(403, "COMMAND_INVESTIGATION_FORBIDDEN"); }
}
