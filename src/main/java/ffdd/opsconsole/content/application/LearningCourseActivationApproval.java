package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.content.application.OpsI18nLearningService.CourseActivation;
import ffdd.opsconsole.content.application.OpsI18nLearningService.CourseActivationKind;
import ffdd.opsconsole.content.dto.I18nActionRequest;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LearningCourseActivationApproval {
    private static final String FIRST_PUBLISH = "i7_course_first_publish";
    private static final String PUBLISH = "i7_course_version_publish";
    private static final String ROLLBACK = "i7_course_version_rollback";
    private static final Set<String> OPERATIONS = Set.of(FIRST_PUBLISH, PUBLISH, ROLLBACK);
    private static final Set<String> INPUT_FIELDS = Set.of("courseId", "version", "expectedRevision",
            "expectedCurrentVersion", "expectedCurrentRevision", "expectedCurrentStatus");
    public static final Set<String> MAKER_AUTHORITIES = Set.of("content_i7_write", "content_i7_course_reward_adjust");

    private final OpsI18nLearningService learning;
    private final LearningAcceptanceSandboxGate acceptanceGate;
    private final AdminOperatorRoleResolver roles;
    private final AdminPermissionCache permissions;
    private final SuperAdminAuthorization superAdmins;
    private final TreasuryCoverageFacade coverageFacade;
    private final ObjectMapper objectMapper;

    public static boolean isOperation(AuditReplayCommand command) {
        return command != null && command.op() != null && OPERATIONS.contains(command.op());
    }

    public static boolean supports(AuditReplayCommand command) {
        return isOperation(command) && "I".equals(command.domain());
    }

    public boolean canPropose(AuditReplayCommand command) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return supports(command) && authentication != null && authentication.isAuthenticated()
                && authentication.getDetails() instanceof Map<?, ?> details
                && "ADMIN".equals(details.get("subjectType"))
                && hasMakerAuthorities(authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
    }

    public static boolean hasMakerAuthorities(Collection<String> grants) {
        return grants.containsAll(MAKER_AUTHORITIES);
    }

    public static Long makerId(AuditReplayCommand command) {
        return command.params() != null && command.params().get("makerAdminId") instanceof Number maker
                ? maker.longValue() : null;
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        return new AuditLockTarget("I", "learning_course_activation", text(command.params(), "courseId"));
    }

    public AuditReplayCommand prepare(AuditOperationProposalRequest request, Long maker) {
        acceptanceGate.requireProductionMutationAllowed();
        AuditReplayCommand command = request.command();
        if (!canPropose(command) || !isActiveMaker(maker)) throw new BizException(403, "LEARNING_ACTIVATION_MAKER_FORBIDDEN");
        if (request.reason() == null || request.reason().trim().length() < 8 || request.reason().trim().length() > 200) {
            throw new BizException(422, "REASON_LENGTH_INVALID");
        }
        var params = command.params();
        if (params == null || !params.keySet().equals(INPUT_FIELDS)) throw invalid();
        var target = target(command);
        if (!"I".equals(request.sourceDomain()) || !target.id().equals(request.obj()) || !target.equals(request.target())
                || (request.targets() != null && !request.targets().isEmpty())) {
            throw new BizException(403, "A2_BUSINESS_CONTEXT_MISMATCH");
        }
        CourseActivation activation = learning.lockCourseActivation(kind(command), target.id(), text(params, "version"));
        if (activation == null) throw new BizException(404, "LEARNING_COURSE_NOT_FOUND");
        learning.requireCourseActivation(activation);
        requireExpectedState(activation, params);
        requireCoverage(activation);

        ObjectNode before = currentReview(activation);
        ObjectNode after = targetReview(activation);
        Map<String, Object> bound = new LinkedHashMap<>(params);
        bound.put("makerAdminId", maker);
        bound.put("beforeReview", before.toString());
        bound.put("afterReview", after.toString());
        bound.put("beforeSummary", summary(before));
        bound.put("afterSummary", summary(after));
        return new AuditReplayCommand("I", command.op(), bound);
    }

    private static String summary(ObjectNode review) {
        return review.path("version").asText() + " · " + review.path("status").asText()
                + " · " + review.path("rewardNex").asText() + " UVT";
    }

    public static String action(AuditReplayCommand command) {
        return switch (kind(command)) {
            case FIRST_PUBLISH -> "首次发布奖励课程";
            case PUBLISH -> "发布奖励课程版本";
            case ROLLBACK -> "回滚至有奖励的课程版本";
        };
    }

    public ApiResult<Void> validateDecision(AuditReplayCommand command, Long checker, boolean approving) {
        Long maker = makerId(command);
        if (checker == null || maker == null
                || !superAdmins.isSuperAdmin(SecurityContextHolder.getContext().getAuthentication())
                || !permissions.getPermissionCodes(checker).contains("platform_a2_operation_approve")) {
            return ApiResult.fail(403, "LEARNING_ACTIVATION_APPROVAL_FORBIDDEN");
        }
        if (maker.equals(checker)) return ApiResult.fail(403, "A2_MAKER_CHECKER_REQUIRED");
        if (approving && !isActiveMaker(maker)) return ApiResult.fail(403, "LEARNING_ACTIVATION_MAKER_FORBIDDEN");
        return ApiResult.ok();
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context) {
        String operationId = A2ReplayContext.approvedCourseActivationId(command);
        if (operationId == null) return ApiResult.fail(403, "LEARNING_ACTIVATION_APPROVAL_FORBIDDEN");
        acceptanceGate.requireProductionMutationAllowed();
        var params = command.params();
        CourseActivation activation = learning.lockCourseActivation(kind(command), target(command).id(), text(params, "version"));
        requireExpectedState(activation, params);
        if (!currentReview(activation).toString().equals(params.get("beforeReview"))
                || !matchesReview(activation, text(params, "afterReview"))) throw stale();
        learning.requireCourseActivation(activation);
        TreasuryCoverageSnapshot coverage = requireCoverage(activation);
        return learning.activateCourse(activation, context.idempotencyKey(),
                new I18nActionRequest(context.operator(), text(params, "proposalReason")), coverage, operationId);
    }

    private boolean isActiveMaker(Long maker) {
        return maker != null && roles.isActiveAdministrator(maker) && hasMakerAuthorities(permissions.getPermissionCodes(maker));
    }

    private static CourseActivationKind kind(AuditReplayCommand command) {
        return switch (command.op()) {
            case FIRST_PUBLISH -> CourseActivationKind.FIRST_PUBLISH;
            case PUBLISH -> CourseActivationKind.PUBLISH;
            case ROLLBACK -> CourseActivationKind.ROLLBACK;
            default -> throw invalid();
        };
    }

    private static void requireExpectedState(CourseActivation activation, Map<String, Object> params) {
        var revision = params.get("expectedRevision");
        Long expectedTargetRevision = revision == null ? null : nonNegativeInteger(revision);
        long currentRevision = nonNegativeInteger(params.get("expectedCurrentRevision"));
        if (activation == null || (activation.target() == null && activation.kind() != CourseActivationKind.FIRST_PUBLISH)
                || !activation.current().version().equals(text(params, "expectedCurrentVersion"))
                || !activation.current().status().equals(text(params, "expectedCurrentStatus"))
                || activation.current().revision() != currentRevision
                || !activation.targetVersion().equals(text(params, "version"))
                || !Objects.equals(activation.target() == null ? null : activation.target().revision(), expectedTargetRevision)) {
            throw stale();
        }
    }

    private ObjectNode currentReview(CourseActivation activation) {
        var current = activation.current();
        return objectMapper.createObjectNode()
                .put("courseId", current.id()).put("version", current.version()).put("revision", current.revision())
                .put("status", current.status()).put("rewardNex", current.rewardNex().toPlainString());
    }

    private ObjectNode targetReview(CourseActivation activation) {
        var target = activation.target();
        ObjectNode after = objectMapper.valueToTree(target == null ? activation.current() : target.payload());
        after.remove(List.of("id", "title", "body", "featured", "operator", "reason", "expectedRevision", "publishState"));
        after.put("courseId", activation.current().id()).put("version", activation.targetVersion())
                .put("status", "published").put("sourceStatus", target == null ? "LEGACY_DRAFT" : target.status())
                .put("rewardNex", activation.targetReward().toPlainString());
        if (target == null) after.putNull("revision"); else after.put("revision", target.revision());
        return after;
    }

    private boolean matchesReview(CourseActivation activation, String review) {
        try {
            return objectMapper.readTree(targetReview(activation).toString()).equals(objectMapper.readTree(review));
        } catch (JsonProcessingException ex) {
            throw new BizException(422, "LEARNING_ACTIVATION_REVIEW_INVALID");
        }
    }

    private TreasuryCoverageSnapshot requireCoverage(CourseActivation activation) {
        if (!activation.amplifies()) throw new BizException(409, "LEARNING_COURSE_ACTIVATION_APPROVAL_NOT_REQUIRED");
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (!coverage.reliable() || coverage.redlinePct().signum() <= 0 || coverage.nexUsdRate().signum() <= 0) {
            throw new BizException(422, "B1_COVERAGE_DATA_UNAVAILABLE");
        }
        if (coverage.redlineBreached()) throw new BizException(422, "COVERAGE_BELOW_REDLINE");
        return coverage;
    }

    private static long nonNegativeInteger(Object value) {
        if (value instanceof Integer i && i >= 0) return i;
        if (value instanceof Long l && l >= 0) return l;
        throw invalid();
    }

    private static String text(Map<String, Object> params, String key) {
        if (params != null && params.get(key) instanceof String value && !value.isBlank()) return value.trim();
        throw invalid();
    }

    private static BizException invalid() { return new BizException(422, "LEARNING_ACTIVATION_PROPOSAL_INVALID"); }

    private static BizException stale() { return new BizException(409, "LEARNING_ACTIVATION_SNAPSHOT_CHANGED"); }
}
