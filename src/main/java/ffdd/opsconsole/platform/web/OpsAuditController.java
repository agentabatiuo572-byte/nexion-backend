package ffdd.opsconsole.platform.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsQueryRequest;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.application.OpsAuditCenterService;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.platform.application.A2AccessPolicy;
import ffdd.opsconsole.shared.audit.AuditRetentionService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import ffdd.opsconsole.platform.dto.AuditCenterOverview;
import ffdd.opsconsole.platform.dto.AuditExportRequest;
import ffdd.opsconsole.platform.dto.AuditMechanismParamUpdateRequest;
import ffdd.opsconsole.platform.dto.AuditOperationDecisionRequest;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.platform.dto.AuditOperationWithdrawRequest;
import ffdd.opsconsole.platform.dto.RetentionExecutionRequest;
import ffdd.opsconsole.platform.dto.RetentionExecutionView;
import ffdd.opsconsole.platform.dto.RetentionPreviewView;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/audit")
@PreAuthorize("hasAuthority('platform_a2_read')")
@RequiredArgsConstructor
public class OpsAuditController {
    private static final int MAX_EXPORT_ROWS = 5000;
    private final AuditLogService auditLogService;
    private final OpsAuditCenterService auditCenterService;
    private final AdminOperatorRoleResolver operatorRoleResolver;
    private final A2AccessPolicy accessPolicy;
    private final AdminIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final A2RuntimePolicy a2RuntimePolicy;
    private final AuditRetentionService auditRetentionService;

    @GetMapping("/reason-policy")
    @PreAuthorize("isAuthenticated()")
    public ApiResult<A2RuntimePolicy.ReasonPolicyView> reasonPolicy() {
        return ApiResult.ok(a2RuntimePolicy.reasonPolicy());
    }

    @GetMapping("/retention-runs/latest")
    public ApiResult<RetentionExecutionView> latestRetentionRun() {
        AuditLogQueryRequest query = new AuditLogQueryRequest();
        query.setAction("A2_AUDIT_RETENTION_MANUAL_EXECUTED");
        query.setResourceType("A2_AUDIT_RETENTION");
        query.setLimit(1);
        List<AuditLogRecord> rows = auditLogService.list(query);
        if (rows == null || rows.isEmpty()) return ApiResult.ok(null);
        return ApiResult.ok(retentionView(rows.get(0)));
    }

    @GetMapping("/retention-preview")
    public ApiResult<RetentionPreviewView> retentionPreview() {
        AuditRetentionService.RetentionPreview preview = auditRetentionService.preview();
        return ApiResult.ok(new RetentionPreviewView(
                preview.retentionMonths(), preview.eligibleRows(), preview.earliestExpireAt(),
                preview.notYetExpiredRows(), preview.legacyRowsWithoutExpireAt(),
                preview.archiveRequired(), preview.approvalAuthority()));
    }

