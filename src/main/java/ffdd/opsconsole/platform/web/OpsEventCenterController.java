package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsEventCenterService;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.platform.application.A4EventRetentionService;
import ffdd.opsconsole.platform.dto.EventCenterMutationRequest;
import ffdd.opsconsole.platform.dto.EventCenterOverview;
import ffdd.opsconsole.platform.dto.EventDomainExtensionRequest;
import ffdd.opsconsole.platform.dto.EventSchemaRegistrationRequest;
import ffdd.opsconsole.platform.dto.EventLifecycleTransitionRequest;
import ffdd.opsconsole.platform.dto.RetentionExecutionRequest;
import ffdd.opsconsole.platform.dto.RetentionExecutionView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.Map;
import java.util.List;
import org.springframework.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/events")
@PreAuthorize("hasAuthority('platform_a4_read')")
@RequiredArgsConstructor
public class OpsEventCenterController {
    private final OpsEventCenterService eventCenterService;
    private final A4EventRetentionService eventRetentionService;
    private final A2RuntimePolicy a2RuntimePolicy;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @GetMapping("/overview")
    public ApiResult<EventCenterOverview> overview() {
        return eventCenterService.overview();
    }

    @GetMapping("/retention-runs/latest")
    public ApiResult<RetentionExecutionView> latestRetentionRun() {
        AuditLogQueryRequest query = new AuditLogQueryRequest();
        query.setAction("A4_EVENT_RETENTION_MANUAL_EXECUTED");
        query.setResourceType("A4_EVENT_RETENTION");
        query.setLimit(1);
        List<AuditLogRecord> rows = auditLogService.list(query);
        if (rows == null || rows.isEmpty()) return ApiResult.ok(null);
        return ApiResult.ok(retentionView(rows.get(0)));
    }

    @PatchMapping("/params/{paramKey}")
    @PreAuthorize("hasAuthority('platform_a4_write')")
    public ApiResult<EventCenterOverview.EventDimensionParam> updateParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody(required = false) EventCenterMutationRequest request) {
        return eventCenterService.updateParam(idempotencyKey, paramKey, request);
    }

    @PostMapping("/schema-registrations")
    @PreAuthorize("hasAuthority('platform_a4_write')")
    public ApiResult<EventCenterOverview> registerSchema(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) EventSchemaRegistrationRequest request) {
        return eventCenterService.registerSchema(idempotencyKey, request);
    }

    @PostMapping("/domain-extension-batches")
    @PreAuthorize("hasAuthority('platform_a4_write')")
    public ApiResult<EventCenterOverview.EventDomainExtensionBatch> registerDomainExtension(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) EventDomainExtensionRequest request) {
        return eventCenterService.registerDomainExtension(idempotencyKey, request);
    }

    @PostMapping("/retention-runs")
    @PreAuthorize("hasAuthority('platform_a4_write') && hasAuthority('platform_a2_write')")
    public ApiResult<RetentionExecutionView> runRetentionNow(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) RetentionExecutionRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) throw new BizException(400, "IDEMPOTENCY_KEY_REQUIRED");
        if (request == null) throw new BizException(422, "REASON_REQUIRED");
        a2RuntimePolicy.validateReason(request.reason());
        String actor = StringUtils.hasText(AdminActorResolver.resolve(null)) ? AdminActorResolver.resolve(null) : "unknown";
        String key = idempotencyKey.trim();
        return ApiResult.ok(idempotencyService.execute("A4_EVENT_RETENTION_RUN", key,
                retentionHash(actor, request.reason()), RetentionExecutionView.class,
                () -> executeRetention(actor, key, request.reason())));
    }

    private RetentionExecutionView executeRetention(String actor, String idempotencyKey, String reason) {
        A4EventRetentionService.RetentionRun run = eventRetentionService.runNow();
        RetentionExecutionView view = new RetentionExecutionView(run.retentionMonths(), run.cutoff(), run.lockAcquired(),
                0, 0, run.outboxRows(), run.behaviorFactRows());
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("A4_EVENT_RETENTION_MANUAL_EXECUTED").resourceType("A4_EVENT_RETENTION")
                .resourceId(run.cutoff().toLocalDate().toString()).actorType("ADMIN").actorUsername(actor)
                .result(run.lockAcquired() ? "SUCCESS" : "LOCKED").riskLevel("HIGH")
                .detail(Map.ofEntries(Map.entry("reason", reason.trim()), Map.entry("idempotencyKey", idempotencyKey),
                        Map.entry("retentionMonths", view.retentionMonths()), Map.entry("evaluatedAt", view.evaluatedAt().toString()),
                        Map.entry("lockAcquired", view.lockAcquired()), Map.entry("archivedRows", 0), Map.entry("deletedRows", 0),
                        Map.entry("outboxRows", view.outboxRows()), Map.entry("behaviorFactRows", view.behaviorFactRows()),
                        Map.entry("idempotent", true), Map.entry("terminalOutboxOnly", true)))
                .build());
        return view;
    }

    private String retentionHash(String actor, String reason) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    objectMapper.writeValueAsBytes(Map.of("actor", actor, "operation", "A4_EVENT_RETENTION_RUN", "reason", reason.trim()))));
        } catch (Exception ex) {
            throw new BizException(422, "A4_RETENTION_HASH_FAILED");
        }
    }

    private RetentionExecutionView retentionView(AuditLogRecord record) {
        try {
            Map<?, ?> detail = objectMapper.readValue(record.getDetailJson(), Map.class);
            return new RetentionExecutionView(number(detail.get("retentionMonths")),
                    java.time.LocalDateTime.parse(String.valueOf(detail.get("evaluatedAt"))),
                    Boolean.TRUE.equals(detail.get("lockAcquired")), number(detail.get("archivedRows")),
                    number(detail.get("deletedRows")), number(detail.get("outboxRows")),
                    number(detail.get("behaviorFactRows")));
        } catch (Exception ex) {
            return null;
        }
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @PostMapping("/schema-registrations/{eventName}/lifecycle")
    @PreAuthorize("hasAuthority('platform_a4_write')")
    public ApiResult<EventCenterOverview.EventLifecycleView> transitionLifecycle(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String eventName,
            @RequestBody(required = false) EventLifecycleTransitionRequest request) {
        return eventCenterService.transitionLifecycle(idempotencyKey, eventName, request);
    }
}
