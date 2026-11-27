package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.dto.GenesisRaffleRequest;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthWheelGuardRequest;
import ffdd.opsconsole.growth.dto.GrowthWheelProbabilityBatchRequest;
import ffdd.opsconsole.growth.dto.GrowthWheelTierRequest;
import ffdd.opsconsole.growth.dto.H4EventRuleRequest;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.exception.H4EventRuleIncompleteException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Only the listed H4 commands can be proposed or replayed. */
@Component
@RequiredArgsConstructor
public class H4GrowthApproval {
    public static final String MAKER_AUTHORITY = "growth_h4_write";
    public static final String GUARD_AUTHORITY = "growth_h4_wheel_pool_write";
    private static final String RULE = "h4_event_rule_enable";
    private static final String RAFFLE = "h4_genesis_raffle_open";
    private static final String LEGACY_REWARD = "h4_legacy_event_reward_update";
    private static final String LEGACY_OPEN = "h4_legacy_event_open";
    private static final String GUARD_CREATE = "h4_wheel_guard_create";
    private static final String GUARD_UPDATE = "h4_wheel_guard_update";
    private static final String TIER_CREATE = "h4_wheel_tier_create";
    private static final String TIER_UPDATE = "h4_wheel_tier_update";
    private static final String TIER_DELETE = "h4_wheel_tier_delete";
    private static final String PROBABILITIES_UPDATE = "h4_wheel_probabilities_update";
    private static final AuditLockTarget WHEEL_POOL = new AuditLockTarget("H", "h4_wheel_pool", "ACTIVE_POOL");
    private static final Set<String> WHEEL_POOL_OPERATIONS = Set.of(TIER_CREATE, TIER_UPDATE, TIER_DELETE, PROBABILITIES_UPDATE);
    private static final Set<String> WHEEL_SNAPSHOT_FIELDS = Set.of("tier", "reward", "prob", "real",
            "kind", "amount", "voucherId", "dailyStock");
    private static final Set<String> LEGACY_EVENT_OPERATIONS = Set.of(LEGACY_REWARD, LEGACY_OPEN);
    private static final Set<String> OPERATIONS = Set.of(RULE, RAFFLE, GUARD_CREATE, GUARD_UPDATE,
            TIER_CREATE, TIER_UPDATE, TIER_DELETE, PROBABILITIES_UPDATE, LEGACY_REWARD, LEGACY_OPEN);
    private final H4EventRuleService rules;
    private final GenesisRaffleService raffles;
    private final OpsGrowthService growth;
    private final AppGrowthWheelMapper wheelMapper;
    private final OpsGrowthCommandBoundary commands;
    private final H4EventRuleRepository db;
    private final ObjectMapper json;
    private final SuperAdminAuthorization superAdmins;
    private final AuditObjectLockMapper lockMapper;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "H".equals(command.domain())
                && command.op() != null && OPERATIONS.contains(command.op());
    }

    public static boolean isRaffleOpen(AuditReplayCommand command) {
        return supports(command) && RAFFLE.equals(command.op());
    }

    public static String makerAuthority(AuditReplayCommand command) {
        return switch (command.op()) {
            case GUARD_CREATE, GUARD_UPDATE, TIER_CREATE, TIER_UPDATE, TIER_DELETE, PROBABILITIES_UPDATE -> GUARD_AUTHORITY;
            default -> MAKER_AUTHORITY;
        };
    }

    public static String proposalAction(AuditReplayCommand command) {
        return switch (command.op()) {
            case LEGACY_REWARD -> "提高旧活动奖励";
            case LEGACY_OPEN -> "开放旧活动资金奖励";
            case TIER_CREATE -> "新增转盘奖项";
            case TIER_UPDATE -> "放宽转盘奖项配置";
            case TIER_DELETE -> "删除转盘奖项并提高安慰奖";
            case PROBABILITIES_UPDATE -> "提高转盘奖项概率";
            default -> "启用活动或放宽转盘资金护栏";
        };
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && hasAuthority(SecurityContextHolder.getContext().getAuthentication(), makerAuthority(command));
    }

    @Transactional(rollbackFor = Exception.class)
    public AuditReplayCommand bindMaker(AuditReplayCommand command, Long adminId) {
        if (adminId == null || adminId <= 0 || !canPropose(command)) throw new BizException(403, "H4_MAKER_FORBIDDEN");
        Map<String, Object> values = values(command);
        boolean legacyEvent = LEGACY_EVENT_OPERATIONS.contains(command.op());
        if ((legacyEvent || WHEEL_POOL_OPERATIONS.contains(command.op())) && !command.params().keySet().equals(Set.of("values")))
            throw new BizException(422, "H4_PROPOSAL_INVALID");
        Map<String, String> expectedState = state(target(command), command.op());
        if (legacyEvent && "true".equals(expectedState.get("exists")) && "false".equals(expectedState.get("hasRule"))) {
            values = new LinkedHashMap<>(values);
            if (LEGACY_REWARD.equals(command.op())) {
                var reward = growth.legacyEventReward(legacyEventUpdate(command, null).value());
                values.put("rewardType", reward.type());
                values.put("rewardAmount", reward.amount().toPlainString());
            } else {
                values.put("rewardType", expectedState.get("rewardType"));
                values.put("rewardAmount", expectedState.get("rewardAmount"));
            }
        }
        if (RAFFLE.equals(command.op()) && "DRAFT".equals(expectedState.get("status"))) {
            // Freeze the proposed save before A2 reviews or hashes the command.
            var submitted = raffle(command, null);
            var completed = raffles.completeAuthoredCopy(submitted);
            if (completed != submitted) {
                values = json.convertValue(completed, new TypeReference<LinkedHashMap<String, Object>>() {});
                values.remove("reason");
            }
        }
        return new AuditReplayCommand("H", command.op(), Map.of("values", values, "makerAdminId", adminId,
                "expectedState", expectedState));
    }

    /** Freeze copy in the proposal transaction after the existing preview checks. */
    public AuditReplayCommand bindEventCopy(AuditReplayCommand command, String operator,
            String reason, String idempotencyKey, String operationId) {
        if (!supports(command) || !Set.of(RULE, LEGACY_OPEN).contains(command.op())) return command;
        AuditLockTarget target = target(command);
        boolean legacy = LEGACY_OPEN.equals(command.op());
        String copyBefore = rules.encode(growth.eventLocalizedContent(target.id()));
        var published = growth.saveEventPublicationCopy(target.id(), operator, Map.of(
                "reason", reason, "idempotencyKey", idempotencyKey.trim(),
                "operation", command.op(), "operationId", operationId, "sourceLocale", "en"));
        if (!published.complete()) {
            throw new H4EventRuleIncompleteException(422,
                    legacy ? "EVENT_LOCALIZED_CONTENT_INCOMPLETE" : "EVENT_RULE_INCOMPLETE",
                    legacy ? growth.eventPublicationDetails(target.id(), published) : published.details());
        }
        var params = new LinkedHashMap<>(command.params());
        params.put("eventCopy", published.content());
        params.put("eventCopyBefore", copyBefore);
        if (legacy) {
            params.put("expectedState", state(target, command.op()));
        }
        return new AuditReplayCommand(command.domain(), command.op(), params);
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
        if (WHEEL_POOL_OPERATIONS.contains(command.op())) return WHEEL_POOL;
        if (LEGACY_EVENT_OPERATIONS.contains(command.op())) {
            String code = text(values, "eventCode");
            if (!OpsGrowthService.legacyEventCode(code)) throw new BizException(422, "H4_PROPOSAL_INVALID");
            return new AuditLockTarget("H", "h4_legacy_event", code);
        }
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
            case LEGACY_REWARD, LEGACY_OPEN -> growth.previewH4LegacyEvent(target.id(), legacyEventUpdate(command, reason),
                    LEGACY_OPEN.equals(command.op()));
            case GUARD_CREATE -> growth.previewH4GuardCreate(guardCreate(command, reason));
            case GUARD_UPDATE -> growth.previewH4GuardUpdate(target.id(), guardUpdate(command, reason));
            case TIER_CREATE -> growth.previewH4WheelTierCreate(wheelTier(command, reason));
            case TIER_UPDATE -> growth.previewH4WheelTierUpdate(wheelTier(command, reason));
            case TIER_DELETE -> growth.previewH4WheelTierDelete(wheelTierDelete(command, reason));
            case PROBABILITIES_UPDATE -> growth.previewH4WheelProbabilities(wheelProbabilities(command, reason));
            default -> throw new BizException(422, "H4_PROPOSAL_INVALID");
        };
        if (result.getCode() != 0) return result;
        return ApiResult.ok(Map.of("before", rules.encode(state(target, command.op())), "after", rules.encode(values(command))));
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
        if (RULE.equals(command.op()) && "evt-spring-spin".equals(target.id())) {
            if (!growth.lockH4Event()) throw new BizException(409, "H4_EVENT_MUTEX_UNAVAILABLE");
            growth.lockH4Wheel();
            AppGrowthWheelService.validateTiers(wheelMapper.lockActiveTiers());
        }
        var current = state(target, command.op());
        Object expected = command.params().get("expectedState");
        if (!current.equals(expected)) {
            if (expected instanceof Map<?, ?> snapshot && command.params().get("proposalReason") instanceof String
                    && confirmedSnapshotChange(command, target, snapshot, current)) {
                throw new ConfirmedRecheckFailure(409, "H4_CONFIG_STALE");
            }
            return ApiResult.fail(409, "H4_CONFIG_STALE");
        }
        if (command.params().get("eventCopy") instanceof Map<?, ?> copy
                && !rules.readEventContent(target.id()).equals(copy)) {
            return ApiResult.fail(409, "H4_CONFIG_STALE");
        }
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
            case LEGACY_REWARD, LEGACY_OPEN -> {
                boolean opening = LEGACY_OPEN.equals(command.op());
                var request = legacyEventUpdate(command, reason);
                yield commands.execute("H4", opening ? "EVENT_STATUS_UPDATE" : "EVENT_REWARD_UPDATE", target.id(),
                        context.idempotencyKey(), request,
                        () -> growth.executeApprovedH4LegacyEvent(context.idempotencyKey(), target.id(), request, opening));
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
            case TIER_CREATE -> {
                var request = wheelTier(command, reason);
                yield commands.execute("H4", "WHEEL_TIER_CREATE", request.tierName(), context.idempotencyKey(), request,
                        () -> growth.executeApprovedH4WheelTierCreate(context.idempotencyKey(), request));
            }
            case TIER_UPDATE -> {
                var request = wheelTier(command, reason);
                yield commands.execute("H4", "WHEEL_TIER_UPDATE", request.tierName(), context.idempotencyKey(), request,
                        () -> growth.executeApprovedH4WheelTierUpdate(context.idempotencyKey(), request));
            }
            case TIER_DELETE -> {
                var request = wheelTierDelete(command, reason);
                yield commands.execute("H4", "WHEEL_TIER_DELETE", request.key(), context.idempotencyKey(), request,
                        () -> growth.executeApprovedH4WheelTierDelete(context.idempotencyKey(), request));
            }
            case PROBABILITIES_UPDATE -> {
                var request = wheelProbabilities(command, reason);
                yield commands.execute("H4", "WHEEL_PROBABILITIES_UPDATE", target.id(), context.idempotencyKey(), request,
                        () -> growth.executeApprovedH4WheelProbabilities(context.idempotencyKey(), request));
            }
            default -> ApiResult.fail(422, "H4_PROPOSAL_INVALID");
        };
    }

    public static final class ConfirmedRecheckFailure extends BizException {
        ConfirmedRecheckFailure(int code, String reason) {
            super(code, reason);
        }
    }

    static boolean confirmedCoverageBreach(TreasuryCoverageSnapshot snapshot) {
        return snapshot.redlineBreached() && snapshot.redlinePct().signum() > 0
                && snapshot.ratioLiabilitiesUsd() != null && snapshot.ratioLiabilitiesUsd().signum() > 0;
    }

    private boolean confirmedSnapshotChange(AuditReplayCommand command, AuditLockTarget target,
                                            Map<?, ?> expected, Map<String, String> current) {
        if (WHEEL_POOL.equals(target)) {
            String before = verifiedWheelSignature(expected);
            String after = verifiedWheelSignature(current);
            return before != null && after != null && !before.equals(after)
                    && before.equals(values(command).get("expectedSignature"));
        }
        if (GUARD_UPDATE.equals(command.op()) && "budget".equals(target.id())) {
            if (!"true".equals(expected.get("exists")) || !"true".equals(current.get("exists"))
                    || !target.id().equals(expected.get("guard_key")) || !target.id().equals(current.get("guard_key"))
                    || !"1".equals(expected.get("status")) || !"1".equals(current.get("status"))
                    || !(expected.get("id") instanceof String id) || !id.matches("[1-9][0-9]{0,18}")
                    || !id.equals(current.get("id")) || !(expected.get("guard_value") instanceof String oldValue)
                    || !oldValue.trim().equals(values(command).get("expectedValue"))) {
                return false;
            }
            BigDecimal before = growth.wheelBudgetAmount(oldValue);
            BigDecimal after = growth.wheelBudgetAmount(current.get("guard_value"));
            return before != null && after != null && before.compareTo(after) != 0;
        }
        String identity;
        String payloadKey;
        if (RULE.equals(command.op()) && existingQuest(expected) && existingQuest(current)) {
            identity = "event_code";
            payloadKey = "rules_json";
        } else if (RAFFLE.equals(command.op()) && "DRAFT".equals(expected.get("status"))
                && Set.of("DRAFT", "OPEN", "PROCESSING", "DRAWN").contains(current.getOrDefault("status", ""))) {
            identity = "draw_id";
            payloadKey = "prizes_json";
        } else {
            return false;
        }
        if (!"true".equals(expected.get("exists")) || !"true".equals(current.get("exists"))
                || !target.id().equals(expected.get(identity)) || !target.id().equals(current.get(identity))
                || !(expected.get("revision") instanceof String before) || !before.matches("[1-9][0-9]{0,18}")
                || !current.getOrDefault("revision", "").matches("[1-9][0-9]{0,18}")) {
            return false;
        }
        var version = new BigDecimal(before);
        return version.compareTo(new BigDecimal(values(command).get("expectedRevision").toString())) == 0
                && new BigDecimal(current.get("revision")).compareTo(version) > 0
                && validRevisionPayload(expected.get(payloadKey), RULE.equals(command.op()))
                && validRevisionPayload(current.get(payloadKey), RULE.equals(command.op()));
    }

    private static boolean existingQuest(Map<?, ?> state) {
        return "0".equals(state.get("q_deleted")) || "false".equals(state.get("q_deleted"));
    }

    private boolean validRevisionPayload(Object stored, boolean rule) {
        if (!(stored instanceof String encoded)) return false;
        try {
            var decoded = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(encoded);
            return rule ? decoded.isObject() : decoded.isArray() && !decoded.isEmpty();
        } catch (JsonProcessingException invalid) {
            return false;
        }
    }

    private String verifiedWheelSignature(Map<?, ?> snapshot) {
        if (!(snapshot.get("tiers") instanceof String encoded)
                || !(snapshot.get("wheelSignature") instanceof String signature)) return null;
        List<Map<String, Object>> tiers;
        try {
            tiers = json.readerFor(new TypeReference<List<Map<String, Object>>>() {})
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(encoded);
        } catch (JsonProcessingException invalid) {
            return null;
        }
        if (tiers == null || tiers.size() < 2 || tiers.size() > 12) return null;
        Set<String> names = new HashSet<>();
        boolean hasComfortTier = false;
        BigDecimal total = BigDecimal.ZERO;
        for (var tier : tiers) {
            if (tier == null || !tier.keySet().equals(WHEEL_SNAPSHOT_FIELDS)
                    || !(tier.get("tier") instanceof String name) || name.isBlank() || !names.add(name)
                    || !(tier.get("reward") instanceof String label) || label.isBlank()
                    || !(tier.get("prob") instanceof Number probability) || !(tier.get("amount") instanceof Number amount)
                    || !(tier.get("kind") instanceof String kind) || !Set.of("uvt", "usdt", "coupon").contains(kind)
                    || !(tier.get("voucherId") instanceof String voucher) || ("coupon".equals(kind) && voucher.isBlank())
                    || !(tier.get("dailyStock") instanceof Integer stock) || stock < 0) {
                return null;
            }
            Object real = tier.get("real");
            boolean realOutflow = Boolean.TRUE.equals(real) || Integer.valueOf(1).equals(real);
            if (!(real instanceof Boolean || Integer.valueOf(0).equals(real) || Integer.valueOf(1).equals(real))
                    || realOutflow != "usdt".equals(kind)) return null;
            BigDecimal share = new BigDecimal(probability.toString());
            if (share.signum() < 0 || new BigDecimal(amount.toString()).signum() <= 0) return null;
            total = total.add(share);
            hasComfortTier |= "uvt".equals(kind);
        }
        return hasComfortTier && total.compareTo(BigDecimal.valueOf(100)) == 0
                && signature.equals(growth.wheelPoolSignature(tiers)) ? signature : null;
    }

    private Map<String, String> state(AuditLockTarget target, String operation) {
        if (LEGACY_EVENT_OPERATIONS.contains(operation)) {
            return growth.lockH4LegacyEventState(target.id(), LEGACY_OPEN.equals(operation));
        }
        if (WHEEL_POOL.equals(target)) {
            var tiers = growth.lockH4WheelPool();
            return Map.of("wheelSignature", growth.wheelPoolSignature(tiers),
                    "tiers", rules.encode(tiers.stream().map(TreeMap::new).toList()));
        }
        Map<String, Object> row;
        if ("h4_event_rule".equals(target.type())) {
            if (!growth.lockH4Event()) throw new BizException(409, "H4_EVENT_MUTEX_UNAVAILABLE");
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
            case RAFFLE -> Set.of("id", "expectedRevision", "enabled", "titleEn", "startsAt", "drawAt", "prizes");
            case LEGACY_REWARD, LEGACY_OPEN -> Set.of("eventCode", "value", "expectedValue");
            case GUARD_CREATE -> Set.of("guardKey", "guardLabel", "guardValue", "note");
            case GUARD_UPDATE -> Set.of("guardKey", "value", "expectedValue");
            case TIER_CREATE, TIER_UPDATE -> Set.of("tierName", "rewardName", "expectedSignature");
            case TIER_DELETE -> Set.of("tierName", "expectedSignature");
            case PROBABILITIES_UPDATE -> Set.of("probabilities", "expectedSignature");
            default -> throw new BizException(422, "H4_PROPOSAL_INVALID");
        };
        var allowed = new HashSet<>(required);
        if (RAFFLE.equals(command.op())) allowed.addAll(Set.of("titleZh", "titleVi", "titleTranslations"));
        if (LEGACY_EVENT_OPERATIONS.contains(command.op()) && command.params().containsKey("makerAdminId"))
            allowed.addAll(Set.of("rewardType", "rewardAmount"));
        if (TIER_CREATE.equals(command.op()) || TIER_UPDATE.equals(command.op()))
            allowed.addAll(Set.of("probabilityPct", "realOutflow", "rewardKind", "rewardAmount", "voucherId", "dailyStock"));
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

    private GrowthConfigUpdateRequest legacyEventUpdate(AuditReplayCommand command, String reason) {
        var values = values(command);
        if (!(values.get("value") instanceof String value) || !(values.get("expectedValue") instanceof String expected))
            throw new BizException(422, "H4_PROPOSAL_INVALID");
        return new GrowthConfigUpdateRequest(null, value, reason, null, expected);
    }

    private GrowthWheelTierRequest wheelTier(AuditReplayCommand command, String reason) {
        var values = values(command);
        return new GrowthWheelTierRequest(text(values, "tierName"), text(values, "rewardName"),
                wheelDecimal(values.get("probabilityPct")), wheelInteger(values.get("realOutflow")),
                wheelText(values.get("rewardKind")), wheelDecimal(values.get("rewardAmount")),
                wheelText(values.get("voucherId")), wheelInteger(values.get("dailyStock")),
                reason, null, text(values, "expectedSignature"));
    }

    private GrowthWheelProbabilityBatchRequest wheelProbabilities(AuditReplayCommand command, String reason) {
        var values = values(command);
        if (!(values.get("probabilities") instanceof Map<?, ?> raw)) throw new BizException(422, "H4_PROPOSAL_INVALID");
        Map<String, BigDecimal> probabilities = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String tier)) throw new BizException(422, "H4_PROPOSAL_INVALID");
            probabilities.put(tier, wheelDecimal(entry.getValue()));
        }
        return new GrowthWheelProbabilityBatchRequest(probabilities, reason, null, text(values, "expectedSignature"));
    }

    private GrowthConfigUpdateRequest wheelTierDelete(AuditReplayCommand command, String reason) {
        var values = values(command);
        return new GrowthConfigUpdateRequest(text(values, "tierName"), null, reason, null, text(values, "expectedSignature"));
    }

    private static BigDecimal wheelDecimal(Object value) {
        if (value == null) return null;
        // A2 uses untyped JSON values. Decimal strings preserve all digits without a Double round trip.
        if (value instanceof Integer || value instanceof Long) return BigDecimal.valueOf(((Number) value).longValue());
        if (value instanceof String text && text.matches("-?[0-9]+(?:\\.[0-9]+)?")) return new BigDecimal(text);
        throw new BizException(422, "H4_PROPOSAL_DECIMAL_INVALID");
    }

    private static Integer wheelInteger(Object value) {
        if (value == null || value instanceof Integer) return (Integer) value;
        throw new BizException(422, "H4_PROPOSAL_INVALID");
    }

    private static String wheelText(Object value) {
        if (value == null || value instanceof String) return (String) value;
        throw new BizException(422, "H4_PROPOSAL_INVALID");
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
