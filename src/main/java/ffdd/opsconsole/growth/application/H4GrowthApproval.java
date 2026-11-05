package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.dto.GenesisRaffleRequest;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthWheelGuardRequest;
import ffdd.opsconsole.growth.dto.H4EventRuleRequest;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Only the four existing H4 commands below can be proposed or replayed. */
@Component
@RequiredArgsConstructor
public class H4GrowthApproval {
    public static final String MAKER_AUTHORITY = "growth_h4_write";
    public static final String GUARD_AUTHORITY = "growth_h4_wheel_pool_write";
    private static final String RULE = "h4_event_rule_enable";
    private static final String RAFFLE = "h4_genesis_raffle_open";
    private static final String GUARD_CREATE = "h4_wheel_guard_create";
    private static final String GUARD_UPDATE = "h4_wheel_guard_update";
    private final H4EventRuleService rules;
    private final GenesisRaffleService raffles;
    private final OpsGrowthService growth;
    private final OpsGrowthCommandBoundary commands;
    private final H4EventRuleRepository db;
    private final ObjectMapper json;
    private final SuperAdminAuthorization superAdmins;
    private final AuditObjectLockMapper lockMapper;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "H".equals(command.domain())
                && Set.of(RULE, RAFFLE, GUARD_CREATE, GUARD_UPDATE).contains(command.op());
    }

    public static String makerAuthority(AuditReplayCommand command) {
        return GUARD_CREATE.equals(command.op()) || GUARD_UPDATE.equals(command.op()) ? GUARD_AUTHORITY : MAKER_AUTHORITY;
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && hasAuthority(SecurityContextHolder.getContext().getAuthentication(), makerAuthority(command));
    }

    @Transactional(rollbackFor = Exception.class)
    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null || adminId <= 0 || !canPropose(command)) throw new BizException(403, "H4_MAKER_FORBIDDEN");
        return new AuditReplayCommand("H", command.op(), Map.of("values", values(command), "makerAdminId", adminId,
                "expectedState", state(target(command))));
    }

    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long checkerId) {
        Object maker = command.params() == null ? null : command.params().get("makerAdminId");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(maker instanceof Number) || checkerId == null || checkerId <= 0
                || !String.valueOf(checkerId).equals(authentication == null ? "" : authentication.getName())
                || !superAdmins.isSuperAdmin(authentication) || !hasAuthority(authentication, "platform_a2_operation_approve"))
            return ApiResult.fail(403, "H4_APPROVAL_FORBIDDEN");
        return String.valueOf(checkerId).equals(String.valueOf(maker))
                ? ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED") : ApiResult.ok();
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        Map<String, Object> values = values(command);
        if (RULE.equals(command.op())) {
            String code = text(values, "eventCode");
            if (!H4EventRuleService.CODES.contains(code)) throw new BizException(422, "EVENT_RULE_NOT_FOUND");
            return new AuditLockTarget("H", "h4_event_rule", code);
        }
        if (RAFFLE.equals(command.op())) {
            String id = text(values, "id");
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new BizException(422, "RAFFLE_ID_INVALID");
            return new AuditLockTarget("H", "h4_genesis_raffle", id);
        }
        String key = text(values, "guardKey");
        if (!Set.of("budget", "kill").contains(key)) throw new BizException(422, "WHEEL_GUARD_INVALID");
        return new AuditLockTarget("H", "h4_wheel_guard", key);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> preview(AuditReplayCommand command, String reason) {
        AuditLockTarget target = target(command);
        ApiResult<Map<String, Object>> result = switch (command.op()) {
            case RULE -> rules.previewEnable(target.id(), rule(command, reason));
            case RAFFLE -> raffles.previewOpen(target.id(), raffle(command, reason));
            case GUARD_CREATE -> growth.previewH4GuardCreate(guardCreate(command, reason));
            case GUARD_UPDATE -> growth.previewH4GuardUpdate(target.id(), guardUpdate(command, reason));
            default -> throw new BizException(422, "H4_PROPOSAL_INVALID");
        };
        if (result.getCode() != 0) return result;
        return ApiResult.ok(Map.of("before", rules.encode(state(target)), "after", rules.encode(values(command))));
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context, Long checkerId, String operationId) {
        ApiResult<Void> checker = validateChecker(command, checkerId);
        if (checker.getCode() != 0) return checker;
        if (!A2ReplayContext.isReplaying() || operationId == null || !operationId.equals(A2ReplayContext.operationId()))
            return ApiResult.fail(403, "H4_APPROVAL_FORBIDDEN");
        AuditLockTarget target = target(command);
        var locks = lockMapper.selectActiveByTicketId(operationId);
        if (locks.size() != 1 || !operationId.equals(locks.get(0).getTicketId()) || !target.equals(
                new AuditLockTarget(locks.get(0).getTargetDomain(), locks.get(0).getTargetType(), locks.get(0).getTargetId())))
            return ApiResult.fail(403, "H4_APPROVAL_FORBIDDEN");
        if (!state(target).equals(command.params().get("expectedState"))) return ApiResult.fail(409, "H4_CONFIG_STALE");
        if (!(command.params().get("proposalReason") instanceof String reason)) return ApiResult.fail(422, "H4_PROPOSAL_INVALID");
        return switch (command.op()) {
            case RULE -> {
                var request = rule(command, reason);
                yield commands.execute("H4", "EVENT_RULE_UPDATE", target.id(), context.idempotencyKey(), request,
                        () -> rules.executeApprovedEnable(target.id(), request));
            }
            case RAFFLE -> {
                var request = raffle(command, reason);
                yield commands.execute("H4", "GENESIS_RAFFLE_UPDATE", target.id(), context.idempotencyKey(), request,
                        () -> raffles.executeApprovedOpen(target.id(), request));
            }
            case GUARD_CREATE -> {
                var request = guardCreate(command, reason);
                yield commands.execute("H4", "WHEEL_GUARD_CREATE", target.id(), context.idempotencyKey(), request,
                        () -> growth.executeApprovedH4GuardCreate(context.idempotencyKey(), request));
            }
            case GUARD_UPDATE -> {
                var request = guardUpdate(command, reason);
                yield commands.execute("H4", "WHEEL_GUARD_UPDATE", target.id(), context.idempotencyKey(), request,
                        () -> growth.executeApprovedH4GuardUpdate(context.idempotencyKey(), target.id(), request));
            }
            default -> ApiResult.fail(422, "H4_PROPOSAL_INVALID");
        };
    }

    private Map<String, String> state(AuditLockTarget target) {
        Map<String, Object> row;
        if ("h4_event_rule".equals(target.type())) {
            row = db.row("""
                SELECT r.*,q.status q_status,q.is_deleted q_deleted,q.starts_at q_starts_at,q.ends_at q_ends_at
                FROM nx_growth_event_rule r JOIN nx_event_quest q ON q.quest_code=r.event_code
                WHERE r.event_code=? FOR UPDATE
                """, target.id());
        } else if ("h4_genesis_raffle".equals(target.type())) {
            row = db.row("SELECT * FROM nx_genesis_raffle_draw WHERE draw_id=? FOR UPDATE", target.id());
            if (row != null) {
                row.put("translations", rules.encode(db.rows("SELECT * FROM nx_genesis_raffle_translation WHERE draw_id=? FOR UPDATE", target.id())));
                row.put("entries", rules.encode(db.rows("SELECT * FROM nx_genesis_raffle_entry WHERE draw_id=? ORDER BY ticket_id FOR UPDATE", target.id())));
            }
        } else {
            if (db.row("SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='H4_WHEEL' FOR UPDATE") == null)
                throw new BizException(409, "H4_WHEEL_MUTEX_UNAVAILABLE");
            row = db.row("SELECT * FROM nx_growth_wheel_guard WHERE guard_key=? AND is_deleted=0 FOR UPDATE", target.id());
        }
        Map<String, String> state = new TreeMap<>();
        state.put("exists", String.valueOf(row != null));
        if (row != null) row.forEach((key, value) -> state.put(key, String.valueOf(value)));
        return state;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(AuditReplayCommand command) {
        if (!supports(command) || command.params() == null || !(command.params().get("values") instanceof Map<?, ?> raw))
            throw new BizException(422, "H4_PROPOSAL_INVALID");
        Set<String> required = switch (command.op()) {
            case RULE -> Set.of("eventCode", "expectedRevision", "enabled", "startsAt", "endsAt", "rules");
            case RAFFLE -> Set.of("id", "expectedRevision", "enabled", "titleZh", "titleEn", "titleVi", "startsAt", "drawAt", "prizes");
            case GUARD_CREATE -> Set.of("guardKey", "guardLabel", "guardValue", "note");
            default -> Set.of("guardKey", "value", "expectedValue");
        };
        var allowed = new HashSet<>(required);
        if (RAFFLE.equals(command.op())) allowed.add("titleTranslations");
        if (!raw.keySet().containsAll(required) || !allowed.containsAll(raw.keySet())) throw new BizException(422, "H4_PROPOSAL_INVALID");
        Map<String, Object> values = (Map<String, Object>) raw;
        if (RULE.equals(command.op()) || RAFFLE.equals(command.op())) {
            Object revision = values.get("expectedRevision");
            if (!(revision instanceof Number) || new BigDecimal(revision.toString()).stripTrailingZeros().scale() > 0
                    || new BigDecimal(revision.toString()).signum() < 0 || new BigDecimal(revision.toString()).compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                    || !Boolean.TRUE.equals(values.get("enabled"))) throw new BizException(422, "H4_PROPOSAL_INVALID");
        }
        if (RULE.equals(command.op()) && !(values.get("rules") instanceof Map<?, ?>)) throw new BizException(422, "H4_PROPOSAL_INVALID");
        if (RAFFLE.equals(command.op())) {
            Set<String> fields = Set.of("id", "nameZh", "nameEn", "nameVi", "rewardType", "amount", "quantity", "productNo", "nameTranslations");
            if (!(values.get("prizes") instanceof List<?> prizes) || prizes.stream().anyMatch(item -> !(item instanceof Map<?, ?> prize)
                    || !fields.containsAll(prize.keySet()) || !(prize.get("quantity") instanceof Number quantity)
                    || new BigDecimal(quantity.toString()).stripTrailingZeros().scale() > 0)) throw new BizException(422, "H4_PROPOSAL_INVALID");
        }
        return values;
    }

    private H4EventRuleRequest rule(AuditReplayCommand command, String reason) {
        var request = new LinkedHashMap<>(values(command));
        request.remove("eventCode"); request.put("reason", reason);
        return convert(request, H4EventRuleRequest.class);
    }
    private GenesisRaffleRequest raffle(AuditReplayCommand command, String reason) {
        var request = new LinkedHashMap<>(values(command)); request.put("reason", reason);
        return convert(request, GenesisRaffleRequest.class);
    }
    private GrowthWheelGuardRequest guardCreate(AuditReplayCommand command, String reason) {
        var request = new LinkedHashMap<>(values(command)); request.put("reason", reason);
        return convert(request, GrowthWheelGuardRequest.class);
    }
    private GrowthConfigUpdateRequest guardUpdate(AuditReplayCommand command, String reason) {
        var values = values(command);
        return new GrowthConfigUpdateRequest(null, text(values, "value"), reason, null, text(values, "expectedValue"));
    }
    private <T> T convert(Object value, Class<T> type) {
        try { return json.convertValue(value, type); }
        catch (IllegalArgumentException ex) { throw new BizException(422, "H4_PROPOSAL_INVALID"); }
    }
    private static String text(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof String text) || text.isBlank() || !text.equals(text.trim()))
            throw new BizException(422, "H4_PROPOSAL_INVALID");
        return text;
    }
    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(grant -> authority.equals(grant.getAuthority()));
    }
}
