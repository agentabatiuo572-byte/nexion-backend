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
                          Integer businessCode, String businessState, Map<String, Object> batch,
                          AdminCommandInvestigationEntity investigation, boolean localTransactionRollbackConfirmed,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                          IntegrityRepairReceipt integrityRepair) {}

    public record IntegrityRepairReceipt(String messageKey, String version, Integer repairedCount) {}

    public record ReceiptStatus(
            String state,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Integer businessCode,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String businessMessage) {
        public ReceiptStatus(String state) {
            this(state, null, null);
        }
    }

    private enum ReceiptFamily { A1_ACCOUNT, A1_PERMISSION, A1_AVATAR_CANCEL, A6, A7, F4, D5, D6, D7, A2_COMMAND }

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

    /** Minimal status for direct writes that predate AdminCommandOrigin. Never returns a response payload. */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ReceiptStatus status(String scope, String commandKey) {
        long actor = requireAdmin();
        ReceiptFamily family = receiptFamily(scope);
        if (family == null) return statusForWiredScope(scope, commandKey, actor);
        if (commandKey == null || commandKey.isBlank() || commandKey.length() > 128
                || !commandKey.equals(commandKey.trim())) {
            throw new BizException(422, "RECEIPT_COMMAND_KEY_INVALID");
        }
        requireReceiptReader(family, actor);
        var original = records.selectCurrent(scope, commandKey);
        if (original == null || !Integer.valueOf(0).equals(original.getIsDeleted())) {
            return new ReceiptStatus("NOT_FOUND");
        }
        if (!receiptOwner(original, family, actor)) return new ReceiptStatus("UNKNOWN");
        return statusOf(original, actor);
    }

    /** Family-less scopes still answer as long as the command is wired and the caller holds its read right. */
    private ReceiptStatus statusForWiredScope(String scope, String commandKey, long actor) {
        String authority = AdminCommandOrigin.supported(scope) ? AdminCommandOrigin.readAuthority(scope) : null;
        if (authority == null) throw new BizException(422, "RECEIPT_SCOPE_INVALID");
        if (commandKey == null || commandKey.isBlank() || commandKey.length() > 128
                || !commandKey.equals(commandKey.trim())) {
            throw new BizException(422, "RECEIPT_COMMAND_KEY_INVALID");
        }
        if (!permissions.getPermissionCodes(actor).contains(authority)) throw denied();
        String storedScope = AdminCommandOrigin.g2ParameterKey(scope) == null ? scope
                : scope.toUpperCase(java.util.Locale.ROOT);
        var original = records.selectCurrent(storedScope, commandKey);
        if (original == null || !Integer.valueOf(0).equals(original.getIsDeleted())) {
            return new ReceiptStatus("NOT_FOUND");
        }
        // Only a stored origin proves who issued the command; rows without one stay unknown.
        if (original.getOriginAdminId() == null || !Long.valueOf(actor).equals(original.getOriginAdminId())) {
            return new ReceiptStatus("UNKNOWN");
        }
        return statusOf(original, actor);
    }

    /** Records evidence and fences the old command. Never invokes a business writer or accepts a result payload. */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Receipt investigate(Request request) {
        long actor = requireAdmin();
        if (!isInvestigator(actor)) throw denied();
        if (request == null) throw new BizException(422, "INVESTIGATION_REQUEST_REQUIRED");
        validateIdentity(request.scope(), request.commandKey(), request.requestHash(), true);
        requireInitialReader(request.scope(), actor);
        if (!Set.of("SUCCEEDED", "NOT_EXECUTED", "STILL_UNKNOWN", "REJECTED").contains(String.valueOf(request.conclusion()))
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
        // A business rejection is only ever concluded from the stored response of this record; the caller
        // supplies neither the code nor the body.
        if ("REJECTED".equals(request.conclusion()) && !businessRejected(original))
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
                if (allowedFact(original, fact)) {
                    entry.setConclusion("SUCCEEDED");
                    entry.setEvidenceType("SUCCESS_AUDIT:" + fact.getId());
                    entry.setFactType(fact.getResourceType());
                    entry.setFactId(fact.getResourceId());
                    entry.setFactAction(fact.getAction());
                }
            }
        } else if ("REJECTED".equals(request.conclusion())) {
            Integer rejectedCode = businessCode(original.getResponseJson());
            // The original reply refused the command. That is not proof that nothing ran, so the
            // conclusion keeps that wording and does not reopen the command.
            entry.setConclusion("REJECTED");
            entry.setEvidenceType("ORIGINAL_BUSINESS_REJECTION:" + rejectedCode);
            entry.setFactType("ADMIN_RECEIPT");
            entry.setFactId(original.getId().toString());
            entry.setFactAction(original.getScope());
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
            case "REJECTED" -> "CONFIRMED_REJECTED";
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

    /**
     * A successful A2 decision stores the ticket body without a top-level business code.
     * Prove the original decision action and its bound ticket before calling it a decision
     * success; proposal records and old rows without origin targets stay unproven.
     */
    private boolean a2DecisionReceipt(AdminIdempotencyRecordEntity original, long actor) {
        if (!"A2_COMMAND".equals(original.getScope())) {
            return false;
        }
        if (!StringUtils.hasText(original.getOriginTargets())) {
            return a2DecisionAudit(original, actor);
        }
        String action = null;
        String boundTicketId = "";
        String receiptTicketId = "";
        try {
            for (var target : json.readTree(original.getOriginTargets())) {
                if (!"A2".equals(target.path("domain").asText())
                        || !"operation".equals(target.path("type").asText())) continue;
                String candidate = target.path("action").asText("");
                if (Set.of("A2_APPROVE", "A2_REJECT", "A2_WITHDRAW", "A2_EXECUTED").contains(candidate)) {
                    action = candidate;
                    boundTicketId = target.path("id").asText("");
                    break;
                }
            }
            if (StringUtils.hasText(original.getResponseJson())) {
                receiptTicketId = json.readTree(original.getResponseJson()).path("id").asText("");
            }
        } catch (JsonProcessingException invalid) {
            return false;
        }
        if (action == null) return false;
        if (StringUtils.hasText(boundTicketId) && StringUtils.hasText(receiptTicketId)
                && !boundTicketId.equals(receiptTicketId)) return false;
        String ticketId = StringUtils.hasText(boundTicketId) ? boundTicketId : receiptTicketId;
        if (!StringUtils.hasText(ticketId)) return false;
        var ticket = tickets.selectActiveByOperationId(ticketId);
        if (ticket == null) return false;
        String expectedStatus = switch (action) {
            case "A2_REJECT" -> "rejected";
            case "A2_WITHDRAW" -> "withdrawn";
            default -> "approved";
        };
        return expectedStatus.equalsIgnoreCase(String.valueOf(ticket.getStatus()).trim());
    }

    /** Legacy A2 successes have no origin targets; prove the same receipt key, decision audit and ticket id. */
    private boolean a2DecisionAudit(AdminIdempotencyRecordEntity original, long actor) {
        String ticketId;
        try {
            if (!StringUtils.hasText(original.getResponseJson())) return false;
            ticketId = json.readTree(original.getResponseJson()).path("id").asText("");
        } catch (JsonProcessingException invalid) {
            return false;
        }
        if (!StringUtils.hasText(ticketId)) return false;
        for (String action : List.of("A2_OPERATION_APPROVED", "A2_OPERATION_REJECTED", "A2_OPERATION_WITHDRAWN")) {
            var decisionAudit = audit.findA2CommandAudit(actor, original.getIdempotencyKey(), action, null);
            if (decisionAudit != null && ticketId.equals(decisionAudit.operationId())) return true;
        }
        return false;
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

    private boolean allowedFact(AdminIdempotencyRecordEntity original, AuditLogRecord fact) {
        String scope = original.getScope();
        if (fact == null || !StringUtils.hasText(fact.getResourceId()) || !StringUtils.hasText(fact.getResourceType())) return false;
        String action = fact.getAction();
        if (AdminCommandOrigin.purchasePaymentPolicyScope(scope)) return "PURCHASE_PAYMENT_POLICY".equals(fact.getResourceType())
                && "1".equals(fact.getResourceId()) && "PURCHASE_PAYMENT_POLICY_UPDATE".equals(action);
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
        if (scope.startsWith("I1_COPY_")) return i1CopyEvidence(scope, fact);
        if (scope.startsWith("I2_NOVA_")) return "NOVA".equals(fact.getResourceType())
                && i2NovaActions(scope).contains(action);
        if ("E6_PHONE_RISK_SETTINGS".equals(scope)) return "CONFIG".equals(fact.getResourceType())
                && "ADMIN.PHONE_RISK_SETTINGS_UPDATED".equals(action)
                && "E.compute.phoneRisk.settings".equals(fact.getResourceId());
        if ("J3_ALERT_CONFIG".equals(scope)) return "TAMPER_ALERT_CONFIG".equals(fact.getResourceType())
                && "J3_TAMPER_ALERT_CONFIG_CHANGED".equals(action);
        if (scope.startsWith("GROWTH:H4:EVENT_CREATE:")) return "GROWTH_EVENT".equals(fact.getResourceType())
                && "H4_EVENT_CREATED".equals(action)
                && fact.getResourceId().equalsIgnoreCase(original.getOriginTarget());
        if (scope.startsWith("GROWTH:H4:WHEEL_TIER_RETIRE")) return "WHEEL_CONFIG".equals(fact.getResourceType())
                && "H4_WHEEL_TIER_RETIRED".equals(action);
        if (scope.startsWith("GROWTH:H1:RHYTHM_PARAM_UPDATE:")) return "GROWTH_RHYTHM".equals(fact.getResourceType())
                && "H1_RHYTHM_CHANGED".equals(action);
        if (scope.startsWith("GROWTH:H1:PHASE_DIAL_UPDATE:")) return "GROWTH_PHASE_DIAL".equals(fact.getResourceType())
                && "H1_PHASE_DIAL_CHANGED".equals(action);
        if (scope.startsWith("GROWTH:H1:PHASE_MONTH_DIAL_UPDATE:")) return "GROWTH_PHASE_MONTH_DIAL".equals(fact.getResourceType())
                && "H1_MONTH_DIAL_CHANGED".equals(action);
        if (scope.startsWith("GROWTH:H1:PHASE_CONTROL_UPDATE:")) return "GROWTH_PHASE_CONTROL".equals(fact.getResourceType())
                && "H1_PHASE_CONTROL_CHANGED".equals(action);
        if (scope.startsWith("GROWTH:H1:PHASE_OVERRIDE_UPDATE:")) return "GROWTH_PHASE_OVERRIDE".equals(fact.getResourceType())
                && "H1_PHASE_OVERRIDE_CHANGED".equals(action);
        if ("A4_EVENT_RETENTION_RUN".equals(scope)) {
            return "A4_EVENT_RETENTION".equals(fact.getResourceType())
                    && "A4_EVENT_RETENTION_MANUAL_EXECUTED".equals(action);
        }
        if (scope.startsWith("A4_PARAM:")) return "A4_EVENT_CENTER_PARAM".equals(fact.getResourceType())
                && "A4_EVENT_PARAM_CHANGED".equals(action);
        if (scope.startsWith("A4_EVENT_LIFECYCLE:")) return "A4_EVENT_LIFECYCLE".equals(fact.getResourceType())
                && "A4_EVENT_LIFECYCLE_CHANGED".equals(action);
        if (scope.startsWith("A4_H3_OUTBOX_REDRIVE:")) return "EVENT_OUTBOX".equals(fact.getResourceType())
                && "A4_H3_OUTBOX_REDRIVEN".equals(action);
        if (scope.startsWith("I4_TRUST_SECTION_")) return i4TrustSectionEvidence(scope, fact);
        if ("I4_TRUST_DOCUMENT".equals(scope)) return "TRUST_DOCUMENT".equals(fact.getResourceType())
                && Set.of("I4_TRUST_DOCUMENT_UPLOADED", "I4_TRUST_DOCUMENT_RESTORED").contains(action);
        if (scope.startsWith("I7_COURSE_")) return i7CourseEvidence(scope, fact);
        if (scope.startsWith("M4_SUPPORT_FAQ_")) return m4FaqEvidence(scope, fact);
        if (scope.startsWith("I3_NOTIFICATION_CAMPAIGN_")) return "NOTIFICATION_CAMPAIGN".equals(fact.getResourceType())
                && i3CampaignActions(scope).contains(action);
        if (scope.startsWith("I6_I18N_")) return i6I18nEvidence(scope, fact);
        // J2 geo writes each publish one success audit with its own resource type; nothing else counts.
        if (scope.startsWith("J2_COUNTRY_LIST")) return "GEO_COUNTRY_LIST".equals(fact.getResourceType())
                && "J2_GEO_COUNTRY_LIST_CHANGED".equals(action);
        if (scope.startsWith("J2_COUNTRY:")) return "GEO_COUNTRY".equals(fact.getResourceType())
                && "J2_GEO_COUNTRY_STATUS_CHANGED".equals(action);
        if (scope.startsWith("J2_ENDPOINT:")) return "GEO_ENDPOINT".equals(fact.getResourceType())
                && "J2_GEO_ENDPOINT_COUNTRIES_CHANGED".equals(action);
        if ("J2_EDGE_SOURCE".equals(scope)) return "GEO_EDGE".equals(fact.getResourceType())
                && "J2_GEO_EDGE_JUDGE_SOURCE_CHANGED".equals(action);
        if ("J2_EMERGENCY_BLOCK".equals(scope)) return "GEO_COUNTRY_BATCH".equals(fact.getResourceType())
                && "J2_GEO_EMERGENCY_BLOCK_CREATED".equals(action);
        if (Set.of("D5_WITHDRAWAL_LIMITS_UPDATE", "D5_WITHDRAWAL_LIMITS").contains(scope)) {
            return "WITHDRAWAL_PARAM".equals(fact.getResourceType())
                    && "D5_WITHDRAWAL_PARAM_CHANGED".equals(action);
        }
        if (Set.of("D6_FX_QUOTE_UPDATE", "D6_FX_QUOTE").contains(scope)) {
            return "FX_QUOTE_CONFIG".equals(fact.getResourceType())
                    && "FX_QUOTE_UPDATED".equals(action);
        }
        if (Set.of("FINANCE:D7:CONFIG_UPDATE", "D7_PAYOUT_VND_CONFIG").contains(scope)) {
            return "PAYOUT_VND_CONFIG".equals(fact.getResourceType())
                    && "D7_PAYOUT_VND_CONFIG_UPDATED".equals(action);
        }
        // J4 playbooks: each scope accepts only its own success audit, never the started/partial variants.
        if ("J4_PLAYBOOK_CREATE".equals(scope)) return "SOP_PLAYBOOK".equals(fact.getResourceType())
                && "J4_SOP_PLAYBOOK_CREATED".equals(action);
        if (scope.startsWith("J4_PLAYBOOK_UPDATE:")) return "SOP_PLAYBOOK".equals(fact.getResourceType())
                && "J4_SOP_PLAYBOOK_EDITED".equals(action);
        if (scope.startsWith("J4_PLAYBOOK_DRILL:")) return "SOP_PLAYBOOK_EXECUTION".equals(fact.getResourceType())
                && "J4_SOP_PLAYBOOK_DRILL_COMPLETED".equals(action);
        if (scope.startsWith("J4_PLAYBOOK_EXECUTE:")) return "SOP_PLAYBOOK_EXECUTION".equals(fact.getResourceType())
                && "J4_SOP_PLAYBOOK_EXECUTED".equals(action);
        if (scope.startsWith("J4_PLAYBOOK_CANCEL:")) return "SOP_PLAYBOOK_EXECUTION".equals(fact.getResourceType())
                && "J4_SOP_PLAYBOOK_CANCEL_REQUESTED".equals(action);
        // Resume records J4_SOP_PLAYBOOK_RECOVERY_STARTED before reconcileStaleExecution and can still end in a
        // partial rejection, so that audit is not proof of a completed recovery: only a retained receipt counts.
        if (scope.startsWith("J4_PLAYBOOK_ROLLBACK:")) return "SOP_PLAYBOOK_EXECUTION".equals(fact.getResourceType())
                && "J4_SOP_PLAYBOOK_ROLLED_BACK".equals(action);
        if (scope.startsWith("F1_VRANK_")) {
            return "TEAM_POLICY".equals(fact.getResourceType())
                    && Set.of("F_TEAM_VRANK_THRESHOLD_CHANGED", "F_TEAM_VRANK_REWARD_ADDED",
                    "F_TEAM_VRANK_REWARD_CHANGED", "F_TEAM_VRANK_REWARD_REMOVED").contains(action);
        }
        if ("F4_AMBASSADOR_POLICY_UPDATE".equals(scope)) {
            return "TEAM_POLICY".equals(fact.getResourceType())
                    && "F4_AMBASSADOR_POLICY_CHANGED".equals(action);
        }
        if (scope.startsWith("F4_MATERIAL_REVIEW:")) {
            return "AMBASSADOR_APPLICATION".equals(fact.getResourceType())
                    && "F4_AMBASSADOR_MATERIAL_REVIEWED".equals(action);
        }
        if ("F_TEAM_CONFIG_UPDATE".equals(scope)) {
            return "TEAM_POLICY".equals(fact.getResourceType())
                    && Set.of("F_TEAM_POLICY_CHANGED", "F_TEAM_UI_CONFIG_CHANGED",
                    "F_TEAM_UNILEVEL_RULE_CHANGED", "F_TEAM_COMMISSION_STATUS_CHANGED",
                    "F_TEAM_AMBASSADOR_REVIEWED", "F_TEAM_LEADERBOARD_CHANGED",
                    "F_TEAM_VRANK_VOTES_CHANGED", "F_TEAM_HARDWARE_QUOTA_UPDATED",
                    "F_TEAM_HARDWARE_QUOTA_RECYCLED", "F_TEAM_LEADERBOARD_MEMBER_DISQUALIFIED").contains(action);
        }
        if (Set.of("F5_COMMISSION_REVERSE", "F5_COMMISSION_REISSUE", "F5_COMMISSION_SUSPENSION",
                "F5_COMMISSION_ANOMALY_CONFIG").contains(scope)) {
            return "COMMISSION_EVENT".equals(fact.getResourceType())
                    && Set.of("ADMIN_COMMISSION_REVERSED", "ADMIN_COMMISSION_REISSUED",
                    "ADMIN_COMMISSION_USER_SUSPENDED", "ADMIN_COMMISSION_USER_RESUMED",
                    "ADMIN_COMMISSION_ANOMALY_CONFIG_CHANGED").contains(action);
        }
        if (AdminCommandOrigin.h9CommandScope(scope)) {
            if ("GROWTH:H9:PUBLIC_STATS_UPDATE:PUBLIC_STATS".equals(scope)) {
                return "GROWTH_PUBLIC_STATS".equals(fact.getResourceType())
                        && "GROWTH_H9_PUBLIC_STATS_UPDATED".equals(action)
                        && ("H9".equals(fact.getResourceId()) || fact.getResourceId().startsWith("H9:"));
            }
            return "GROWTH_DEVICE_RANK".equals(fact.getResourceType())
                    && "GROWTH_H9_DEVICE_RANK_UPDATED".equals(action)
                    && scope.substring("GROWTH:H9:DEVICE_RANK_UPDATE:".length()).toLowerCase(java.util.Locale.ROOT)
                            .equals(fact.getResourceId());
        }
        if (AdminCommandOrigin.a3PlatformParamScope(scope)) {
            return "A3_PLATFORM_PARAM".equals(fact.getResourceType())
                    && "A3_PLATFORM_PARAM_CHANGED".equals(action)
                    && scope.substring("A3_PLATFORM_PARAM:".length()).toLowerCase(java.util.Locale.ROOT)
                            .equals(fact.getResourceId());
        }
        if (AdminCommandOrigin.a3FeatureFlagScope(scope)) {
            // Stored scopes are uppercase; the server-bound target preserves the canonical physical key.
            String physicalKey = original.getOriginTarget();
            return "A3_PLATFORM_CONFIG".equals(fact.getResourceType())
                    && "ADMIN_FEATURE_FLAG_CHANGED".equals(action)
                    && physicalKey != null && physicalKey.equals(fact.getResourceId());
        }
        if ("A3_PLATFORM_EXPERIENCE".equals(scope)) {
            return "A3_PLATFORM_EXPERIENCE".equals(fact.getResourceType())
                    && "A3_PLATFORM_EXPERIENCE_CHANGED".equals(action)
                    && java.util.Set.of("platform.experience.share.base_url",
                            "platform.experience.interface_languages",
                            "platform.experience.operating_languages").contains(fact.getResourceId());
        }
        if (AdminCommandOrigin.userDetailScope(scope)) {
            if (scope.startsWith("USER_PAYMENT_METHOD_UNBIND:")) {
                return "USER_PAYMENT_METHOD".equals(fact.getResourceType())
                        && "USER_PAYMENT_METHOD_UNBIND".equals(action);
            }
            if (scope.startsWith("USER_PAYMENT_METHOD_REBIND_NOTICE:")) {
                return "USER_PAYMENT_METHOD".equals(fact.getResourceType())
                        && "USER_PAYMENT_METHOD_REBIND_NOTICE".equals(action);
            }
            return "USER".equals(fact.getResourceType()) && "USER_NICKNAME_RESET".equals(action);
        }
        if (AdminCommandOrigin.e3TradeinScope(scope)) {
            return "DEVICE".equals(fact.getResourceType())
                    && "ADMIN.TRADEIN_ACTION_EXECUTED".equals(action);
        }
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

    /** I1 copy/experiment writes: the resource type follows the writer's own action derivation. */
    private boolean i1CopyEvidence(String scope, AuditLogRecord fact) {
        String action = fact.getAction();
        String expectedType = action.contains("VERSION_OPTION") ? "CONTENT_COPY_VERSION_OPTION"
                : action.contains("POSITION") ? "CONTENT_COPY_POSITION"
                : action.contains("EXPERIMENT") ? "CONTENT_EXPERIMENT"
                : action.contains("FRAMEWORK") ? "CONTENT_EXPERIMENT_FRAMEWORK" : "CONTENT_COPY";
        if (!expectedType.equals(fact.getResourceType())) return false;
        return switch (scope) {
            case "I1_COPY_CREATE" -> "I1_COPY_CREATED".equals(action);
            case "I1_COPY_DRAFT_SAVE" -> "I1_COPY_DRAFT_SAVED".equals(action);
            case "I1_COPY_VERSION_PUBLISH" -> "I1_COPY_VERSION_PUBLISHED".equals(action);
            case "I1_COPY_VERSION_ROLLBACK" -> "I1_COPY_VERSION_ROLLED_BACK".equals(action);
            case "I1_COPY_VERSION_ARCHIVE" -> "I1_COPY_VERSION_ARCHIVED".equals(action);
            case "I1_COPY_DRAFT_VERSION_DELETE" -> "I1_COPY_DRAFT_VERSION_DELETED".equals(action);
            case "I1_COPY_POSITION_CREATE" -> "I1_COPY_POSITION_CREATED".equals(action);
            case "I1_COPY_POSITION_UPDATE" -> "I1_COPY_POSITION_UPDATED".equals(action);
            case "I1_COPY_POSITION_DELETE" -> "I1_COPY_POSITION_DELETED".equals(action);
            case "I1_COPY_VERSION_OPTION_CREATE" -> "I1_COPY_VERSION_OPTION_CREATED".equals(action);
            case "I1_COPY_VERSION_OPTION_UPDATE" -> "I1_COPY_VERSION_OPTION_UPDATED".equals(action);
            case "I1_COPY_VERSION_OPTION_DELETE" -> "I1_COPY_VERSION_OPTION_DELETED".equals(action);
            case "I1_COPY_EXPERIMENT_FRAMEWORK_UPDATE" -> "I1_EXPERIMENT_FRAMEWORK_UPDATED".equals(action);
            case "I1_COPY_EXPERIMENT_CREATE" -> "I1_EXPERIMENT_CREATED".equals(action);
            case "I1_COPY_EXPERIMENT_START" -> "I1_EXPERIMENT_STARTED".equals(action);
            case "I1_COPY_EXPERIMENT_STOP" -> "I1_EXPERIMENT_STOPPED".equals(action);
            case "I1_COPY_EXPERIMENT_ADOPT" -> "I1_EXPERIMENT_WINNER_ADOPTED".equals(action);
            case "I1_COPY_EXPERIMENT_DISCARD" -> "I1_EXPERIMENT_DISCARDED".equals(action);
            default -> false;
        };
    }

    /** I2 Nova writes: one resource type (NOVA), and only the actions that scope itself writes. */
    private static java.util.Set<String> i2NovaActions(String scope) {
        if (scope.startsWith("I2_NOVA_SOCIAL_EVENT_STATUS:")) return java.util.Set.of("I2_NOVA_SOCIAL_EVENT_STATUS_CHANGED");
        if (scope.startsWith("I2_NOVA_SOCIAL_EVENT_DELETE:")) return java.util.Set.of("I2_NOVA_SOCIAL_EVENT_DELETED");
        if (scope.startsWith("I2_NOVA_SOCIAL_")) return switch (scope) {
            case "I2_NOVA_SOCIAL_SYNC" -> java.util.Set.of("I2_NOVA_SOCIAL_EVENTS_SYNCED");
            case "I2_NOVA_SOCIAL_EXPIRE" -> java.util.Set.of("I2_NOVA_SOCIAL_EVENTS_EXPIRED");
            case "I2_NOVA_SOCIAL_DISTRIBUTION" -> java.util.Set.of("I2_NOVA_SOCIAL_DISTRIBUTION_CHANGED");
            default -> java.util.Set.of();
        };
        if (scope.startsWith("I2_NOVA_CHANNEL_STATUS:")) return java.util.Set.of("I2_NOVA_CHANNEL_RESTORED", "I2_NOVA_CHANNEL_KILLED");
        if (scope.startsWith("I2_NOVA_CHANNEL_UPDATE:")) return java.util.Set.of("I2_NOVA_CHANNEL_UPDATED");
        if (scope.startsWith("I2_NOVA_CHANNEL_DELETE:")) return java.util.Set.of("I2_NOVA_CHANNEL_DELETED");
        if ("I2_NOVA_CHANNEL_CREATE".equals(scope)) return java.util.Set.of("I2_NOVA_CHANNEL_CREATED");
        if (scope.startsWith("I2_NOVA_TEMPLATE_STATUS:")) return java.util.Set.of("I2_NOVA_TEMPLATE_STATUS_CHANGED");
        if (scope.startsWith("I2_NOVA_TEMPLATE_UPDATE:")) return java.util.Set.of("I2_NOVA_TEMPLATE_UPDATED");
        if (scope.startsWith("I2_NOVA_TEMPLATE_DELETE:")) return java.util.Set.of("I2_NOVA_TEMPLATE_DELETED");
        if ("I2_NOVA_TEMPLATE_CREATE".equals(scope)) return java.util.Set.of("I2_NOVA_TEMPLATE_CREATED");
        return java.util.Set.of();
    }

    /** I4 trust-section writes: the section or draft-version resource, with this scope's own action. */
    private boolean i4TrustSectionEvidence(String scope, AuditLogRecord fact) {
        String action = fact.getAction();
        boolean sectionResource = "TRUST_SECTION".equals(fact.getResourceType());
        boolean versionResource = "TRUST_SECTION_VERSION".equals(fact.getResourceType());
        return switch (scope) {
            case "I4_TRUST_SECTION_PUBLISH" -> sectionResource && "I4_TRUST_SECTION_PUBLISHED".equals(action);
            case "I4_TRUST_SECTION_ROLLBACK" -> sectionResource && "I4_TRUST_SECTION_ROLLED_BACK".equals(action);
            case "I4_TRUST_SECTION_ARCHIVE" -> sectionResource && "I4_TRUST_SECTION_ARCHIVED".equals(action);
            case "I4_TRUST_SECTION_DRAFT_CREATE" -> versionResource && "I4_TRUST_SECTION_DRAFT_CREATED".equals(action);
            case "I4_TRUST_SECTION_DRAFT_UPDATE" -> versionResource && "I4_TRUST_SECTION_DRAFT_UPDATED".equals(action);
            case "I4_TRUST_SECTION_DRAFT_DELETE" -> versionResource && "I4_TRUST_SECTION_DRAFT_DELETED".equals(action);
            default -> false;
        };
    }

    /** I7 course writes: the course or course-version resource, with this scope's own action. */
    private boolean i7CourseEvidence(String scope, AuditLogRecord fact) {
        String action = fact.getAction();
        boolean course = "LEARNING_COURSE".equals(fact.getResourceType());
        boolean version = "LEARNING_COURSE_VERSION".equals(fact.getResourceType());
        if (scope.startsWith("I7_COURSE_VERSION_CREATE")) return version && "I7_LEARNING_COURSE_VERSION_CREATED".equals(action);
        if (scope.startsWith("I7_COURSE_VERSION_UPDATE")) return version && "I7_LEARNING_COURSE_VERSION_UPDATED".equals(action);
        if (scope.startsWith("I7_COURSE_VERSION_DELETE")) return version && "I7_LEARNING_COURSE_VERSION_DELETED".equals(action);
        if (scope.startsWith("I7_COURSE_VERSION_PUBLISH")) return version && "I7_LEARNING_COURSE_VERSION_PUBLISHED".equals(action);
        if (scope.startsWith("I7_COURSE_VERSION_ROLLBACK")) return version && "I7_LEARNING_COURSE_VERSION_ROLLED_BACK".equals(action);
        if (scope.startsWith("I7_COURSE_CREATE")) return course && "I7_LEARNING_COURSE_CREATED".equals(action);
        if (scope.startsWith("I7_COURSE_DRAFT_UPDATE")) return course && "I7_LEARNING_COURSE_DRAFT_UPDATED".equals(action);
        if (scope.startsWith("I7_COURSE_DELETE")) return course && "I7_LEARNING_COURSE_DRAFT_DELETED".equals(action);
        if (scope.startsWith("I7_COURSE_PUBLISH")) return course && "I7_LEARNING_COURSE_PUBLISHED".equals(action);
        if (scope.startsWith("I7_COURSE_ARCHIVE")) return course && "I7_LEARNING_COURSE_ARCHIVED".equals(action);
        if (scope.startsWith("I7_COURSE_FEATURED")) return course && "I7_LEARNING_FEATURED_CHANGED".equals(action);
        return false;
    }

    /** M4 FAQ writes: one knowledge resource, and only the action this scope itself writes. */
    private boolean m4FaqEvidence(String scope, AuditLogRecord fact) {
        if (!"SUPPORT_KNOWLEDGE".equals(fact.getResourceType())) return false;
        String action = fact.getAction();
        if (scope.startsWith("M4_SUPPORT_FAQ_CREATE")) return "M4_SUPPORT_FAQ_CREATED".equals(action);
        if (scope.startsWith("M4_SUPPORT_FAQ_UPDATE")) return "M4_SUPPORT_FAQ_UPDATED".equals(action);
        if (scope.startsWith("M4_SUPPORT_FAQ_STATUS")) return "M4_SUPPORT_FAQ_STATUS_CHANGED".equals(action);
        if (scope.startsWith("M4_SUPPORT_FAQ_DELETE")) return "M4_SUPPORT_FAQ_DELETED".equals(action);
        return false;
    }

    /** I3 campaign writes: the campaign resource, and only the action that scope itself writes. */
    private static java.util.Set<String> i3CampaignActions(String scope) {
        return switch (scope) {
            case "I3_NOTIFICATION_CAMPAIGN_CREATE" -> java.util.Set.of("I3_NOTIFICATION_CAMPAIGN_CREATED");
            case "I3_NOTIFICATION_CAMPAIGN_DRAFT_UPDATE" -> java.util.Set.of("I3_NOTIFICATION_CAMPAIGN_DRAFT_SAVED");
            case "I3_NOTIFICATION_CAMPAIGN_SCHEDULE" -> java.util.Set.of("I3_NOTIFICATION_CAMPAIGN_SCHEDULED");
            case "I3_NOTIFICATION_CAMPAIGN_SEND_NOW" -> java.util.Set.of("I3_NOTIFICATION_CAMPAIGN_SEND_NOW");
            case "I3_NOTIFICATION_CAMPAIGN_CANCEL" -> java.util.Set.of("I3_NOTIFICATION_CAMPAIGN_CANCELLED");
            case "I3_NOTIFICATION_CAMPAIGN_DELETE" -> java.util.Set.of("I3_NOTIFICATION_CAMPAIGN_DELETED");
            default -> java.util.Set.of();
        };
    }

    /** I6 i18n writes: each action is audited under its own resource type by the writer. */
    private boolean i6I18nEvidence(String scope, AuditLogRecord fact) {
        String action = fact.getAction();
        String expectedType = switch (action) {
            case "I6_I18N_INTEGRITY_RESCANNED" -> "I18N_SCAN";
            case "I6_I18N_INTEGRITY_FIXED" -> "I18N_INTEGRITY";
            case "I6_I18N_DRAFT_SAVED", "I6_I18N_MESSAGE_PUBLISHED", "I6_I18N_MESSAGE_ARCHIVED",
                 "I6_I18N_MESSAGE_ROLLED_BACK" -> "I18N_MESSAGE";
            default -> "";
        };
        if (!expectedType.equals(fact.getResourceType())) return false;
        if (scope.startsWith("I6_I18N_DRAFT:")) return "I6_I18N_DRAFT_SAVED".equals(action);
        if (scope.startsWith("I6_I18N_PUBLISH:")) return "I6_I18N_MESSAGE_PUBLISHED".equals(action);
        if (scope.startsWith("I6_I18N_ARCHIVE:")) return "I6_I18N_MESSAGE_ARCHIVED".equals(action);
        if (scope.startsWith("I6_I18N_ROLLBACK:")) return "I6_I18N_MESSAGE_ROLLED_BACK".equals(action);
        if (scope.startsWith("I6_I18N_INTEGRITY_FIX:")) return "I6_I18N_INTEGRITY_FIXED".equals(action);
        return "I6_I18N_RESCAN".equals(scope) && "I6_I18N_INTEGRITY_RESCANNED".equals(action);
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
        // Report actions bind the report's actual domain; requireReader checks that stored authority next.
        if (scope.startsWith("L_BI_REPORT_ACTION_")) return;
        if ("F_TEAM_CONFIG_UPDATE".equals(scope)) return; // exact per-key authority is stored on the original
        if (AdminCommandOrigin.a3PlatformParamScope(scope)) return; // exact authority is stored on the original
        if (AdminCommandOrigin.e3TradeinScope(scope)) return; // exact entry authority is stored on the original
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
        if (scope == null || !(scope.matches("[A-Z0-9_.:-]{1,96}")
                || AdminCommandOrigin.h9CommandScope(scope) || AdminCommandOrigin.a3PlatformParamScope(scope)
                || AdminCommandOrigin.a3FeatureFlagScope(scope)
                || "A3_PLATFORM_EXPERIENCE".equals(scope)
                || (AdminCommandOrigin.a4CommandScope(scope) && scope.matches("[A-Za-z0-9_.:-]{1,96}")))
                || !AdminCommandOrigin.supported(scope)
                || key == null || key.isBlank() || key.length() > 128 || !key.equals(key.trim())
                || requireHash && hash == null || hash != null && !hashFormatAllowed(scope, hash))
            throw new BizException(422, "INVESTIGATION_ORIGINAL_IDENTITY_INVALID");
    }

    /**
     * The platform experience writer keeps Integer.toHexString(hashCode()) so an in-flight command can be
     * replayed, which is one to eight lowercase hex characters. The four F5 commission writers use
     * UUID.nameUUIDFromBytes(...).toString(), whose canonical UUID form is 8-4-4-4-12 lowercase hex.
     * Every other scope stores a 64 character digest. Accepting these real stored forms here keeps the
     * exact-match check below unchanged for all scopes.
     */
    private boolean hashFormatAllowed(String scope, String hash) {
        if ("A3_PLATFORM_EXPERIENCE".equals(scope) && hash.matches("[0-9a-f]{1,8}")) return true;
        if (Set.of("F5_COMMISSION_REVERSE", "F5_COMMISSION_REISSUE", "F5_COMMISSION_SUSPENSION",
                "F5_COMMISSION_ANOMALY_CONFIG").contains(scope)
                && hash.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return true;
        return hash.matches("[a-fA-F0-9]{64}");
    }

    private ReceiptFamily receiptFamily(String scope) {
        if (scope == null || scope.length() > 96) return null;
        if (scope.matches("A1:ACCOUNT_(?:CREATE|PASSWORD_RESET):[1-9][0-9]{0,18}")) return ReceiptFamily.A1_ACCOUNT;
        if ("A1_PERMISSION_REGISTER".equals(scope)) return ReceiptFamily.A1_PERMISSION;
        if (scope.matches("A1:AVATAR_CANCEL:[1-9][0-9]{0,18}:[a-f0-9-]{36}")) return ReceiptFamily.A1_AVATAR_CANCEL;
        if (scope.matches("A6_ROLE_(?:CREATE:[A-Za-z0-9_-]{1,64}|UPDATE:[1-9][0-9]{0,18})")) return ReceiptFamily.A6;
        if (scope.matches("A7_MENU_(?:CREATE:[A-Za-z0-9_-]{1,64}|UPDATE:[1-9][0-9]{0,18}|DELETE:[1-9][0-9]{0,18})")) return ReceiptFamily.A7;
        if (scope.matches("F4_MATERIAL_REVIEW:[1-9][0-9]{0,18}:[1-9][0-9]{0,18}")) return ReceiptFamily.F4;
        if ("A2_COMMAND".equals(scope)) return ReceiptFamily.A2_COMMAND;
        if (Set.of("D5_WITHDRAWAL_LIMITS_UPDATE", "D5_WITHDRAWAL_LIMITS").contains(scope)) return ReceiptFamily.D5;
        if (Set.of("D6_FX_QUOTE_UPDATE", "D6_FX_QUOTE").contains(scope)) return ReceiptFamily.D6;
        if (Set.of("FINANCE:D7:CONFIG_UPDATE", "D7_PAYOUT_VND_CONFIG").contains(scope)) return ReceiptFamily.D7;
        return null;
    }

    private void requireReceiptReader(ReceiptFamily family, long actor) {
        String authority = switch (family) {
            case A1_ACCOUNT, A1_PERMISSION, A1_AVATAR_CANCEL -> "platform_a1_read";
            case A6 -> "platform_a6_read";
            case A7 -> "platform_a7_read";
            case F4 -> "network_f4_read";
            case D5 -> "finance_d5_read";
            case D6 -> "finance_d6_read";
            case D7 -> "finance_d7_read";
            case A2_COMMAND -> null; // Owner-only, verified by receiptOwner against originAdminId.
        };
        if (authority != null && !permissions.getPermissionCodes(actor).contains(authority)) throw denied();
    }

    private boolean receiptOwner(AdminIdempotencyRecordEntity original, ReceiptFamily family, long actor) {
        return switch (family) {
            case A1_ACCOUNT -> original.getScope().endsWith(":" + actor);
            case A1_AVATAR_CANCEL -> {
                String[] parts = original.getScope().split(":");
                yield parts.length == 4 && Long.toString(actor).equals(parts[2]);
            }
            case F4 -> {
                String[] parts = original.getScope().split(":");
                yield parts.length == 3 && Long.toString(actor).equals(parts[1]);
            }
            case A1_PERMISSION, A6, A7 -> {
                if (original.getOriginAdminId() != null) {
                    yield Long.valueOf(actor).equals(original.getOriginAdminId());
                }
                List<Long> owners = investigations.findReceiptOwners(original.getScope(), original.getIdempotencyKey());
                yield owners != null && owners.size() == 1 && Long.valueOf(actor).equals(owners.get(0));
            }
            case A2_COMMAND -> {
                if (original.getOriginAdminId() != null) {
                    yield Long.valueOf(actor).equals(original.getOriginAdminId());
                }
                yield audit.findA2CommandAudit(actor, original.getIdempotencyKey(), "A2_OPERATION_APPROVED", null) != null
                        || audit.findA2CommandAudit(actor, original.getIdempotencyKey(), "A2_OPERATION_REJECTED", null) != null
                        || audit.findA2CommandAudit(actor, original.getIdempotencyKey(), "A2_OPERATION_WITHDRAWN", null) != null;
            }
            default -> {
                List<Long> owners = investigations.findReceiptOwners(original.getScope(), original.getIdempotencyKey());
                yield owners != null && owners.size() == 1 && Long.valueOf(actor).equals(owners.get(0));
            }
        };
    }

    private ReceiptStatus statusOf(AdminIdempotencyRecordEntity original, long actor) {
        String state;
        if ("A2_COMMAND".equals(original.getScope())) {
            state = receiptState(original);
            String action = a2OriginAction(original);
            if ("FAILED".equals(state) && a2TicketCommand(original)) {
                // A rolled-back ticket command without a captured rejection has no reliable decision result.
                return new ReceiptStatus("UNKNOWN");
            }
            if ("REJECTED".equals(state) && !Set.of("A2_APPROVE", "A2_REJECT", "A2_WITHDRAW").contains(action)) {
                // A rejected proposal is not a rejected decision. Unknown/old A2 origins are not
                // promoted to a decision outcome either; only an exact decision action may be REJECTED.
                return new ReceiptStatus("UNKNOWN");
            }
            if (("SUCCEEDED".equals(original.getStatus()) || "CONFIRMED_SUCCEEDED".equals(original.getStatus()))
                    && businessCode(original.getResponseJson()) == null
                    && !a2DecisionReceipt(original, actor)) {
                return new ReceiptStatus("UNKNOWN");
            }
        } else {
            state = receiptState(original);
        }
        if ("REJECTED".equals(state)) {
            return new ReceiptStatus(state, businessCode(original.getResponseJson()),
                    businessMessage(original.getResponseJson()));
        }
        return new ReceiptStatus(state);
    }

    /** A ticket command carries a proposal or decision origin target; non-ticket A2 writes do not. */
    private boolean a2TicketCommand(AdminIdempotencyRecordEntity original) {
        if (!"A2_COMMAND".equals(original.getScope()) || !StringUtils.hasText(original.getOriginTargets())) return false;
        try {
            for (var target : json.readTree(original.getOriginTargets())) {
                String action = target.path("action").asText("");
                if (action.startsWith("A2_PROPOSE")
                        || Set.of("A2_APPROVE", "A2_REJECT", "A2_WITHDRAW", "A2_EXECUTED").contains(action)) {
                    return true;
                }
            }
        } catch (JsonProcessingException invalid) {
            return false;
        }
        return false;
    }

    /** First persisted A2 origin target action; empty when legacy rows have no reliable origin. */
    private String a2OriginAction(AdminIdempotencyRecordEntity original) {
        if (!StringUtils.hasText(original.getOriginTargets())) return "";
        try {
            for (var target : json.readTree(original.getOriginTargets())) {
                String action = target.path("action").asText("");
                if (action.startsWith("A2_")) return action;
            }
        } catch (JsonProcessingException invalid) {
            return "";
        }
        return "";
    }

    private String businessMessage(String responseJson) {
        if (!StringUtils.hasText(responseJson)) return null;
        try {
            var value = json.readTree(responseJson);
            return value != null && value.has("message") && value.get("message").isTextual()
                    ? value.get("message").asText() : null;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String receiptState(AdminIdempotencyRecordEntity original) {
        String status = original.getStatus();
        if ("PROCESSING".equals(status)) return "PROCESSING";
        if ("FAILED".equals(status)) {
            // Only the origin-backed failure path persists a trusted business reply; a bare
            // FAILED row (or a legacy row without provenance) still proves no business result.
            boolean trustedFailureReply = original.getOriginAdminId() != null || "A2_COMMAND".equals(original.getScope());
            Integer code = trustedFailureReply ? businessCode(original.getResponseJson()) : null;
            if (code != null && code != 0) return "REJECTED";
            return AdminCommandOrigin.h4DirectScope(original.getScope())
                    || AdminCommandOrigin.g2ParameterKey(original.getScope()) != null ? "UNKNOWN" : "FAILED";
        }
        if ("CONFIRMED_NOT_EXECUTED".equals(status) && AdminCommandOrigin.h4DirectScope(original.getScope())) {
            return "NOT_EXECUTED";
        }
        if ("CONFIRMED_REJECTED".equals(status)) return "REJECTED";
        if ("SUCCEEDED".equals(status) || "CONFIRMED_SUCCEEDED".equals(status)) {
            Integer code = businessCode(original.getResponseJson());
            if (code == null) {
                // A2 decisions store the ticket body without a top-level code; a successful A2_COMMAND
                // record already proves its own decision and must report success instead of UNKNOWN.
                return "A2_COMMAND".equals(original.getScope()) ? "SUCCEEDED" : "UNKNOWN";
            }
            return code == 0 ? "SUCCEEDED" : "REJECTED";
        }
        return "UNKNOWN";
    }

    /**
     * The only accepted rejection fact: the command completed (SUCCEEDED) and its own stored reply
     * carries a valid non-zero business code. FAILED, PROCESSING or a missing code prove nothing.
     */
    private boolean businessRejected(AdminIdempotencyRecordEntity original) {
        if (!"SUCCEEDED".equals(original.getStatus())) return false;
        Integer code = businessCode(original.getResponseJson());
        return code != null && code != 0;
    }

    private Integer businessCode(String responseJson) {
        if (!StringUtils.hasText(responseJson)) return null;
        try {
            var value = json.readTree(responseJson);
            return value != null && value.has("code") && value.get("code").canConvertToInt()
                    ? value.get("code").asInt() : null;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private Receipt receipt(AdminIdempotencyRecordEntity original, AdminCommandInvestigationEntity latest) {
        boolean locked = Set.of("UNKNOWN", "PROCESSING").contains(original.getStatus());
        return new Receipt(original.getId(), original.getScope(), original.getIdempotencyKey(), original.getRequestHash(),
                original.getOriginMethod(), original.getOriginPath(), original.getOriginTarget(), original.getOriginTargets(), original.getOriginAdminId(),
                original.getStatus(), locked, "CONFIRMED_NOT_EXECUTED".equals(original.getStatus()),
                businessCode(original.getResponseJson()), receiptState(original), batchSplit(original), latest,
                original.getResponseJson() == null && provenNotExecuted(original), integrityRepair(original));
    }

    private IntegrityRepairReceipt integrityRepair(AdminIdempotencyRecordEntity original) {
        if (!original.getScope().startsWith("I6_I18N_INTEGRITY_FIX:")
                || !Integer.valueOf(0).equals(businessCode(original.getResponseJson()))) return null;
        try {
            var data = json.readTree(original.getResponseJson()).path("data");
            var key = data.path("messageKey");
            var version = data.path("version");
            if (!key.isTextual() || key.asText().isBlank() || !version.isTextual() || version.asText().isBlank()) return null;
            var count = data.path("repairedCount");
            return new IntegrityRepairReceipt(key.asText(), version.asText(),
                    count.isIntegralNumber() && count.canConvertToInt() ? count.intValue() : null);
        } catch (JsonProcessingException invalid) {
            return null;
        }
    }

    /** Only the D2 batch split is projected: item ids plus the rejection/conflict reason codes. */
    private Map<String, Object> batchSplit(AdminIdempotencyRecordEntity original) {
        if (original.getScope() == null || !original.getScope().startsWith("D2_BATCH_")
                || !StringUtils.hasText(original.getResponseJson())) {
            return null;
        }
        try {
            var data = json.readTree(original.getResponseJson()).path("data");
            if (!data.isObject()) return null;
            Map<String, Object> split = new java.util.LinkedHashMap<>();
            split.put("accepted", textList(data.path("accepted")));
            split.put("rejected", textList(data.path("rejected")));
            java.util.List<Map<String, Object>> conflicts = new java.util.ArrayList<>();
            for (var item : data.path("conflicts")) {
                if (item.isObject()) conflicts.add(Map.of(
                        "withdrawalId", item.path("withdrawalId").asText(),
                        "reason", item.path("reason").asText()));
            }
            split.put("conflicts", conflicts);
            return split;
        } catch (JsonProcessingException invalid) {
            return null;
        }
    }

    private static java.util.List<String> textList(com.fasterxml.jackson.databind.JsonNode node) {
        java.util.List<String> values = new java.util.ArrayList<>();
        if (node.isArray()) node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private BizException denied() { return new BizException(403, "COMMAND_INVESTIGATION_FORBIDDEN"); }
}
