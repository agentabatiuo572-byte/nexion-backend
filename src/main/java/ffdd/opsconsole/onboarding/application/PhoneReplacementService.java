package ffdd.opsconsole.onboarding.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.onboarding.mapper.PhoneReplacementMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * One phone replacement domain. The existing activation service owns binding/device/task writes.
 * Mutation hooks MUST join its transaction, after policy -> account -> current App -> binding locking.
 */
@Service
public class PhoneReplacementService {
    private final PhoneReplacementMapper mapper;
    private final Clock clock;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    private final ObjectMapper json = new ObjectMapper().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public PhoneReplacementService(PhoneReplacementMapper mapper, Clock clock, AuditLogService audit, EventOutboxService outbox) {
        this.mapper = mapper; this.clock = clock; this.audit = audit; this.outbox = outbox;
    }

    public record LockedPolicy(PhoneReplacementPolicy policy, Long policyVersion) { }
    public record LockedAccount(Account account, Binding binding, PhoneReplacementMapper.State history,
                                PhoneReplacementPolicy policy, Long policyVersion, long serverNow) { }
    public record Command(Operation operation, boolean replay) { }
    public record ActivationInput(Long expectedBindingVersion, boolean bindingVersionPresent, Long expectedPolicyVersion,
                                  boolean replacementConfirmed, String targetInstallationId, String targetHardwareKey,
                                  String exceptionGrantId) { }
    public record ActivationPermit(boolean replacement, Grant grant, ActivationInput input) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedPolicy lockPolicy() {
        requireTransaction();
        List<PhoneReplacementMapper.Config> rows = mapper.lockPolicy();
        Long version = rows.stream().filter(r -> PhoneReplacementPolicy.VERSION_KEY.equals(r.configKey()))
                .map(PhoneReplacementMapper.Config::configValue).map(PhoneReplacementService::validVersion)
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new LockedPolicy(policy(rows),version);
    }