    @PostMapping("/retention-runs")
    @PreAuthorize("hasAuthority('platform_a2_write')")
    public ApiResult<RetentionExecutionView> runRetentionNow(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) RetentionExecutionRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) throw new BizException(400, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        if (request == null) throw new BizException(422, "REASON_REQUIRED");
        a2RuntimePolicy.validateReason(request.reason());
        String actor = StringUtils.hasText(AdminActorResolver.resolve(null)) ? AdminActorResolver.resolve(null) : "unknown";
        String key = idempotencyKey.trim();
        return ApiResult.ok(idempotencyService.execute("A2_AUDIT_RETENTION_RUN", key,
                retentionHash(actor, request.reason()), RetentionExecutionView.class,
                () -> executeRetention(actor, key, request.reason())));
    }

    private RetentionExecutionView executeRetention(String actor, String idempotencyKey, String reason) {
        AuditRetentionService.RetentionRun run = auditRetentionService.runNow();
        RetentionExecutionView view = new RetentionExecutionView(run.retentionMonths(), run.cutoff(), run.lockAcquired(),
                run.archivedRows(), run.affectedRows(), 0, 0);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("A2_AUDIT_RETENTION_MANUAL_EXECUTED").resourceType("A2_AUDIT_RETENTION")
                .resourceId(run.cutoff().toLocalDate().toString()).actorType("ADMIN").actorUsername(actor)
                .result(run.lockAcquired() ? "SUCCESS" : "LOCKED").riskLevel("HIGH")
                .detail(Map.ofEntries(Map.entry("reason", reason.trim()), Map.entry("idempotencyKey", idempotencyKey),
                        Map.entry("retentionMonths", view.retentionMonths()), Map.entry("evaluatedAt", view.evaluatedAt().toString()),
                        Map.entry("lockAcquired", view.lockAcquired()), Map.entry("archivedRows", view.archivedRows()),
                        Map.entry("deletedRows", view.deletedRows()), Map.entry("outboxRows", 0), Map.entry("behaviorFactRows", 0),
                        Map.entry("idempotent", true), Map.entry("explicitExpireAtOnly", true), Map.entry("coldArchiveRequired", true)))
                .build());
        return view;
    }

    private RetentionExecutionView retentionView(AuditLogRecord record) {
        try {
            Map<?, ?> detail = objectMapper.readValue(record.getDetailJson(), Map.class);
            return new RetentionExecutionView(number(detail.get("retentionMonths")),
                    LocalDateTime.parse(String.valueOf(detail.get("evaluatedAt"))), Boolean.TRUE.equals(detail.get("lockAcquired")),
                    number(detail.get("archivedRows")), number(detail.get("deletedRows")),
                    number(detail.get("outboxRows")), number(detail.get("behaviorFactRows")));
        } catch (Exception ex) {
            return null;
        }
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String retentionHash(String actor, String reason) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    objectMapper.writeValueAsBytes(Map.of("actor", actor, "operation", "A2_AUDIT_RETENTION_RUN", "reason", reason.trim()))));
        } catch (Exception ex) {
            throw new BizException(422, "A2_RETENTION_HASH_FAILED");
        }
    }

    public ApiResult<AuditCenterOverview> overview() {
        return overview(new AuditLogQueryRequest());
    }

    @GetMapping("/overview")
    public ApiResult<AuditCenterOverview> overview(AuditLogQueryRequest request) {
        return auditCenterService.overview(accessPolicy.constrain(request));
    }

    @GetMapping("/logs")
    public ApiResult<List<AuditLogRecord>> logs(AuditLogQueryRequest request) {
        return ApiResult.ok(auditLogService.list(accessPolicy.constrain(request)));
    }

    @GetMapping("/operations/proposals/by-idempotency-key")
    @PreAuthorize("hasAnyAuthority('platform_a2_read','platform_a2_proposal_create','platform_a4_write','growth_h7_write','overview_b1_redline_write','overview_b5_threshold_write',"
            + "'device_e2_write','growth_h8_write','growth_h5_write','growth_h3_write','growth_h4_write','growth_h4_wheel_pool_write','network_f1_write','network_f3_write','network_f4_pool_fund','finprod_g3_curve_target_price_write',"
            + "'finprod_g3_curve_pump_prob_write','finprod_g3_override_price_write','finprod_g3_write','finprod_g4_dividend_rate_write','finprod_g4_write','finprod_g4_price_write')"
            + " || (hasAuthority('content_i7_write') && hasAuthority('content_i7_course_reward_adjust'))"
            + " || (hasAuthority('content_i7_course_reward_adjust') && hasAnyAuthority('platform_a2_write','platform_a2_proposal_create'))"
            + " || (hasAuthority('risk_k2_write') && hasAnyAuthority('platform_a2_proposal_create','platform_a2_write'))"
            + " || (hasAuthority('emergency_j1_gate_resume') && hasAuthority('platform_a2_write'))")
    public ApiResult<AuditCenterOverview.AuditOperationTicket> proposalByIdempotencyKey(
            @RequestParam(name = "idempotencyKey", required = false) String idempotencyKey) {
        if (idempotencyKey == null) {
            return ApiResult.fail(400, "idempotencyKey is required");
        }
        return auditCenterService.proposalByIdempotencyKey(idempotencyKey);
    }

    @GetMapping("/logs/trace/{traceId}")
    public ApiResult<List<AuditLogRecord>> byTrace(@PathVariable String traceId) {
        AuditLogQueryRequest request = new AuditLogQueryRequest();
        request.setTraceId(traceId);
        request.setLimit(200);
        return ApiResult.ok(auditLogService.list(accessPolicy.constrain(request)));
    }

    @PostMapping("/exports")
    @PreAuthorize("hasAuthority('platform_a2_export')")
    public ResponseEntity<?> export(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AuditExportRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return jsonFailure(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        int reasonLength = request == null || request.reason() == null ? 0
                : request.reason().trim().codePointCount(0, request.reason().trim().length());
        if (request == null || !StringUtils.hasText(request.reason())
                || reasonLength < a2RuntimePolicy.reasonMinChars()) {
            return jsonFailure(OpsErrorCode.REASON_REQUIRED);
        }
        if (reasonLength > 200) {
            return jsonFailure(OpsErrorCode.VALIDATION_FAILED);
        }

        String normalizedKey = idempotencyKey.trim();
        String actor = AdminActorResolver.resolve(null);
        AuditExportResult export;
        try {
            AuditLogQueryRequest query = auditExportQuery(request);
            AuditExportScope currentScope = AuditExportScope.from(query);
            export = idempotencyService.execute("A2_COMMAND", normalizedKey,
                    exportHash(actor, request), AuditExportResult.class,
                    () -> createExport(normalizedKey, actor, request, query, currentScope));
            if (!currentScope.canRecover(export.scope(), query.getDomain())) {
                throw new BizException(403, "A2_EXPORT_SCOPE_CHANGED");
            }
        } catch (BizException ex) {
            return ResponseEntity.status(ex.getCode()).contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResult.fail(ex.getCode(), ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResult.fail(422, ex.getMessage()));
        }
        String extension = export.csv() ? ".csv" : ".xls";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(export.csv()
                        ? "text/csv;charset=UTF-8" : "application/vnd.ms-excel;charset=UTF-8"))
                .contentLength(export.body().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.jobNo() + extension, StandardCharsets.UTF_8).build().toString())
                .body(export.body());
    }

    private AuditExportResult createExport(String idempotencyKey, String actor, AuditExportRequest request,
                                           AuditLogQueryRequest query, AuditExportScope scope) {
        LocalDateTime createdAt = LocalDateTime.now();
        String jobNo = "A2-AUD-EXP-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(createdAt);
        AuditExportCsv csv = auditExportCsv(query);
        if (csv.rowCount() == 0) {
            throw new BizException(422, "A2_EXPORT_EMPTY_RESULT");
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("A2_AUDIT_EXPORTED").resourceType("A2_AUDIT_EXPORT")
                .resourceId(jobNo).bizNo(jobNo).actorType("ADMIN").actorUsername(actor)
                .result("SUCCESS").riskLevel("MEDIUM")
                .detail(Map.of("jobNo", jobNo, "filter", request.filter(), "reason", request.reason().trim(),
                        "idempotencyKey", idempotencyKey, "rowCount", csv.rowCount()))
                .build());
        return new AuditExportResult(jobNo, createdAt, csv.body(), true, scope);
    }

    private String exportHash(String actor, AuditExportRequest request) {
        try {
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("actor", actor == null ? "unknown" : actor);
            envelope.put("operation", "EXPORT");
            envelope.put("payload", request);
            byte[] bytes = objectMapper.writeValueAsBytes(envelope);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new BizException(422, "A2_EXPORT_HASH_FAILED");
        }
    }

    private ResponseEntity<ApiResult<Map<String, Object>>> jsonFailure(OpsErrorCode errorCode) {
        return ResponseEntity.status(errorCode.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResult.fail(errorCode.httpStatus(), errorCode.name()));
    }

    private AuditLogQueryRequest auditExportQuery(AuditExportRequest request) {
        AuditLogQueryRequest query = new AuditLogQueryRequest();
        query.setLimit(MAX_EXPORT_ROWS);
        applyAuditFilter(query, request.filter());
        return accessPolicy.constrain(query);
    }

    private AuditExportCsv auditExportCsv(AuditLogQueryRequest query) {
        long total = auditLogService.count(query);
        if (total > MAX_EXPORT_ROWS) {
            throw new BizException(422, "A2_EXPORT_TOO_LARGE_REFINE_FILTER");
        }
        List<AuditLogRecord> rows = total == 0 ? List.of() : auditLogService.listForExport(query, MAX_EXPORT_ROWS);
        if (rows.size() != total) {
            throw new BizException(409, "A2_EXPORT_SNAPSHOT_CHANGED_RETRY");
        }
        StringBuilder csv = new StringBuilder(8192).append('\ufeff');
        appendCsvRow(csv, "时间", "操作者", "动作", "资源类型", "资源编号",
                "业务编号", "结果", "风险级别", "Schema 版本");
        for (AuditLogRecord row : rows) {
            appendCsvRow(csv, row.getCreatedAt(), maskAuditIdentifier(row.getActorUsername()),
                    row.getAction(), row.getResourceType(), maskAuditIdentifier(row.getResourceId()),
                    maskAuditIdentifier(row.getBizNo()), row.getResult(), row.getRiskLevel(),
                    row.getSchemaVersion() == null ? "legacy" : row.getSchemaVersion());
        }
        return new AuditExportCsv(csv.toString().getBytes(StandardCharsets.UTF_8), rows.size());
    }

    private void applyAuditFilter(AuditLogQueryRequest query, Map<String, Object> filter) {
        Object action = filter.get("action");
        if (action instanceof String value && StringUtils.hasText(value)) {
            query.setAction(value.trim());
        }
        Object resourceType = filter.get("resourceType");
        if (resourceType instanceof String value && StringUtils.hasText(value)) {
            query.setResourceType(value.trim());
        }
        Object result = filter.get("result");
        if (result instanceof String value && StringUtils.hasText(value)) {
            query.setResult(value.trim());
        }
        Object riskLevel = filter.get("riskLevel");
        if (riskLevel instanceof String value && StringUtils.hasText(value)) {
            query.setRiskLevel(value.trim());
        }
        setText(filter.get("domain"), query::setDomain);
        setText(filter.get("operator"), query::setOperator);
        setText(filter.get("object"), query::setObject);
        setTime(filter.get("startTime"), query::setStartTime);
        setTime(filter.get("endTime"), query::setEndTime);
    }

    private void setText(Object value, java.util.function.Consumer<String> setter) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            setter.accept(text.trim());
        }
    }

    private void setTime(Object value, java.util.function.Consumer<LocalDateTime> setter) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                setter.accept(LocalDateTime.parse(text.trim()));
            } catch (java.time.format.DateTimeParseException ex) {
                throw new IllegalArgumentException("A2_FILTER_TIME_INVALID");
            }
        }
    }

    private void appendCsvRow(StringBuilder csv, Object... cells) {
        for (int index = 0; index < cells.length; index++) {
            if (index > 0) {
                csv.append(',');
            }
            csv.append(csvCell(cells[index]));
        }
        csv.append("\r\n");
    }

    private String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        String firstVisible = text.stripLeading();
        boolean leadingControl = !text.isEmpty()
                && (text.charAt(0) == '\t' || text.charAt(0) == '\r' || text.charAt(0) == '\n');
        boolean formulaPrefix = !firstVisible.isEmpty() && "=+-@".indexOf(firstVisible.charAt(0)) >= 0;
        if (leadingControl || formulaPrefix) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private String maskAuditIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        int length = value.codePointCount(0, value.length());
        if (length <= 4) {
            return "***";
        }
        return value.substring(0, value.offsetByCodePoints(0, 2)) + "***"
                + value.substring(value.offsetByCodePoints(0, length - 2));
    }

    @PostMapping("/operations/{operationId}/approve")
    @PreAuthorize("hasAuthority('platform_a2_operation_approve')"
            + " && @a2AccessPolicy.canAccessOperation(#operationId)")
    public ApiResult<AuditCenterOverview.AuditOperationTicket> approveOperation(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String operationId,
            @RequestBody(required = false) AuditOperationDecisionRequest request) {
        return auditCenterService.approve(idempotencyKey, operationId, authenticated(request));
    }

    @PostMapping("/operations")
    @PreAuthorize("hasAnyAuthority('platform_a2_write','platform_a2_proposal_create')"
            + " || (#request != null && (@treasuryThresholdApproval.canPropose(#request.command())"
            + " || @a4RegistryApproval.canPropose(#request.command())"
            + " || @auditReplayBusinessPermissionGuard.canProposeH7(#request.command())"
            + " || @e2TaskPricingApproval.canPropose(#request.command())"
            + " || @h8RewardParamApproval.canPropose(#request.command())"
            + " || @h5GrowthApproval.canPropose(#request.command())"
            + " || @h3GrowthApproval.canPropose(#request.command())"
            + " || @h4GrowthApproval.canPropose(#request.command())"
            + " || @teamFundApproval.canPropose(#request.command())"
            + " || @g3MarketApproval.canPropose(#request.command())"
            + " || @learningCourseActivationApproval.canPropose(#request.command())"
            + " || @g4CatalogApproval.canPropose(#request.command())"
            + " || @g4DividendApproval.canPropose(#request.command())))")
    public ApiResult<AuditCenterOverview.AuditOperationTicket> createOperation(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AuditOperationProposalRequest request) {
        return auditCenterService.createProposal(idempotencyKey, authenticated(request));
    }

    @PostMapping("/operations/{operationId}/reject")
    @PreAuthorize("hasAuthority('platform_a2_operation_approve')"
            + " && @a2AccessPolicy.canAccessOperation(#operationId)")
    public ApiResult<AuditCenterOverview.AuditOperationTicket> rejectOperation(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String operationId,
            @RequestBody(required = false) AuditOperationDecisionRequest request) {
        return auditCenterService.reject(idempotencyKey, operationId, authenticated(request));
    }

    @PostMapping("/operations/{operationId}/withdraw")
    @PreAuthorize("hasAuthority('platform_a2_proposal_create')"
            + " && @a2AccessPolicy.canAccessOperation(#operationId)")
    public ApiResult<AuditCenterOverview.AuditOperationTicket> withdrawOperation(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String operationId,
            @RequestBody(required = false) AuditOperationWithdrawRequest request) {
        AuditOperationWithdrawRequest authenticated = request == null ? null : new AuditOperationWithdrawRequest(
                request.reason(), request.expectedStatus(), AdminActorResolver.resolve(request.operator()));
        return auditCenterService.withdraw(idempotencyKey, operationId, authenticated);
    }

    private AuditOperationDecisionRequest authenticated(AuditOperationDecisionRequest request) {
        if (request == null) {
            return null;
        }
        return new AuditOperationDecisionRequest(request.reason(), AdminActorResolver.resolve(request.operator()));
    }

    private AuditOperationProposalRequest authenticated(AuditOperationProposalRequest request) {
        if (request == null) {
            return null;
        }
        return new AuditOperationProposalRequest(
                request.action(), request.obj(), request.beforeValue(), request.afterValue(),
                AdminActorResolver.resolve(request.operator()), operatorRoleResolver.resolve(), request.type(),
                request.amplifies(), request.sos(), request.roleGate(), request.reason(), request.sourceDomain(),
                request.command(), request.target(), request.targets());
    }

    @PostMapping("/mechanism-params/{paramKey}")
    @PreAuthorize("hasAuthority('platform_a2_write')")
    public ApiResult<AuditCenterOverview.AuditMechanismParam> updateMechanismParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody(required = false) AuditMechanismParamUpdateRequest request) {
        AuditMechanismParamUpdateRequest authenticated = request == null ? null
                : new AuditMechanismParamUpdateRequest(
                        request.value(), request.reason(), AdminActorResolver.resolve(null));
        return auditCenterService.updateMechanismParam(idempotencyKey, paramKey, authenticated);
    }

    @GetMapping("/stats/summary")
    @PreAuthorize("@a2AccessPolicy.hasUnrestrictedRead()")
    public ApiResult<AuditStatsSummaryResponse> summary(AuditStatsQueryRequest request) {
        return ApiResult.ok(auditLogService.summary(request));
    }

    @GetMapping("/stats/actions")
    @PreAuthorize("@a2AccessPolicy.hasUnrestrictedRead()")
    public ApiResult<List<AuditStatsBucket>> actions(AuditStatsQueryRequest request) {
        return ApiResult.ok(auditLogService.topActions(request));
    }

    @GetMapping("/stats/services")
    @PreAuthorize("@a2AccessPolicy.hasUnrestrictedRead()")
    public ApiResult<List<AuditStatsBucket>> services(AuditStatsQueryRequest request) {
        return ApiResult.ok(auditLogService.topServices(request));
    }

    @GetMapping("/stats/users")
    @PreAuthorize("@a2AccessPolicy.hasUnrestrictedRead()")
    public ApiResult<List<AuditStatsBucket>> users(AuditStatsQueryRequest request) {
        return ApiResult.ok(auditLogService.topUsers(request));
    }

    public record AuditExportResult(String jobNo, LocalDateTime createdAt, byte[] body, boolean csv,
                                    AuditExportScope scope) {}

    public record AuditExportScope(List<String> domains, String actor) {
        static AuditExportScope from(AuditLogQueryRequest query) {
            List<String> allowed = query.getAllowedDomains();
            List<String> domains = query.getDomain() != null
                    && (allowed == null || allowed.contains(query.getDomain())) ? List.of(query.getDomain()) : allowed;
            return new AuditExportScope(domains, query.getOperatorExact());
        }

        boolean canRecover(AuditExportScope issued, String explicitDomain) {
            if (issued == null) {
                // Older receipts have no scope metadata. A requested domain still bounds the old result.
                return actor == null && (domains == null || explicitDomain != null && domains.contains(explicitDomain));
            }
            return (actor == null || actor.equals(issued.actor()))
                    && (domains == null || issued.domains() != null && domains.containsAll(issued.domains()));
        }
    }

    private record AuditExportCsv(byte[] body, int rowCount) {}
}
