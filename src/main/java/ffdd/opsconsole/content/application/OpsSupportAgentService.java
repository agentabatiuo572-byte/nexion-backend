package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.AdvisorRoutingDecision;
import ffdd.opsconsole.content.domain.SupportAgentAssignmentView;
import ffdd.opsconsole.content.domain.SupportAgentOverview;
import ffdd.opsconsole.content.domain.SupportAgentPageView;
import ffdd.opsconsole.content.domain.SupportAgentProfileRecord;
import ffdd.opsconsole.content.domain.SupportAgentProfileView;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.dto.SupportAgentAssignmentRequest;
import ffdd.opsconsole.content.dto.SupportAgentProfileUpdateRequest;
import ffdd.opsconsole.content.dto.SupportAgentQueryRequest;
import ffdd.opsconsole.platform.application.OpsAdminAccountService;
import ffdd.opsconsole.platform.dto.AdminAccountOverview;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsSupportAgentService {
    private static final List<String> POSITIONS = List.of("一线客服", "客服主管", "技术支持", "合规客服", "专属顾问");
    private static final List<String> SERVICE_TYPES = List.of("support", "advisor");
    private static final List<String> DEFAULT_TAGS = List.of("账户", "提现", "KYC");
    private static final Set<String> ASSIGNMENT_TYPES = Set.of("PRIMARY", "BACKUP");

    private final SupportAgentRepository repository;
    private final OpsAdminAccountService accountService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public ApiResult<SupportAgentOverview> overview() {
        repository.ensureSchema();
        List<AdminAccountOverview.OperatorRecord> operators = supportOperators();
        ensureDefaultProfiles(operators);
        List<SupportAgentProfileView> agents = profileViews(operators);
        List<Long> agentIds = agents.stream().map(SupportAgentProfileView::adminId).toList();
        List<SupportAgentAssignmentView> assignments = repository.listActiveAssignments(agentIds);
        return ApiResult.ok(new SupportAgentOverview(
                agents,
                assignments,
                transferTargets(agents),
                POSITIONS,
                SERVICE_TYPES,
                List.of("nx_admin", "nx_support_agent_profile", "nx_support_agent_user_assignment")));
    }

    public ApiResult<SupportAgentPageView> agents(SupportAgentQueryRequest request) {
        repository.ensureSchema();
        List<AdminAccountOverview.OperatorRecord> operators = supportOperators();
        ensureDefaultProfiles(operators);
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        int from = (int) Math.min(operators.size(), Math.max(0, (pageNum - 1) * pageSize));
        int to = (int) Math.min(operators.size(), from + pageSize);
        List<AdminAccountOverview.OperatorRecord> pageOperators = operators.subList(from, to);
        List<SupportAgentProfileView> agents = profileViews(pageOperators);
        List<Long> agentIds = agents.stream().map(SupportAgentProfileView::adminId).toList();
        return ApiResult.ok(new SupportAgentPageView(
                operators.size(),
                pageNum,
                pageSize,
                agents,
                repository.listActiveAssignments(agentIds),
                POSITIONS,
                SERVICE_TYPES,
                List.of("nx_admin", "nx_support_agent_profile", "nx_support_agent_user_assignment")));
    }

    public List<Map<String, Object>> transferTargets() {
        repository.ensureSchema();
        List<AdminAccountOverview.OperatorRecord> operators = supportOperators();
        ensureDefaultProfiles(operators);
        return transferTargets(profileViews(operators));
    }

    public AdvisorRoutingDecision routeAdvisorForUser(Long userId) {
        repository.ensureSchema();
        List<AdminAccountOverview.OperatorRecord> operators = supportOperators();
        ensureDefaultProfiles(operators);
        List<SupportAgentProfileView> agents = profileViews(operators).stream()
                .filter(agent -> agent.serviceTypes().contains("advisor"))
                .filter(agent -> Boolean.TRUE.equals(agent.enabled()))
                .filter(agent -> Boolean.TRUE.equals(agent.transferable()))
                .filter(agent -> !Boolean.TRUE.equals(agent.busy()))
                .toList();
        if (userId != null) {
            List<Long> agentIds = agents.stream().map(SupportAgentProfileView::adminId).toList();
            Map<Long, SupportAgentProfileView> agentById = agents.stream()
                    .collect(Collectors.toMap(
                            SupportAgentProfileView::adminId,
                            Function.identity(),
                            (left, right) -> left,
                            LinkedHashMap::new));
            Optional<SupportAgentAssignmentView> primary = repository.listActiveAssignments(agentIds).stream()
                    .filter(assignment -> userId.equals(assignment.userId()))
                    .filter(assignment -> "PRIMARY".equalsIgnoreCase(assignment.assignmentType()))
                    .findFirst();
            Optional<SupportAgentAssignmentView> backup = repository.listActiveAssignments(agentIds).stream()
                    .filter(assignment -> userId.equals(assignment.userId()))
                    .filter(assignment -> "BACKUP".equalsIgnoreCase(assignment.assignmentType()))
                    .findFirst();
            Optional<SupportAgentAssignmentView> assignment = primary.or(() -> backup);
            if (assignment.isPresent() && agentById.containsKey(assignment.get().agentAdminId())) {
                SupportAgentProfileView agent = agentById.get(assignment.get().agentAdminId());
                return new AdvisorRoutingDecision(
                        "agent",
                        String.valueOf(agent.adminId()),
                        agent.name(),
                        agent.adminId(),
                        true,
                        false,
                        "M5_ASSIGNED_ADVISOR");
            }
        }
        Optional<SupportAgentProfileView> available = agents.stream()
                .filter(agent -> agent.maxConcurrent() == null || agent.assignedUserCount() < agent.maxConcurrent())
                .findFirst();
        if (available.isPresent()) {
            SupportAgentProfileView agent = available.get();
            return new AdvisorRoutingDecision(
                    "agent",
                    String.valueOf(agent.adminId()),
                    agent.name(),
                    agent.adminId(),
                    false,
                    false,
                    "ADVISOR_POOL");
        }
        return new AdvisorRoutingDecision(
                "standby",
                "standby-pool",
                "备勤池",
                null,
                false,
                true,
                "NO_ADVISOR_AVAILABLE");
    }

    public ApiResult<SupportAgentProfileView> updateProfile(
            Long adminId,
            String idempotencyKey,
            SupportAgentProfileUpdateRequest request) {
        repository.ensureSchema();
        ApiResult<SupportAgentProfileView> guard = requireProfileCommand(adminId, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        AdminAccountOverview.OperatorRecord operator = supportOperator(adminId).orElse(null);
        if (operator == null) {
            return ApiResult.fail(404, "SUPPORT_AGENT_NOT_FOUND");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        repository.ensureDefaultProfile(adminId, defaultPosition(), List.of("support"), DEFAULT_TAGS, 10, now);
        List<String> serviceTypes = normalizeServiceTypes(request.serviceTypes());
        List<String> tags = normalizeTags(request.tags());
        int maxConcurrent = boundedInt(request.maxConcurrent(), 0, 40, 10);
        repository.updateProfile(
                adminId,
                request.position().trim(),
                serviceTypes,
                tags,
                maxConcurrent,
                !Boolean.FALSE.equals(request.enabled()),
                !Boolean.FALSE.equals(request.transferable()),
                Boolean.TRUE.equals(request.busy()),
                now);
        SupportAgentProfileView view = profileView(operator, repository.findProfile(adminId).orElseThrow());
        audit("M5_SUPPORT_AGENT_PROFILE_CHANGED", "SUPPORT_AGENT_PROFILE", String.valueOf(adminId), request.operator(), Map.of(
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "position", view.position(),
                "serviceTypes", view.serviceTypes(),
                "maxConcurrent", view.maxConcurrent()));
        return ApiResult.ok(view);
    }

    public ApiResult<SupportAgentAssignmentView> assignAdvisorUser(
            Long adminId,
            String idempotencyKey,
            SupportAgentAssignmentRequest request) {
        repository.ensureSchema();
        ApiResult<SupportAgentAssignmentView> guard = requireAssignmentCommand(adminId, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        AdminAccountOverview.OperatorRecord operator = supportOperator(adminId).orElse(null);
        if (operator == null) {
            return ApiResult.fail(404, "SUPPORT_AGENT_NOT_FOUND");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        repository.ensureDefaultProfile(adminId, defaultPosition(), List.of("support"), DEFAULT_TAGS, 10, now);
        SupportAgentProfileRecord profile = repository.findProfile(adminId).orElseThrow();
        if (!profile.serviceTypes().contains("advisor")) {
            return ApiResult.fail(422, "SUPPORT_AGENT_NOT_ADVISOR");
        }
        String assignmentType = normalizeAssignmentType(request.assignmentType());
        SupportAgentAssignmentView assignment = repository.upsertAssignment(
                adminId,
                request.userId(),
                assignmentType,
                operator(request.operator()),
                request.reason().trim(),
                now);
        audit("M5_SUPPORT_ADVISOR_USER_BOUND", "SUPPORT_ADVISOR_ASSIGNMENT", adminId + ":" + request.userId(), request.operator(), Map.of(
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "assignmentType", assignmentType));
        return ApiResult.ok(assignment);
    }

    public ApiResult<SupportAgentAssignmentView> deactivateAdvisorAssignment(
            Long adminId,
            Long assignmentId,
            String idempotencyKey,
            SupportAgentAssignmentRequest request) {
        repository.ensureSchema();
        if (adminId == null || assignmentId == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_ADVISOR_ASSIGNMENT_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        String reason = request == null ? null : request.reason();
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        Optional<SupportAgentAssignmentView> deactivated = repository.deactivateAssignment(
                adminId,
                assignmentId,
                operator(request.operator()),
                reason.trim(),
                LocalDateTime.now(clock));
        if (deactivated.isEmpty()) {
            return ApiResult.fail(404, "SUPPORT_ADVISOR_ASSIGNMENT_NOT_FOUND");
        }
        audit("M5_SUPPORT_ADVISOR_USER_UNBOUND", "SUPPORT_ADVISOR_ASSIGNMENT", String.valueOf(assignmentId), request.operator(), Map.of(
                "reason", reason.trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "agentAdminId", adminId,
                "userId", deactivated.get().userId()));
        return ApiResult.ok(deactivated.get());
    }

    private List<SupportAgentProfileView> profileViews(List<AdminAccountOverview.OperatorRecord> operators) {
        List<Long> adminIds = operators.stream()
                .map(AdminAccountOverview.OperatorRecord::id)
                .map(this::parseAdminId)
                .flatMap(Optional::stream)
                .toList();
        Map<Long, SupportAgentProfileRecord> profiles = repository.listProfiles(adminIds).stream()
                .collect(Collectors.toMap(
                        SupportAgentProfileRecord::adminId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<SupportAgentProfileView> views = new ArrayList<>();
        for (AdminAccountOverview.OperatorRecord operator : operators) {
            Long adminId = parseAdminId(operator.id()).orElse(null);
            if (adminId == null) {
                continue;
            }
            SupportAgentProfileRecord profile = profiles.get(adminId);
            if (profile != null) {
                views.add(profileView(operator, profile));
            }
        }
        return views;
    }

    private void ensureDefaultProfiles(List<AdminAccountOverview.OperatorRecord> operators) {
        LocalDateTime now = LocalDateTime.now(clock);
        for (AdminAccountOverview.OperatorRecord operator : operators) {
            parseAdminId(operator.id()).ifPresent(adminId ->
                    repository.ensureDefaultProfile(adminId, defaultPosition(), List.of("support"), DEFAULT_TAGS, 10, now));
        }
    }

    private SupportAgentProfileView profileView(
            AdminAccountOverview.OperatorRecord operator,
            SupportAgentProfileRecord profile) {
        Long adminId = profile.adminId();
        return new SupportAgentProfileView(
                String.valueOf(adminId),
                adminId,
                operator.name(),
                operator.email(),
                operator.role(),
                operator.status(),
                profile.position(),
                profile.serviceTypes(),
                profile.tags(),
                profile.maxConcurrent(),
                profile.enabled(),
                profile.transferable(),
                profile.busy(),
                repository.countActiveAssignments(adminId),
                profile.updatedAt());
    }

    private List<Map<String, Object>> transferTargets(List<SupportAgentProfileView> agents) {
        List<Map<String, Object>> targets = new ArrayList<>();
        targets.add(target("queue", "support", "客服队列", "客服队列", List.of("support")));
        targets.add(target("queue", "advisor", "专属顾问队列", "专属顾问队列", List.of("advisor")));
        targets.add(target("standby", "standby-pool", "备勤池", "备勤池", SERVICE_TYPES));
        agents.stream()
                .filter(agent -> Boolean.TRUE.equals(agent.enabled()))
                .filter(agent -> Boolean.TRUE.equals(agent.transferable()))
                .forEach(agent -> targets.add(target(
                        "agent",
                        String.valueOf(agent.adminId()),
                        agent.name(),
                        agent.position(),
                        agent.serviceTypes())));
        return targets;
    }

    private Map<String, Object> target(String type, String id, String name, String position, List<String> serviceTypes) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("targetType", type);
        target.put("targetId", id);
        target.put("targetName", name);
        target.put("position", position);
        target.put("serviceTypes", serviceTypes);
        return target;
    }

    private List<AdminAccountOverview.OperatorRecord> supportOperators() {
        ApiResult<AdminAccountOverview> overview = accountService.overview();
        if (overview == null || overview.getData() == null || overview.getData().operators() == null) {
            return List.of();
        }
        return overview.getData().operators().stream()
                .filter(operator -> "support".equalsIgnoreCase(operator.role()))
                .filter(operator -> "enabled".equalsIgnoreCase(operator.status()))
                .filter(operator -> parseAdminId(operator.id()).isPresent())
                .toList();
    }

    private Optional<AdminAccountOverview.OperatorRecord> supportOperator(Long adminId) {
        if (adminId == null) {
            return Optional.empty();
        }
        return supportOperators().stream()
                .filter(operator -> operator.id().equals(String.valueOf(adminId)))
                .findFirst();
    }

    private String defaultPosition() {
        return "一线客服";
    }

    private Optional<Long> parseAdminId(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private ApiResult<SupportAgentProfileView> requireProfileCommand(
            Long adminId,
            String idempotencyKey,
            SupportAgentProfileUpdateRequest request) {
        if (adminId == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_AGENT_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.position())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_AGENT_POSITION_REQUIRED");
        }
        if (request.position().trim().length() > 64) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_AGENT_POSITION_TOO_LONG");
        }
        if (normalizeServiceTypes(request.serviceTypes()).isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_AGENT_SERVICE_TYPE_REQUIRED");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!StringUtils.hasText(request.operator())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OPERATOR_REQUIRED");
        }
        return null;
    }

    private ApiResult<SupportAgentAssignmentView> requireAssignmentCommand(
            Long adminId,
            String idempotencyKey,
            SupportAgentAssignmentRequest request) {
        if (adminId == null || request == null || request.userId() == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_ADVISOR_USER_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!ASSIGNMENT_TYPES.contains(normalizeAssignmentType(request.assignmentType()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_ADVISOR_ASSIGNMENT_TYPE_INVALID");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!StringUtils.hasText(request.operator())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OPERATOR_REQUIRED");
        }
        return null;
    }

    private List<String> normalizeServiceTypes(List<String> serviceTypes) {
        if (serviceTypes == null) {
            return List.of();
        }
        LinkedHashSet<String> result = serviceTypes.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(SERVICE_TYPES::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new ArrayList<>(result);
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(value -> value.length() <= 24)
                .distinct()
                .limit(10)
                .toList();
    }

    private String normalizeAssignmentType(String assignmentType) {
        return StringUtils.hasText(assignmentType)
                ? assignmentType.trim().toUpperCase(Locale.ROOT)
                : "PRIMARY";
    }

    private int boundedInt(Integer value, int min, int max, int fallback) {
        int raw = value == null ? fallback : value;
        return Math.max(min, Math.min(max, raw));
    }

    private long normalizePage(Long pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private long normalizeSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, 100);
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private void audit(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail)
                .build());
    }
}
