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
import ffdd.opsconsole.platform.application.A2AccessPolicy;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import ffdd.opsconsole.platform.dto.AuditCenterOverview;
import ffdd.opsconsole.platform.dto.AuditExportRequest;
import ffdd.opsconsole.platform.dto.AuditMechanismParamUpdateRequest;
import ffdd.opsconsole.platform.dto.AuditOperationDecisionRequest;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

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

    @GetMapping("/logs/trace/{traceId}")
    public ApiResult<List<AuditLogRecord>> byTrace(@PathVariable String traceId) {
        AuditLogQueryRequest request = new AuditLogQueryRequest();
        request.setTraceId(traceId);
        request.setLimit(200);
        return ApiResult.ok(auditLogService.list(accessPolicy.constrain(request)));
    }

    @PostMapping("/exports")
    @PreAuthorize("hasAuthority('platform_a2_export')")
    @Transactional
    public ResponseEntity<?> export(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AuditExportRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return jsonFailure(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        int reasonLength = request == null || request.reason() == null ? 0
                : request.reason().trim().codePointCount(0, request.reason().trim().length());
        if (request == null || !StringUtils.hasText(request.reason())
                || reasonLength < Math.max(8, auditCenterService.currentReasonMinChars())) {
            return jsonFailure(OpsErrorCode.REASON_REQUIRED);
        }
        if (reasonLength > 200) {
            return jsonFailure(OpsErrorCode.VALIDATION_FAILED);
        }

        String normalizedKey = idempotencyKey.trim();
        String actor = AdminActorResolver.resolve(null);
        AuditExportResult export;
        try {
            export = idempotencyService.execute("A2_COMMAND", normalizedKey,
                    exportHash(actor, request), AuditExportResult.class,
                    () -> createExport(normalizedKey, actor, request));
        } catch (BizException ex) {
            return ResponseEntity.status(ex.getCode()).contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResult.fail(ex.getCode(), ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResult.fail(422, ex.getMessage()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.ms-excel;charset=UTF-8"))
                .contentLength(export.body().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.jobNo() + ".xls", StandardCharsets.UTF_8).build().toString())
                .body(export.body());
    }

    private AuditExportResult createExport(String idempotencyKey, String actor, AuditExportRequest request) {
        LocalDateTime createdAt = LocalDateTime.now();
        String jobNo = "A2-AUD-EXP-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(createdAt);
        ExportWorkbook workbook = auditExportWorkbook(jobNo, idempotencyKey, createdAt, request);
        if (workbook.rowCount() == 0) {
            throw new BizException(422, "A2_EXPORT_EMPTY_RESULT");
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("A2_AUDIT_EXPORTED").resourceType("A2_AUDIT_EXPORT")
                .resourceId(jobNo).bizNo(jobNo).actorType("ADMIN").actorUsername(actor)
                .result("SUCCESS").riskLevel("MEDIUM")
                .detail(Map.of("jobNo", jobNo, "filter", request.filter(), "reason", request.reason().trim(),
                        "idempotencyKey", idempotencyKey, "rowCount", workbook.rowCount()))
                .build());
        return new AuditExportResult(jobNo, createdAt, workbook.body());
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

    private ExportWorkbook auditExportWorkbook(
            String jobNo,
            String idempotencyKey,
            LocalDateTime createdAt,
            AuditExportRequest request) {
        AuditLogQueryRequest query = new AuditLogQueryRequest();
        query.setLimit(MAX_EXPORT_ROWS);
        if (request.filter() != null) {
            applyAuditFilter(query, request.filter());
        }
        query = accessPolicy.constrain(query);
        long total = auditLogService.count(query);
        if (total > MAX_EXPORT_ROWS) {
            throw new BizException(422, "A2_EXPORT_TOO_LARGE_REFINE_FILTER");
        }
        List<AuditLogRecord> rows = total == 0 ? List.of() : auditLogService.listForExport(query, MAX_EXPORT_ROWS);
        if (rows.size() != total) {
            throw new BizException(409, "A2_EXPORT_SNAPSHOT_CHANGED_RETRY");
        }
        StringBuilder html = new StringBuilder(8192);
        html.append('\ufeff')
                .append("<html><head><meta charset=\"UTF-8\"></head><body>")
                .append("<table border=\"1\">")
                .append("<tr><th colspan=\"8\">A2 审计日志导出</th></tr>")
                .append("<tr><td>导出单号</td><td colspan=\"7\">").append(escape(jobNo)).append("</td></tr>")
                .append("<tr><td>导出时间</td><td colspan=\"7\">").append(escape(createdAt.toString())).append("</td></tr>")
                .append("<tr><td>幂等键</td><td colspan=\"7\">").append(escape(idempotencyKey)).append("</td></tr>")
                .append("<tr><td>导出原因</td><td colspan=\"7\">").append(escape(request.reason().trim())).append("</td></tr>")
                .append("<tr><th>时间</th><th>操作者</th><th>动作</th><th>资源类型</th><th>资源编号</th>")
                .append("<th>业务编号</th><th>结果</th><th>风险级别</th></tr>");
        for (AuditLogRecord row : rows == null ? List.<AuditLogRecord>of() : rows) {
            html.append("<tr>")
                    .append("<td>").append(escape(row.getCreatedAt() == null ? "" : row.getCreatedAt().toString())).append("</td>")
                    .append("<td>").append(escape(row.getActorUsername())).append("</td>")
                    .append("<td>").append(escape(row.getAction())).append("</td>")
                    .append("<td>").append(escape(row.getResourceType())).append("</td>")
                    .append("<td>").append(escape(row.getResourceId())).append("</td>")
                    .append("<td>").append(escape(row.getBizNo())).append("</td>")
                    .append("<td>").append(escape(row.getResult())).append("</td>")
                    .append("<td>").append(escape(row.getRiskLevel())).append("</td>")
                    .append("</tr>");
        }
        html.append("</table></body></html>");
        return new ExportWorkbook(html.toString().getBytes(StandardCharsets.UTF_8), rows == null ? 0 : rows.size());
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

    private String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        String firstVisible = text.stripLeading();
        boolean leadingControl = !text.isEmpty()
                && (text.charAt(0) == '\t' || text.charAt(0) == '\r' || text.charAt(0) == '\n');
        boolean formulaPrefix = !firstVisible.isEmpty() && "=+-@".indexOf(firstVisible.charAt(0)) >= 0;
        if (leadingControl || formulaPrefix) {
            text = "'" + text;
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    @PostMapping("/operations/{operationId}/approve")
    @PreAuthorize("hasAuthority('platform_a2_operation_approve')")
    public ApiResult<AuditCenterOverview.AuditOperationTicket> approveOperation(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String operationId,
            @RequestBody(required = false) AuditOperationDecisionRequest request) {
        return auditCenterService.approve(idempotencyKey, operationId, authenticated(request));
    }

    @PostMapping("/operations")
    @PreAuthorize("hasAnyAuthority('platform_a2_write','platform_a2_proposal_create')")
    public ApiResult<AuditCenterOverview.AuditOperationTicket> createOperation(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AuditOperationProposalRequest request) {
        return auditCenterService.createProposal(idempotencyKey, authenticated(request));
    }

    @PostMapping("/operations/{operationId}/reject")
    @PreAuthorize("hasAuthority('platform_a2_operation_approve')")
    public ApiResult<AuditCenterOverview.AuditOperationTicket> rejectOperation(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String operationId,
            @RequestBody(required = false) AuditOperationDecisionRequest request) {
        return auditCenterService.reject(idempotencyKey, operationId, authenticated(request));
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

    public record AuditExportResult(String jobNo, LocalDateTime createdAt, byte[] body) {}
    private record ExportWorkbook(byte[] body, int rowCount) {}
}