    @Transactional(readOnly = true)
    public Policy currentPolicy() {
        var rows = currentRead() ? mapper.lockPolicy() : mapper.policy();
        Policy result = view(policy(rows));
        if (result.available()) return result;
        Long version = rows.stream().filter(r -> PhoneReplacementPolicy.VERSION_KEY.equals(r.configKey()))
                .map(PhoneReplacementMapper.Config::configValue).map(PhoneReplacementService::validVersion)
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new Policy(false,null,null,null,null,version);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Policy lockCurrentPolicy() {
        requireTransaction();
        var rows=mapper.lockPublicationPolicy();
        var parsed=policy(rows);
        if(parsed!=null)return view(parsed);
        Long version=rows.stream().filter(r->PhoneReplacementPolicy.VERSION_KEY.equals(r.configKey()))
                .map(PhoneReplacementMapper.Config::configValue).map(PhoneReplacementService::validVersion)
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new Policy(false,null,null,null,null,version);
    }

    public Map<String,String> validatePolicy(Policy candidate, long now) {
        if (candidate == null || candidate.allowReplacement() == null || candidate.minReplacementIntervalDays() == null
                || candidate.replacementWindowDays() == null || candidate.maxReplacementsInWindow() == null || candidate.policyVersion() == null)
            return Map.of("policy", "手机策略字段必须齐全");
        return new PhoneReplacementPolicy(candidate.allowReplacement(),candidate.minReplacementIntervalDays(),
                candidate.replacementWindowDays(),candidate.maxReplacementsInWindow(),candidate.policyVersion()).fieldErrors(now);
    }

    public Operation adminOperationContext(String id) { return mapper.adminOperationContext(id); }
    public Operation adminOperationContextByKey(long actor, String type, String key,String environment,String run) {
        return mapper.adminOperationContextByKey(actor,type,key,environment,run);
    }

    /** currentAppCheck is supplied by the existing session authority, not a second session implementation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedAccount lockAccount(LockedPolicy lockedPolicy, Account account, Runnable currentAppCheck) {
        return lockAccount(lockedPolicy,account,currentAppCheck,true);
    }

    /** Administrative revocation and original receipt recovery remain available for a frozen account. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedAccount lockAdminAccount(LockedPolicy lockedPolicy, Account account) {
        return lockAccount(lockedPolicy,account,null,false);
    }

    private LockedAccount lockAccount(LockedPolicy lockedPolicy, Account account, Runnable currentAppCheck, boolean activeRequired) {
        requireTransaction();
        if (lockedPolicy == null) throw new IllegalArgumentException("Policy lock must precede account lock");
        String status=mapper.lockUser(account.userId());
        if (status == null || activeRequired && !"ACTIVE".equals(status)) throw failure(403, "USER_AUTH_REQUIRED");
        if (currentAppCheck != null) currentAppCheck.run();
        Binding binding = mapper.lockBinding(account);
        mapper.ensureState(account);
        return new LockedAccount(account, binding, readState(account), lockedPolicy.policy(),lockedPolicy.policyVersion(), clock.millis());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Command openOperation(LockedAccount locked, Actor actor, String commandType, String key, Object input) {
        requireTransaction();
        validateActor(actor);
        if ("USER".equals(actor.type()) && actor.id() != locked.account().userId()) throw failure(403, "PERMISSION_DENIED");
        if (commandType == null || !commandType.matches("[A-Z_]{1,48}") || key == null
                || key.isBlank() || key.length() > 128) throw failure(422, "IDEMPOTENCY_KEY_INVALID");
        String hash = hash(Map.of("account", locked.account(), "input", input));
        Operation prior = readOperationByKey(locked.account(), actor, commandType, key);
        if (prior != null) {
            if (prior.userId() != locked.account().userId() || !hash.equals(prior.requestHash()))
                throw failure(409, "IDEMPOTENCY_MISMATCH");
            return new Command(prior, true);
        }
        String id = id("PRO");
        // The unique actor/type/key index also fences concurrent cross-account reuse.
        try {
            requireOne(mapper.insertOperation(locked.account(), actor, id, commandType, key, hash, clock.millis()));
        } catch (org.springframework.dao.DuplicateKeyException collision) {
            throw failure(409, "IDEMPOTENCY_MISMATCH");
        }
        return new Command(readOperation(locked.account(), id), false);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BizException.class)
    public ActivationPermit prepareActivation(LockedAccount locked, ActivationInput input, List<String> hardBlockedReasons) {
        requireTransaction();
        installation(input.targetInstallationId());
        Binding binding = locked.binding();
        if (!input.bindingVersionPresent() || !Objects.equals(input.expectedBindingVersion(),
                binding == null ? null : binding.version())) throw failure(409, "BINDING_VERSION_CHANGED");
        boolean replacement = binding != null && PhoneReplacementPolicy.isReplacement(binding.installationId(),
                binding.hardwareKey(), input.targetInstallationId(), input.targetHardwareKey());
        // Existing calibration, capability, terms and risk checks apply to every new activation.
        if (hardBlockedReasons != null && !hardBlockedReasons.isEmpty()) throw failure(409, hardBlockedReasons.get(0));
        if (!replacement) {
            if (locked.policyVersion() != null && !Objects.equals(input.expectedPolicyVersion(),locked.policyVersion()))
                throw failure(409,"POLICY_VERSION_CHANGED");
            return new ActivationPermit(false, null, input);
        }
        if (!input.replacementConfirmed()) throw failure(409, "PHONE_REPLACEMENT_CONFIRMATION_REQUIRED");
        if (locked.policy() == null) throw failure(503, "PHONE_REPLACEMENT_POLICY_UNAVAILABLE");
        if (!Objects.equals(input.expectedPolicyVersion(), locked.policy().policyVersion()))
            throw failure(409, "POLICY_VERSION_CHANGED");
        long now = clock.millis();
        Grant grant = null;
        if (input.exceptionGrantId() != null) {
            grant = readGrant(locked.account(), input.exceptionGrantId());
            if (!usable(grant, binding, now) || !input.targetInstallationId().equals(grant.targetInstallationId()))
                throw failure(409, "GRANT_STATE_CHANGED");
        }
        PhoneReplacementPolicy.Decision decision = decision(locked.account(), binding, locked.history(), locked.policy(),
                now, grant != null && grant.waiveCount(), grant != null && grant.waiveInterval(), List.of());
        if (!decision.canReplace()) throw failure(409, decision.blockedReasons().get(0));
        return new ActivationPermit(true, grant, input);
    }

    /** Call after binding/device/task writes and before commit. Any failure rolls the enclosing activation back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt recordActivationSuccess(LockedAccount locked, ActivationPermit permit, Command command,
                                           Binding newBinding, Map<String,Object> originalResult) {
        requireTransaction();
        if (command.replay()) return receipt(command.operation());
        requirePending(command);
        if (newBinding == null || !permit.input().targetInstallationId().equals(newBinding.installationId())
                || newBinding.version() <= (locked.binding() == null ? 0 : locked.binding().version()))
            throw failure(409, "BINDING_VERSION_CHANGED");
        long now = clock.millis();
        // Preserve lifecycle order: expiry against the prior binding precedes this binding change.
        materialize(locked.account(), locked.binding(), now);
        String operationId = command.operation().operationId();
        Grant grant = permit.grant();
        if (permit.replacement()) {
            if (grant != null) {
                Grant current = readGrant(locked.account(), grant.grantId());
                if (!usable(current, locked.binding(), now)) throw failure(409, "GRANT_STATE_CHANGED");
                requireOne(mapper.transitionGrant(locked.account(), grant.grantId(), "CONSUMED", now, operationId, now));
                emit(locked.account(), actor(command), "admin.phone_replacement_grant_consumed", grant.grantId(),
                        operationId, Map.of("grantId", grant.grantId(), "status", "CONSUMED"));
            }
            requireOne(mapper.insertEvent(locked.account(), id("PRE"), operationId, locked.binding(), newBinding,
                    now, locked.policy().policyVersion(), grant == null ? null : grant.grantId(), "LIVE", operationId));
            emit(locked.account(), actor(command), "phone.replacement_succeeded", operationId, operationId,
                    Map.of("oldBindingVersion", locked.binding().version(), "newBindingVersion", newBinding.version(),
                            "policyVersion", locked.policy().policyVersion()));
        }
        if (locked.binding() == null || permit.replacement())
            requireOne(mapper.markBindingTime(locked.account(), locked.binding() == null, now));
        materialize(locked.account(), newBinding, now);
        return finish(locked, command, "SUCCEEDED", originalResult, "BINDING", Long.toString(locked.account().userId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt createRequest(LockedAccount locked, String targetInstallation, long expectedBindingVersion, String reason, Command command) {
        requireTransaction();
        if (command.replay()) return receipt(command.operation());
        requireUser(command, locked); installation(targetInstallation); reason(reason);
        if (locked.binding() == null) throw failure(409, "PHONE_BINDING_REQUIRED");
        if (expectedBindingVersion != locked.binding().version()) throw failure(409,"BINDING_VERSION_CHANGED");
        long now = clock.millis();
        materialize(locked.account(), locked.binding(), now);
        Grant active = activeGrant(locked.account(), locked.binding(), now);
        Request request = active != null && targetInstallation.equals(active.targetInstallationId())
                ? readRequest(locked.account(), active.requestId())
                : mapper.currentPendingRequest(locked.account(), targetInstallation, locked.binding().version());
        if (request == null) {
            String requestId = id("PRQ");
            requireOne(mapper.insertRequest(locked.account(), requestId, requestId, targetInstallation,
                    locked.binding().version(), reason.trim(), now, plusDay(now), command.operation().operationId()));
            request = readRequest(locked.account(), requestId);
            emit(locked.account(), actor(command), "phone.replacement_request_created", requestId,
                    command.operation().operationId(), Map.of("requestId", requestId, "bindingVersion", request.bindingVersion()));
        }
        return finish(locked, command, "SUCCEEDED", request, "REQUEST", request.requestId());
    }

    /** Caller has verified that supportRef belongs to this account via the content public boundary. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt linkSupport(LockedAccount locked, String requestId, String supportRef,
                               long verifiedSupportAccountId, Command command) {
        requireTransaction();
        if (command.replay()) return receipt(command.operation());
        requireUser(command, locked);
        if (verifiedSupportAccountId != locked.account().userId() || supportRef == null
                || supportRef.isBlank() || supportRef.length() > 128) throw failure(403, "PERMISSION_DENIED");
        materialize(locked.account(), locked.binding(), clock.millis());
        Request request = readRequest(locked.account(), requestId);
        if (request == null || !Set.of("PENDING", "GRANTED").contains(request.status())) throw failure(409, "REQUEST_UNAVAILABLE");
        requireOne(mapper.linkSupport(locked.account(), requestId, supportRef));
        return finish(locked, command, "SUCCEEDED", readRequest(locked.account(), requestId), "REQUEST", requestId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt issueGrant(LockedAccount locked, IssueInput input, VerifiedEvidence evidence, Command command) {
        requireTransaction();
        if (command.replay()) return receipt(command.operation());
        requireAdmin(command); reason(input.reason());
        if (!"ACTIVE".equals(mapper.lockUser(locked.account().userId()))) throw failure(403, "USER_AUTH_REQUIRED");
        if (!input.waiveCount() && !input.waiveInterval()) throw failure(422, "PHONE_REPLACEMENT_WAIVER_REQUIRED");
        if (locked.binding() == null || input.expectedBindingVersion() != locked.binding().version())
            throw failure(409, "BINDING_VERSION_CHANGED");
        if (evidence == null || !evidence.explicitlyVerified() || evidence.accountId() != locked.account().userId()
                || evidence.verifiedByAdminId() != command.operation().actorId() || evidence.reference() == null
                || evidence.reference().isBlank() || evidence.snapshot() == null || evidence.snapshot().isEmpty())
            throw failure(422, "PHONE_REPLACEMENT_EVIDENCE_REQUIRED");
        long now = clock.millis();
        materialize(locked.account(), locked.binding(), now);
        Request request = readRequest(locked.account(), input.requestId());
        if (request == null || !"PENDING".equals(request.status()) || request.expiresAt() <= now
                || request.bindingVersion() != locked.binding().version()) throw failure(409, "REQUEST_UNAVAILABLE");
        if (activeGrant(locked.account(), locked.binding(), now) != null) throw failure(409, "ACTIVE_GRANT_EXISTS");
        String grantId = id("PRG");
        requireOne(mapper.insertGrant(locked.account(), request, grantId, input.waiveCount(), input.waiveInterval(),
                now, plusDay(now), input.reason().trim(), write(Map.of("reference", evidence.reference(),
                        "verifiedByAdminId", evidence.verifiedByAdminId(), "verifiedAt", now, "explicitlyVerified", true,
                        "snapshot", evidence.snapshot())), command.operation().actorId(), command.operation().operationId()));
        requireOne(mapper.transitionRequest(locked.account(), request.requestId(), "GRANTED", grantId, null, null));
        emit(locked.account(), actor(command), "admin.phone_replacement_grant_issued", grantId,
                command.operation().operationId(), Map.of("requestId", request.requestId(), "grantId", grantId));
        return finish(locked, command, "SUCCEEDED", grantSummary(readGrant(locked.account(), grantId)), "GRANT", grantId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt closeRequest(LockedAccount locked, String requestId, String reason, Command command) {
        requireTransaction();
        if (command.replay()) return receipt(command.operation());
        requireAdmin(command); reason(reason);
        long now = clock.millis(); materialize(locked.account(), locked.binding(), now);
        Request request = readRequest(locked.account(), requestId);
        if (request == null || !"PENDING".equals(request.status())) throw failure(409, "REQUEST_UNAVAILABLE");
        requireOne(mapper.transitionRequest(locked.account(), requestId, "CLOSED", null, reason.trim(), now));
        emit(locked.account(), actor(command), "admin.phone_replacement_request_closed", requestId,
                command.operation().operationId(), Map.of("requestId", requestId, "reason", reason.trim()));
        return finish(locked, command, "SUCCEEDED", readRequest(locked.account(), requestId), "REQUEST", requestId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt revokeGrant(LockedAccount locked, String grantId, String reason, Command command) {
        requireTransaction();
        if (command.replay()) return receipt(command.operation());
        requireAdmin(command); reason(reason);
        long now = clock.millis(); materialize(locked.account(), locked.binding(), now);
        Grant grant = readGrant(locked.account(), grantId);
        if (!usable(grant, locked.binding(), now)) throw failure(409, "GRANT_STATE_CHANGED");
        requireOne(mapper.transitionGrant(locked.account(), grantId, "REVOKED", now, null, null));
        emit(locked.account(), actor(command), "admin.phone_replacement_grant_revoked", grantId,
                command.operation().operationId(), Map.of("grantId", grantId, "reason", reason.trim()));
        return finish(locked, command, "SUCCEEDED", grantSummary(readGrant(locked.account(), grantId)), "GRANT", grantId);
    }

    /** Explicit verified coverage only; never call merely because a migration ran. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void establishReliableHistory(LockedAccount locked, long reliableFrom, String evidenceRef) {
        requireTransaction();
        if (reliableFrom < 0 || reliableFrom > clock.millis() || evidenceRef == null || evidenceRef.isBlank()
                || evidenceRef.length() > 200) throw failure(422, "PHONE_HISTORY_EVIDENCE_REQUIRED");
        mapper.establishHistory(locked.account(), reliableFrom, evidenceRef);
    }

    /** Backfill only independently verified successful changes; a duplicate source is a read, not a new event. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String importVerifiedEvent(LockedAccount locked, String sourceKind, String sourceId, Binding oldBinding,
                                     Binding newBinding, long occurredAt, long policyVersion) {
        requireTransaction();
        if (sourceKind == null || !sourceKind.matches("[A-Z_]{1,32}") || "LIVE".equals(sourceKind)
                || sourceId == null || sourceId.isBlank() || sourceId.length() > 128
                || oldBinding == null || newBinding == null || occurredAt < 0 || occurredAt > clock.millis()
                || !PhoneReplacementPolicy.isReplacement(oldBinding.installationId(), oldBinding.hardwareKey(),
                        newBinding.installationId(), newBinding.hardwareKey())) throw failure(422, "PHONE_HISTORY_EVIDENCE_REQUIRED");
        String prior = mapper.eventBySource(locked.account(), sourceKind, sourceId);
        if (prior != null) return prior;
        String eventId = id("PRE");
        requireOne(mapper.insertEvent(locked.account(), eventId, id("IMPORT"), oldBinding, newBinding, occurredAt,
                policyVersion, null, sourceKind, sourceId));
        return eventId;
    }

    @Transactional(readOnly = true)
    public Eligibility eligibility(Account account, String targetInstallation, String targetHardware,
                                   boolean executableApp, List<String> hardBlockedReasons) {
        PhoneReplacementPolicy policy = policy(currentRead() ? mapper.lockPolicy() : mapper.policy());
        Binding binding = readBinding(account);
        long now = clock.millis();
        List<String> blocks = new ArrayList<>(hardBlockedReasons == null ? List.of() : hardBlockedReasons);
        if (!executableApp) blocks.add("CURRENT_APP_REQUIRED");
        Grant grant = activeGrant(account, binding, now);
        // A target-specific grant is never advertised as usable by another installation or H5.
        if (!executableApp || grant != null && !Objects.equals(grant.targetInstallationId(), targetInstallation)) grant = null;
        if (binding == null) return new Eligibility(null, policy == null ? currentPolicy() : view(policy), now, 0L, policy == null ? null : (long)policy.maxReplacementsInWindow(),
                true, null, blocks.isEmpty(), null, List.copyOf(blocks), grantSummary(grant));
        boolean restoring = targetHardware != null && !targetHardware.isBlank()
                && !PhoneReplacementPolicy.isReplacement(binding.installationId(), binding.hardwareKey(),
                        targetInstallation, targetHardware);
        if (policy == null) {
            if (restoring) return new Eligibility(bindingSummary(binding), currentPolicy(), now, null, null, false, null,
                    blocks.isEmpty(), null, List.copyOf(blocks), grantSummary(grant));
            blocks.add("PHONE_REPLACEMENT_POLICY_UNAVAILABLE");
            return new Eligibility(bindingSummary(binding), view(null), now, null, null, false, null, false, null, List.copyOf(blocks), grantSummary(grant));
        }
        var d = decision(account, binding, readState(account), policy, now,
                grant != null && grant.waiveCount(), grant != null && grant.waiveInterval(), blocks);
        return new Eligibility(bindingSummary(binding), view(policy), now, d.usedCount(), d.remainingCount(), d.historyComplete(),
                d.historyCompleteFrom(), restoring ? blocks.isEmpty() : d.canReplace(), d.nextAllowedAt(),
                restoring ? List.copyOf(blocks) : d.blockedReasons(), grantSummary(grant));
    }

    @Transactional(readOnly = true)
    public Receipt operation(Account account, String id) { return receipt(readOperation(account, id)); }

    @Transactional(readOnly = true)
    public Receipt userOperation(Account account, String id) {
        Operation operation=readOperation(account,id);
        return operation != null && "USER".equals(operation.actorType()) && operation.actorId()==account.userId()
                ? receipt(operation) : null;
    }

    @Transactional(readOnly = true)
    public Receipt operationByKey(Account account, Actor actor, String type, String key) {
        Operation operation = readOperationByKey(account, actor, type, key);
        return operation != null && operation.userId() == account.userId() ? receipt(operation) : null;
    }

    @Transactional(readOnly = true)
    public CurrentRequest currentRequest(Account account, String installation) {
        Binding binding = readBinding(account); long now = clock.millis();
        Grant active = activeGrant(account, binding, now);
        if (active != null && (installation == null || installation.equals(active.targetInstallationId())))
            return new CurrentRequest(requestView(readRequest(account, active.requestId()), binding, now),grantSummary(active));
        List<Request> rows = currentRead() ? mapper.currentRequests(account,installation,0,1) : mapper.requests(account, installation, 0, 1);
        if (rows.isEmpty()) return null;
        Request request = requestView(rows.get(0), binding, now);
        Grant grant = request.grantId() == null ? null : readGrant(account,request.grantId());
        return new CurrentRequest(request,grantSummary(grantView(grant,binding,now)));
    }

    @Transactional(readOnly = true)
    public List<Request> requests(Account account, int offset, int limit) {
        page(offset, limit); Binding binding = readBinding(account); long now = clock.millis();
        return (currentRead() ? mapper.currentRequests(account,null,offset,limit) : mapper.requests(account, null, offset, limit))
                .stream().map(r -> requestView(r, binding, now)).toList();
    }

    @Transactional(readOnly = true)
    public Request request(Account account,String requestId) {
        Binding binding=readBinding(account);
        return requestView(readRequest(account,requestId),binding,clock.millis());
    }

    @Transactional(readOnly = true)
    public List<GrantSummary> grants(Account account,int offset,int limit) {
        page(offset,limit);var binding=readBinding(account);long now=clock.millis();
        return mapper.grants(account,offset,limit).stream().map(g->grantSummary(grantView(g,binding,now))).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> replacementEvents(Account account, int offset, int limit) {
        page(offset, limit); return mapper.events(account, offset, limit);
    }

    public Receipt receipt(Operation operation) {
        if (operation == null) return null; // Unknown, never a fabricated FAILED result.
        Account account = new Account(operation.userId(), operation.sourceEnvironment(), operation.runId());
        Object current = switch (operation.resourceType() == null ? "" : operation.resourceType()) {
            case "REQUEST" -> requestView(readRequest(account, operation.resourceId()), readBinding(account), clock.millis());
            case "GRANT" -> grantSummary(grantView(readGrant(account, operation.resourceId()), readBinding(account), clock.millis()));
            case "BINDING" -> bindingSummary(readBinding(account));
            default -> null;
        };
        return new Receipt(operation.operationId(), operation.commandType(), operation.status(), operation.submittedAt(),
                operation.completedAt(), read(operation.originalResultJson()), current, operation.auditRef());
    }

    /** Only call for a determinate rejection before any binding/device write; transaction owner controls rollback. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt reject(LockedAccount locked, Command command, String conflictType) {
        requireTransaction();
        if (command.replay()) return receipt(command.operation());
        requirePending(command);
        boolean activation = "PHONE_ACTIVATE".equals(command.operation().commandType());
        emit(locked.account(), actor(command), activation
                        ? "phone.replacement_rejected" : "admin.phone_replacement_grant_rejected",
                command.operation().operationId(), command.operation().operationId(), Map.of("conflictType", conflictType));
        return finish(locked, command, "FAILED", Map.of("conflictType", conflictType), null, null);
    }

    private Receipt finish(LockedAccount locked, Command command, String status, Object result, String resourceType, String resourceId) {
        requirePending(command);
        String auditRef = "PHONE:" + command.operation().operationId();
        audit.recordRequired(AuditLogWriteRequest.builder().traceId(auditRef).action("PHONE_REPLACEMENT_COMMAND")
                .resourceType("PHONE_REPLACEMENT_OPERATION").resourceId(command.operation().operationId())
                .userId(locked.account().userId()).actorType(command.operation().actorType()).actorId(command.operation().actorId())
                .result(status).riskLevel("MEDIUM").detail(Map.of("commandType", command.operation().commandType(),
                        "result", result)).build());
        requireOne(mapper.finishOperation(locked.account(), command.operation().operationId(), status, clock.millis(),
                write(result), resourceType, resourceId, auditRef));
        return receipt(readOperation(locked.account(), command.operation().operationId()));
    }

    private void materialize(Account account, Binding binding, long now) {
        mapper.expireRequests(account, binding == null ? -1 : binding.version(), now);
        for (Grant grant : mapper.lockActiveGrants(account)) {
            String state = effectiveGrantState(grant, binding, now);
            if ("ACTIVE".equals(state)) continue;
            boolean expired = "EXPIRED".equals(state);
            long terminalAt = expired ? grant.expiresAt() : now;
            // CAS is the unique lifecycle-event fence; repeat reads/writers never emit twice.
            if (mapper.transitionGrant(account, grant.grantId(), state, terminalAt, null, null) == 1)
                emit(account, new Actor("SYSTEM", 0), expired ? "admin.phone_replacement_grant_expired" : "admin.phone_replacement_grant_stale",
                        grant.grantId(), null, Map.of("grantId", grant.grantId(), "status", state, "terminalAt", terminalAt));
        }
    }

    private Grant activeGrant(Account account, Binding binding, long now) {
        return (currentRead() ? mapper.lockActiveGrants(account) : mapper.activeGrants(account)).stream().filter(g -> usable(g, binding, now)).findFirst().orElse(null);
    }
    private static boolean usable(Grant grant, Binding binding, long now) {
        return grant != null && "ACTIVE".equals(effectiveGrantState(grant, binding, now));
    }
    private static String effectiveGrantState(Grant grant, Binding binding, long now) {
        if (!"ACTIVE".equals(grant.status())) return grant.status();
        if (binding == null || grant.bindingVersion() != binding.version()) return "STALE";
        return now >= grant.expiresAt() ? "EXPIRED" : "ACTIVE";
    }
    private static Grant grantView(Grant grant, Binding binding, long now) {
        if (grant == null) return null;
        return new Grant(grant.grantId(), grant.requestId(), grant.userId(), grant.sourceEnvironment(), grant.runId(),
                grant.targetInstallationId(), grant.bindingVersion(), grant.waiveCount(), grant.waiveInterval(),
                grant.issuedAt(), grant.expiresAt(), effectiveGrantState(grant,binding,now), grant.reason(), grant.evidenceJson(),
                grant.operatorId(), grant.consumedOperationId(), grant.consumedAt(), grant.terminalAt());
    }
    private static Request requestView(Request r, Binding binding, long now) {
        if (r == null || !"PENDING".equals(r.status())) return r;
        String status = binding == null || r.bindingVersion() != binding.version() ? "STALE"
                : now >= r.expiresAt() ? "EXPIRED" : "PENDING";
        return new Request(r.requestId(),r.requestNo(),r.userId(),r.sourceEnvironment(),r.runId(),r.targetInstallationId(),
                r.bindingVersion(),r.reason(),r.createdAt(),r.expiresAt(),status,r.grantId(),r.supportRef(),
                r.supportLinkStatus(),r.closeReason(),r.closedAt());
    }
    private PhoneReplacementPolicy.Decision decision(Account account, Binding binding, PhoneReplacementMapper.State state,
            PhoneReplacementPolicy policy, long now, boolean waiveCount, boolean waiveInterval, List<String> blocks) {
        var history = state == null ? null : new PhoneReplacementPolicy.History(state.completeFrom(),state.allHistoryKnown(),state.changedAtVerified());
        return policy.evaluate(now, binding.changedAt(), history,
                (currentRead() ? mapper.currentEventTimes(account, now-policy.replacementWindowDays()*PhoneReplacementPolicy.DAY, now)
                        : mapper.eventTimes(account, now-policy.replacementWindowDays()*PhoneReplacementPolicy.DAY, now)),
                waiveCount, waiveInterval, blocks);
    }
    // Locking reads are required even after the account lock: a caller may already hold an older RR snapshot.
    private static boolean currentRead() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }
    private Binding readBinding(Account a) { return currentRead() ? mapper.lockBinding(a) : mapper.binding(a); }
    private PhoneReplacementMapper.State readState(Account a) { return currentRead() ? mapper.currentState(a) : mapper.state(a); }
    private Request readRequest(Account a,String id) { return currentRead() ? mapper.currentRequest(a,id) : mapper.request(a,id); }
    private Grant readGrant(Account a,String id) { return currentRead() ? mapper.currentGrant(a,id) : mapper.grant(a,id); }
    private Operation readOperation(Account a,String id) { return currentRead() ? mapper.currentOperation(a,id) : mapper.operation(a,id); }
    private Operation readOperationByKey(Account a,Actor actor,String type,String key) {
        return currentRead() ? mapper.currentOperationByKey(a,actor,type,key) : mapper.operationByKey(a,actor,type,key);
    }
    private PhoneReplacementPolicy policy(List<PhoneReplacementMapper.Config> rows) {
        Map<String,String> values = new HashMap<>(); rows.forEach(r -> values.put(r.configKey(),r.configValue()));
        return PhoneReplacementPolicy.parse(values,clock.millis());
    }
    private static Long validVersion(String value) {
        try { long version=Long.parseLong(value); return version>0 ? version : null; }
        catch(RuntimeException invalid){return null;}
    }
    private static Policy view(PhoneReplacementPolicy p) {
        return p == null ? new Policy(false,null,null,null,null,null) : new Policy(true,p.allowReplacement(),
                p.minReplacementIntervalDays(),p.replacementWindowDays(),p.maxReplacementsInWindow(),p.policyVersion());
    }
    private static BindingSummary bindingSummary(Binding b) {
        return b == null ? null : new BindingSummary(b.installationId(),b.userDeviceId(),b.changedAt(),b.version());
    }
    private static GrantSummary grantSummary(Grant g) {
        return g == null ? null : new GrantSummary(g.grantId(),g.requestId(),g.bindingVersion(),g.waiveCount(),
                g.waiveInterval(),g.issuedAt(),g.expiresAt(),g.status(),"ACTIVE".equals(g.status()) ? 1 : 0);
    }
    private void emit(Account account, Actor actor, String event, String resource, String operationId, Map<String,Object> details) {
        var payload = new LinkedHashMap<String,Object>();
        payload.put("user_id",account.userId()); payload.put("resource_id",resource);
        payload.put("operation_id",operationId); payload.put("details",details);
        audit.recordRequired(AuditLogWriteRequest.builder().action(event).resourceType("PHONE_REPLACEMENT")
                .resourceId(resource).userId(account.userId()).actorType(actor.type()).actorId(actor.id())
                .result("SUCCESS").riskLevel("MEDIUM").detail(payload).build());
        outbox.publish("PHONE_REPLACEMENT",resource,event,payload);
    }
    private static void requirePending(Command c) {
        if (c == null || c.operation() == null || !"PENDING".equals(c.operation().status()) || c.replay())
            throw failure(409,"PHONE_OPERATION_NOT_PENDING");
    }
    private static Actor actor(Command c) { return new Actor(c.operation().actorType(),c.operation().actorId()); }
    private static void requireUser(Command c, LockedAccount l) {
        requirePending(c);
        if (!"USER".equals(c.operation().actorType()) || c.operation().actorId()!=l.account().userId())
            throw failure(403,"PERMISSION_DENIED");
    }
    private static void requireAdmin(Command c) {
        requirePending(c); if (!"ADMIN".equals(c.operation().actorType())) throw failure(403,"PERMISSION_DENIED");
        // Exact current authorization/data scope is the SPEC-03 application adapter's responsibility.
    }
    private static void validateActor(Actor a) {
        if (a == null || a.id()<=0 || !Set.of("USER","ADMIN").contains(a.type())) throw failure(403,"PERMISSION_DENIED");
    }
    private static void installation(String v) { if(v==null || !v.matches("[A-Za-z0-9._:-]{1,128}")) throw failure(422,"ONBOARDING_DEVICE_INVALID"); }
    private static void reason(String v) { if(v==null || v.trim().length()<8 || v.trim().length()>200) throw failure(422,"PHONE_REPLACEMENT_REASON_INVALID"); }
    private static void page(int offset,int limit) { if(offset<0 || limit<1 || limit>101) throw failure(422,"PAGE_INVALID"); }
    private static long plusDay(long now) { return Math.addExact(now,PhoneReplacementPolicy.DAY); }
    private static String id(String prefix) { return prefix+"-"+UUID.randomUUID().toString().replace("-",""); }
    private static BizException failure(int code,String type) { return new ffdd.opsconsole.shared.exception.PhoneReplacementException(code,type); }
    private static void requireOne(int changed) { if(changed!=1) throw failure(409,"PHONE_REPLACEMENT_WRITE_CONFLICT"); }
    private static void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("PHONE_REPLACEMENT_TRANSACTION_REQUIRED");
    }
    private String write(Object v) { try { return json.writeValueAsString(v); } catch(JsonProcessingException e){throw failure(503,"PHONE_OPERATION_SERIALIZATION_FAILED");} }
    private Object read(String v) { if(v==null)return null; try{return json.readValue(v,Object.class);}catch(JsonProcessingException e){throw failure(503,"PHONE_OPERATION_RECEIPT_UNAVAILABLE");} }
    private String hash(Object input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(input).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
