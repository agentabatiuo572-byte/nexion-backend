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
import ffdd.opsconsole.content.dto.SupportAgentSeatAssignmentRequest;
import ffdd.opsconsole.platform.application.OpsAdminAccountService;
import ffdd.opsconsole.platform.dto.AdminAccountOverview;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsSupportAgentService {
    private static final String POSITION_MANAGER = "客服主管";
    private static final String POSITION_DEDICATED = "专属客服";
    private static final String POSITION_GENERAL = "通用客服";
    private static final String SEAT_MANAGER = "MANAGER";
    private static final String SEAT_DEDICATED = "DEDICATED";
    private static final String SEAT_GENERAL = "GENERAL";
    private static final List<String> POSITIONS = List.of(POSITION_MANAGER, POSITION_DEDICATED, POSITION_GENERAL);
    private static final List<String> SERVICE_TYPES = List.of("support", "advisor");
    private static final List<String> DEFAULT_TAGS = List.of("账户", "提现", "KYC");
    private static final Set<String> SUPPORT_OPERATOR_ROLES = Set.of("support");

    private final SupportAgentRepository repository;
    private final OpsAdminAccountService accountService;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
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

    public boolean canManageSupportSeats() {
        AdminAccountOverview.OperatorRecord actor = accountService.currentOperator().orElse(null);
        if (actor == null) {
            return false;
        }
        String actorRole = normalizedRole(actor.role());
        return "super".equals(actorRole)
                || "superadmin".equals(actorRole)
                || ("support".equals(actorRole) && supportSeatSupervisor(actor));
    }

    public Optional<SupportAgentProfileView> assignableSupportAgent(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return Optional.empty();
        }
        try {
            return assignableSupportAgent(Long.parseLong(agentId.trim()));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public Optional<SupportAgentProfileView> assignableSupportAgent(Long adminId) {
        repository.ensureSchema();
        AdminAccountOverview.OperatorRecord operator = supportOperator(adminId).orElse(null);
        if (operator == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        repository.ensureDefaultProfile(
                adminId,
                defaultSeatType(),
                defaultPosition(),
                defaultServiceTypes(),
                DEFAULT_TAGS,
                defaultMaxConcurrent(),
                now);
        return repository.findProfile(adminId)
                .map(profile -> profileView(operator, profile))
                .filter(agent -> Boolean.TRUE.equals(agent.enabled()))
                .filter(agent -> Boolean.TRUE.equals(agent.transferable()))
                .filter(agent -> agent.serviceTypes().contains("support"));
    }

    public Optional<SupportAgentProfileView> currentAssignableSupportAgent() {
        AdminAccountOverview.OperatorRecord actor = accountService.currentOperator().orElse(null);
        if (actor == null) return Optional.empty();
        return parseAdminId(actor.id()).flatMap(this::assignableSupportAgent);
    }

    public AdvisorRoutingDecision routeAdvisorForUser(Long userId) {
        repository.ensureSchema();
        List<AdminAccountOverview.OperatorRecord> operators = supportOperators();
        ensureDefaultProfiles(operators);
        List<SupportAgentProfileView> agents = profileViews(operators).stream()
                .filter(agent -> dedicatedSupportSeat(agent.seatType()))
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
            Optional<SupportAgentAssignmentView> boundAssignment = repository.listActiveAssignments(agentIds).stream()
                    .filter(row -> userId.equals(row.userId()))
                    .findFirst();
            if (boundAssignment.isPresent() && agentById.containsKey(boundAssignment.get().agentAdminId())) {
                SupportAgentProfileView agent = agentById.get(boundAssignment.get().agentAdminId());
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

    @Transactional
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
        SupportAgentProfileRecord currentProfile = repository.findProfile(adminId).orElse(null);
        String seatType = currentProfile == null
                ? defaultSeatType()
                : normalizeSeatType(currentProfile.seatType(), currentProfile.position());
        String position = positionForSeatType(seatType);
        ApiResult<SupportAgentProfileView> authorization = requireSeatMutationAuthorization(adminId, seatType);
        if (authorization != null) {
            return authorization;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> serviceTypes = normalizeSeatServiceTypes(seatType, request.serviceTypes());
        List<String> tags = normalizeTags(request.tags());
        int maxConcurrent = boundedInt(request.maxConcurrent(), 0, 40, 10);
        repository.ensureDefaultProfile(adminId, seatType, position, serviceTypes, tags, maxConcurrent, now);
        repository.updateProfile(
                adminId,
                seatType,
                position,
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

    @Transactional
    public ApiResult<SupportAgentProfileView> assignSeat(
            Long adminId,
            String idempotencyKey,
            SupportAgentSeatAssignmentRequest request) {
        repository.ensureSchema();
        ApiResult<SupportAgentProfileView> guard = requireSeatAssignmentCommand(adminId, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return idempotentCommand(
                "M1_SUPPORT_SEAT_ASSIGN",
                idempotencyKey,
                requestHash(String.valueOf(adminId), String.valueOf(request)),
                () -> assignSeatOnce(adminId, idempotencyKey, request));
    }

    private ApiResult<SupportAgentProfileView> assignSeatOnce(
            Long adminId,
            String idempotencyKey,
            SupportAgentSeatAssignmentRequest request) {
        AdminAccountOverview.OperatorRecord operator = supportOperator(adminId).orElse(null);
        if (operator == null) {
            return ApiResult.fail(404, "SUPPORT_AGENT_NOT_FOUND");
        }
        String position = canonicalPosition(request.position());
        String seatType = seatTypeForPosition(position);
        ApiResult<SupportAgentProfileView> authorization = requireSeatMutationAuthorization(adminId, seatType);
        if (authorization != null) {
            return authorization;
        }
        List<Long> userIds = normalizeUserIds(request.userIds());
        if (SEAT_DEDICATED.equals(seatType) && userIds.isEmpty()) {
            return ApiResult.fail(422, "SUPPORT_SEAT_DEDICATED_USERS_REQUIRED");
        }
        for (Long userId : userIds) {
            if (!repository.userExists(userId)) {
                return ApiResult.fail(404, "SUPPORT_ADVISOR_USER_NOT_FOUND");
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        List<String> serviceTypes = normalizeSeatServiceTypes(seatType, request.serviceTypes());
        List<String> tags = normalizeTags(request.tags());
        int maxConcurrent = boundedInt(request.maxConcurrent(), 0, 40, SEAT_DEDICATED.equals(seatType) ? 30 : 12);
        repository.ensureDefaultProfile(adminId, seatType, position, serviceTypes, tags, maxConcurrent, now);
        repository.updateProfile(
                adminId,
                seatType,
                position,
                serviceTypes,
                tags,
                maxConcurrent,
                !Boolean.FALSE.equals(request.enabled()),
                !Boolean.FALSE.equals(request.transferable()),
                Boolean.TRUE.equals(request.busy()),
                now);
        List<Long> boundUserIds = new ArrayList<>();
        if (SEAT_DEDICATED.equals(seatType)) {
            for (Long userId : userIds) {
                repository.upsertAssignment(
                        adminId,
                        userId,
                        operator(request.operator()),
                        request.reason().trim(),
                        now);
                boundUserIds.add(userId);
            }
        }
        SupportAgentProfileView view = profileView(operator, repository.findProfile(adminId).orElseThrow());
        audit("M1_SUPPORT_SEAT_ASSIGNED", "SUPPORT_AGENT_PROFILE", String.valueOf(adminId), request.operator(), Map.of(
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "seatType", view.seatType(),
                "position", view.position(),
                "serviceTypes", view.serviceTypes(),
                "boundUserIds", boundUserIds));
        return ApiResult.ok(view);
    }

    @Transactional
    public ApiResult<SupportAgentAssignmentView> assignAdvisorUser(
            Long adminId,
            String idempotencyKey,
            SupportAgentAssignmentRequest request) {
        repository.ensureSchema();
        ApiResult<SupportAgentAssignmentView> guard = requireAssignmentCommand(adminId, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return idempotentCommand(
                "M1_SUPPORT_ADVISOR_BIND",
                idempotencyKey,
                requestHash(String.valueOf(adminId), String.valueOf(request)),
                () -> assignAdvisorUserOnce(adminId, idempotencyKey, request));
    }

    private ApiResult<SupportAgentAssignmentView> assignAdvisorUserOnce(
            Long adminId,
            String idempotencyKey,
            SupportAgentAssignmentRequest request) {
        AdminAccountOverview.OperatorRecord operator = supportOperator(adminId).orElse(null);
        if (operator == null) {
            return ApiResult.fail(404, "SUPPORT_AGENT_NOT_FOUND");
        }
        SupportAgentProfileRecord profile = repository.findProfile(adminId).orElse(null);
        if (profile == null) {
            return ApiResult.fail(404, "SUPPORT_AGENT_PROFILE_NOT_CONFIGURED");
        }
        if (!dedicatedSupportSeat(profile.seatType())) {
            return ApiResult.fail(422, "SUPPORT_AGENT_NOT_DEDICATED");
        }
        ApiResult<SupportAgentAssignmentView> authorization = requireAdvisorAssignmentAuthorization(adminId);
        if (authorization != null) {
            return authorization;
        }
        if (!repository.userExists(request.userId())) {
            return ApiResult.fail(404, "SUPPORT_ADVISOR_USER_NOT_FOUND");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!profile.serviceTypes().contains("advisor")) {
            return ApiResult.fail(422, "SUPPORT_AGENT_NOT_ADVISOR");
        }
        SupportAgentAssignmentView assignment = repository.upsertAssignment(
                adminId,
                request.userId(),
                operator(request.operator()),
                request.reason().trim(),
                now);
        audit("M5_SUPPORT_ADVISOR_USER_BOUND", "SUPPORT_ADVISOR_ASSIGNMENT", adminId + ":" + request.userId(), request.operator(), Map.of(
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(assignment);
    }

    @Transactional
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
        if (!StringUtils.hasText(reason) || reason.trim().length() < 8) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (reason.trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REASON_TOO_LONG");
        }
        return idempotentCommand(
                "M1_SUPPORT_ADVISOR_UNBIND",
                idempotencyKey,
                requestHash(String.valueOf(adminId), String.valueOf(assignmentId), String.valueOf(request)),
                () -> deactivateAdvisorAssignmentOnce(adminId, assignmentId, idempotencyKey, request, reason));
    }

    private ApiResult<SupportAgentAssignmentView> deactivateAdvisorAssignmentOnce(
            Long adminId,
            Long assignmentId,
            String idempotencyKey,
            SupportAgentAssignmentRequest request,
            String reason) {
        AdminAccountOverview.OperatorRecord operator = supportOperator(adminId).orElse(null);
        if (operator == null) {
            return ApiResult.fail(404, "SUPPORT_AGENT_NOT_FOUND");
        }
        SupportAgentProfileRecord profile = repository.findProfile(adminId).orElse(null);
        if (profile == null || !dedicatedSupportSeat(profile.seatType())) {
            return ApiResult.fail(422, "SUPPORT_AGENT_NOT_DEDICATED");
        }
        ApiResult<SupportAgentAssignmentView> authorization = requireAdvisorAssignmentAuthorization(adminId);
        if (authorization != null) {
            return authorization;
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ApiResult<T> idempotentCommand(
            String scope,
            String idempotencyKey,
            String requestHash,
            java.util.function.Supplier<ApiResult<T>> action) {
        return (ApiResult<T>) idempotencyService.execute(
                scope,
                idempotencyKey.trim(),
                requestHash,
                ApiResult.class,
                (java.util.function.Supplier) action);
    }

    private String requestHash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
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
            parseAdminId(operator.id()).ifPresent(adminId -> repository.ensureDefaultProfile(
                    adminId,
                    defaultSeatType(),
                    defaultPosition(),
                    defaultServiceTypes(),
                    DEFAULT_TAGS,
                    defaultMaxConcurrent(),
                    now));
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
                normalizeSeatType(profile.seatType(), profile.position()),
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
                .filter(operator -> SUPPORT_OPERATOR_ROLES.contains(normalizedRole(operator.role())))
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

    private ApiResult<SupportAgentAssignmentView> requireAdvisorAssignmentAuthorization(Long targetAdminId) {
        AdminAccountOverview.OperatorRecord actor = accountService.currentOperator().orElse(null);
        if (actor == null) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "SUPPORT_ADVISOR_ASSIGNMENT_FORBIDDEN");
        }
        String actorRole = normalizedRole(actor.role());
        if ("super".equals(actorRole) || "superadmin".equals(actorRole)) {
            return null;
        }
        if (supportSeatSupervisor(actor) && !String.valueOf(targetAdminId).equals(actor.id())) {
            return null;
        }
        return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "SUPPORT_ADVISOR_ASSIGNMENT_FORBIDDEN");
    }

    private ApiResult<SupportAgentProfileView> requireSeatMutationAuthorization(Long targetAdminId, String seatType) {
        AdminAccountOverview.OperatorRecord actor = accountService.currentOperator().orElse(null);
        if (actor == null) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "SUPPORT_SEAT_ASSIGNMENT_FORBIDDEN");
        }
        String actorRole = normalizedRole(actor.role());
        if ("super".equals(actorRole) || "superadmin".equals(actorRole)) {
            return null;
        }
        if (!"support".equals(actorRole) || !supportSeatSupervisor(actor)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "SUPPORT_SEAT_ASSIGNMENT_FORBIDDEN");
        }
        if (String.valueOf(targetAdminId).equals(actor.id())) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "SUPPORT_SEAT_ASSIGNMENT_SELF_FORBIDDEN");
        }
        if (SEAT_MANAGER.equals(seatType)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "SUPPORT_SEAT_MANAGER_ASSIGNMENT_SUPER_ONLY");
        }
        return null;
    }

    private boolean supportSeatSupervisor(AdminAccountOverview.OperatorRecord actor) {
        Long actorAdminId = parseAdminId(actor.id()).orElse(null);
        if (actorAdminId == null) {
            return false;
        }
        return repository.findProfile(actorAdminId)
                .map(profile -> normalizeSeatType(profile.seatType(), profile.position()))
                .filter(SEAT_MANAGER::equals)
                .isPresent();
    }

    private String defaultSeatType() {
        return SEAT_GENERAL;
    }

    private String defaultPosition() {
        return POSITION_GENERAL;
    }

    private List<String> defaultServiceTypes() {
        return List.of("support");
    }

    private int defaultMaxConcurrent() {
        return 12;
    }

    private boolean dedicatedSupportSeat(String seatType) {
        return SEAT_DEDICATED.equals(normalizeSeatType(seatType, null));
    }

    private String seatTypeForPosition(String position) {
        return switch (canonicalPosition(position)) {
            case POSITION_MANAGER -> SEAT_MANAGER;
            case POSITION_DEDICATED -> SEAT_DEDICATED;
            default -> SEAT_GENERAL;
        };
    }

    private String positionForSeatType(String seatType) {
        return switch (normalizeSeatType(seatType, null)) {
            case SEAT_MANAGER -> POSITION_MANAGER;
            case SEAT_DEDICATED -> POSITION_DEDICATED;
            default -> POSITION_GENERAL;
        };
    }

    private String normalizeSeatType(String seatType, String position) {
        if (StringUtils.hasText(seatType)) {
            String normalized = seatType.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if (List.of(SEAT_MANAGER, SEAT_DEDICATED, SEAT_GENERAL).contains(normalized)) {
                return normalized;
            }
        }
        return seatTypeForPosition(position);
    }

    private String canonicalPosition(String position) {
        if (!StringUtils.hasText(position)) {
            return POSITION_GENERAL;
        }
        String raw = position.trim();
        if (POSITIONS.contains(raw)) {
            return raw;
        }
        if (raw.contains("主管")) {
            return POSITION_MANAGER;
        }
        if (raw.contains("专属") || raw.contains("顾问")) {
            return POSITION_DEDICATED;
        }
        return POSITION_GENERAL;
    }

    private String normalizedRole(String role) {
        return StringUtils.hasText(role) ? role.trim().toLowerCase(Locale.ROOT).replace('-', '_') : "";
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
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_AGENT_REQUIRED");
        }
        if (StringUtils.hasText(request.position()) && request.position().trim().length() > 64) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_AGENT_POSITION_TOO_LONG");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 8) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (request.reason().trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REASON_TOO_LONG");
        }
        if (!StringUtils.hasText(request.operator())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OPERATOR_REQUIRED");
        }
        return null;
    }

    private ApiResult<SupportAgentProfileView> requireSeatAssignmentCommand(
            Long adminId,
            String idempotencyKey,
            SupportAgentSeatAssignmentRequest request) {
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
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 8) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (request.reason().trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REASON_TOO_LONG");
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
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 8) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (request.reason().trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REASON_TOO_LONG");
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

    private List<String> normalizeSeatServiceTypes(String seatType, List<String> serviceTypes) {
        List<String> normalized = normalizeServiceTypes(serviceTypes);
        if (SEAT_DEDICATED.equals(seatType)) {
            LinkedHashSet<String> result = new LinkedHashSet<>(normalized);
            result.add("advisor");
            return new ArrayList<>(result);
        }
        return List.of("support");
    }

    private List<Long> normalizeUserIds(List<Long> userIds) {
        if (userIds == null) {
            return List.of();
        }
        return userIds.stream()
                .filter(userId -> userId != null && userId > 0)
                .distinct()
                .toList();
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
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
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
