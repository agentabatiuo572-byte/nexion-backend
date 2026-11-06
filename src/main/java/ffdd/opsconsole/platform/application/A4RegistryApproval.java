package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.platform.dto.EventDomainExtensionRequest;
import ffdd.opsconsole.platform.dto.EventSchemaRegistrationRequest;
import ffdd.opsconsole.platform.dto.ExistingSchemaPropertyRequest;
import ffdd.opsconsole.platform.mapper.EventGovernanceMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class A4RegistryApproval {
    static final String MAKER_AUTHORITY = "platform_a4_write";
    static final String MAKER_FIELD = "a4RegistryMakerAdminId";
    private static final String SCHEMA = "a4_schema_register";
    private static final String PROPERTY = "a4_schema_property_add";
    private static final String EXTENSION = "a4_domain_extension_register";
    private static final String SCHEMA_TARGET = "a4_event_schema";
    private static final String EXTENSION_TARGET = "a4_domain_extension";
    private static final Set<String> SCHEMA_FIELDS = Set.of("eventName", "ownerDomain", "producer", "consumer",
            "propertyName", "propertyType", "pii", "serverAuthoritative", "samplingPolicy", "expectedVersion");
    private static final Set<String> PROPERTY_FIELDS = Set.of("eventName", "propertyName", "propertyType", "expectedVersion");
    private static final Set<String> EXTENSION_FIELDS = Set.of("domainName", "eventName", "producer", "consumer");

    private final OpsEventCenterService events;
    private final EventGovernanceMapper governance;
    private final SuperAdminAuthorization superAdmins;

    static boolean isOperation(AuditReplayCommand command) {
        return command != null && command.op() != null
                && Set.of(SCHEMA, PROPERTY, EXTENSION).contains(command.op().trim().toLowerCase(Locale.ROOT));
    }

    static boolean supports(AuditReplayCommand command) {
        return isOperation(command) && "A".equals(command.domain()) && Set.of(SCHEMA, PROPERTY, EXTENSION).contains(command.op());
    }

    static boolean isRegistrationTarget(AuditLockTarget target) {
        return target != null && target.type() != null
                && Set.of(SCHEMA_TARGET, EXTENSION_TARGET).contains(target.type().trim().toLowerCase(Locale.ROOT));
    }

    public boolean canPropose(AuditReplayCommand command) {
        return supports(command) && A2ReplayContext.authenticatedAdminId() != null
                && A2ReplayContext.hasAuthority(MAKER_AUTHORITY);
    }

    AuditReplayCommand prepare(AuditOperationProposalRequest request) {
        AuditReplayCommand command = request.command();
        if (!canPropose(command)) throw new BizException(403, "A4_REGISTRY_MAKER_FORBIDDEN");
        Map<String, Object> params = command.params();
        Set<String> allowed = switch (command.op()) {
            case SCHEMA -> SCHEMA_FIELDS;
            case PROPERTY -> PROPERTY_FIELDS;
            default -> EXTENSION_FIELDS;
        };
        if (params == null || !allowed.containsAll(params.keySet())) throw invalid();
        String reason = request.reason();
        Map<String, Object> canonical = new LinkedHashMap<>();
        try {
            switch (command.op()) {
                case SCHEMA -> {
                    var value = events.normalizeSchema(schemaRequest(params, reason));
                    var existing = validateSchemaRevisionAndProperty(value.expectedVersion(), value.eventName(), value.propertyName(), false);
                    if (existing != null && !OpsEventCenterService.schemaMetadataMatches(existing, value)) {
                        throw new BizException(409, "A4_SCHEMA_METADATA_CONFLICT");
                    }
                    canonical.putAll(Map.of("eventName", value.eventName(), "ownerDomain", value.ownerDomain(),
                            "producer", value.producer(), "consumer", value.consumer(), "propertyName", value.propertyName(),
                            "propertyType", value.propertyType(), "serverAuthoritative", value.serverAuthoritative(),
                            "samplingPolicy", value.samplingPolicy(), "expectedVersion", value.expectedVersion()));
                }
                case PROPERTY -> {
                    var value = events.normalizeSchemaProperty(propertyRequest(params, reason));
                    validateSchemaRevisionAndProperty(value.expectedVersion(), value.eventName(), value.propertyName(), true);
                    canonical.putAll(Map.of("eventName", value.eventName(), "propertyName", value.propertyName(),
                            "propertyType", value.propertyType(), "expectedVersion", value.expectedVersion()));
                }
                default -> {
                    var value = events.normalizeDomainExtension(extensionRequest(params, reason));
                    if (governance.countDomainExtension(value.domainName(), value.eventName()) > 0) {
                        throw new BizException(409, "A4_DOMAIN_EXTENSION_DUPLICATE");
                    }
                    canonical.putAll(Map.of("domainName", value.domainName(), "eventName", value.eventName(),
                            "producer", value.producer(), "consumer", value.consumer()));
                }
            }
        } catch (IllegalArgumentException ex) {
            throw new BizException(422, ex.getMessage());
        }
        canonical.put("proposalReason", reason.trim());
        var bound = new AuditReplayCommand("A", command.op(), canonical);
        AuditLockTarget expected = target(bound);
        if (expected == null || !"A4".equals(request.sourceDomain()) || !expected.id().equals(request.obj())
                || !expected.equals(request.target()) || (request.targets() != null && !request.targets().isEmpty())) {
            throw new BizException(403, "A2_BUSINESS_CONTEXT_MISMATCH");
        }
        return bound;
    }

    private EventGovernanceMapper.EventSchemaRecord validateSchemaRevisionAndProperty(
            String version, String eventName, String propertyName, boolean existingRequired) {
        Integer revision = governance.currentRevision();
        if (revision == null || revision < 1) throw new BizException(503, "A4_SCHEMA_VERSION_UNAVAILABLE");
        if (!version.equals("v" + revision)) throw new BizException(409, "A4_SCHEMA_VERSION_STALE");
        var schema = governance.findSchema(eventName);
        if (schema == null && existingRequired) throw new BizException(422, "A4_EVENT_SCHEMA_NOT_FOUND");
        if (schema != null && governance.countProperty(schema.id(), propertyName) > 0) {
            throw new BizException(409, "A4_SCHEMA_PROPERTY_DUPLICATE");
        }
        return schema;
    }

    static AuditLockTarget target(AuditReplayCommand command) {
        if (!supports(command) || command.params() == null
                || !(command.params().get("eventName") instanceof String name)
                || name.length() > 128 || !name.matches("^[a-z][a-z0-9_]*\\.[a-z0-9]+(?:_[a-z0-9]+)*$")) return null;
        return new AuditLockTarget("A", EXTENSION.equals(command.op()) ? EXTENSION_TARGET : SCHEMA_TARGET, name);
    }

    static AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor describe(AuditReplayCommand command) {
        var params = command.params();
        var target = target(command);
        String action = switch (command.op()) {
            case SCHEMA -> "登记事件";
            case PROPERTY -> "新增事件字段";
            default -> "登记域扩展批次";
        };
        String before = EXTENSION.equals(command.op()) ? "未登记" : "事件目录 " + params.get("expectedVersion");
        String after = EXTENSION.equals(command.op()) ? "登记域扩展 · " + params.get("domainName")
                : params.get("propertyName") + " · " + params.get("propertyType");
        return new AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor(
                action, target.id(), before, after, "A4", "param", false, target);
    }

    static String reviewAfter(AuditReplayCommand command) {
        var p = command.params();
        return switch (command.op()) {
            case SCHEMA -> "事件 " + p.get("eventName") + " · 所属模块 " + p.get("ownerDomain")
                    + "\n发送方 " + p.get("producer") + " · 使用方 " + p.get("consumer")
                    + "\n字段 " + p.get("propertyName") + " · " + p.get("propertyType")
                    + "\n无个人信息 · 服务端裁决 " + (Boolean.TRUE.equals(p.get("serverAuthoritative")) ? "是" : "否")
                    + " · 采样 " + p.get("samplingPolicy");
            case PROPERTY -> "事件 " + p.get("eventName") + "\n新增字段 " + p.get("propertyName") + " · " + p.get("propertyType");
            default -> "模块 " + p.get("domainName") + " · 事件 " + p.get("eventName")
                    + "\n发送方 " + p.get("producer") + " · 使用方 " + p.get("consumer");
        };
    }

    ApiResult<Void> validateChecker() {
        return A2ReplayContext.hasAuthority("platform_a2_operation_approve")
                && superAdmins.isSuperAdmin(SecurityContextHolder.getContext().getAuthentication())
                ? ApiResult.ok() : ApiResult.fail(403, "A4_REGISTRY_CHECKER_FORBIDDEN");
    }

    ApiResult<?> replay(AuditReplayCommand command) {
        if (target(command) == null || !A2ReplayContext.isReplaying() || A2ReplayContext.operationId() == null
                || validateChecker().getCode() != 0) return ApiResult.fail(403, "A2_CONFIRMATION_REQUIRED");
        var params = command.params();
        String reason = field(params, "proposalReason");
        String key = "a2-a4-" + A2ReplayContext.operationId();
        return switch (command.op()) {
            case SCHEMA -> events.registerSchema(key, schemaRequest(params, reason));
            case PROPERTY -> events.addSchemaProperty(key, propertyRequest(params, reason));
            default -> events.registerDomainExtension(key, extensionRequest(params, reason));
        };
    }

    private static EventSchemaRegistrationRequest schemaRequest(Map<String, Object> p, String reason) {
        return new EventSchemaRegistrationRequest(field(p, "eventName"), field(p, "ownerDomain"), field(p, "producer"),
                field(p, "consumer"), field(p, "propertyName"), field(p, "propertyType"), flag(p, "pii"),
                flag(p, "serverAuthoritative"), field(p, "samplingPolicy"), field(p, "expectedVersion"), reason);
    }

    private static ExistingSchemaPropertyRequest propertyRequest(Map<String, Object> p, String reason) {
        return new ExistingSchemaPropertyRequest(field(p, "eventName"), field(p, "propertyName"), field(p, "propertyType"),
                field(p, "expectedVersion"), reason);
    }

    private static EventDomainExtensionRequest extensionRequest(Map<String, Object> p, String reason) {
        return new EventDomainExtensionRequest(field(p, "domainName"), field(p, "eventName"), field(p, "producer"),
                field(p, "consumer"), reason);
    }

    private static String field(Map<String, Object> p, String key) {
        if (p.get(key) instanceof String value) return value;
        throw invalid();
    }

    private static boolean flag(Map<String, Object> p, String key) {
        if (p.get(key) == null) return false;
        if (p.get(key) instanceof Boolean value) return value;
        throw invalid();
    }

    private static BizException invalid() {
        return new BizException(422, "A4_REGISTRY_PROPOSAL_INVALID");
    }
}
