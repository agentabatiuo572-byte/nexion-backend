package ffdd.opsconsole.bi.application;

import ffdd.opsconsole.bi.domain.BiReportCreateCommand;
import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.dto.RegulatoryReportRequest;
import ffdd.opsconsole.content.facade.RegulatoryDisclosureFacade;
import ffdd.opsconsole.content.facade.RegulatoryDisclosureSnapshot;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.facade.TreasuryFinanceAnalyticsFacade;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OpsRegulatoryReportService {
    private static final Set<String> TEMPLATE_CODES = Set.of(
            "KYC_COMPLIANCE", "PAYOUT_REPORT", "AML_REPORT", "JURISDICTION_SPECIAL");
    private static final Map<String, String> TEMPLATE_LABELS = Map.of(
            "KYC_COMPLIANCE", "KYC 合规报告",
            "PAYOUT_REPORT", "提现发放报告",
            "AML_REPORT", "反洗钱监测报告",
            "JURISDICTION_SPECIAL", "法域专项报告");

    private final BiReportRepository reportRepository;
    private final TreasuryLedgerRepository ledgerRepository;
    private final TreasuryFinanceAnalyticsFacade financeAnalyticsFacade;
    private final RegulatoryDisclosureFacade disclosureFacade;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;

    public ApiResult<Map<String, Object>> options() {
        List<Map<String, Object>> disclosures = disclosureFacade.currentOptions().stream()
                .filter(row -> row.chapterCount() == 7)
                .map(this::option)
                .toList();
        return ApiResult.ok(linked(
                "templates", TEMPLATE_CODES.stream().sorted().map(code -> linked(
                        "code", code, "label", TEMPLATE_LABELS.get(code))).toList(),
                "disclosures", disclosures,
                "maskingPolicy", "MASKED",
                "containsPii", false,
                "decryptedPiiBlocked", true,
                "sources", List.of(
                        "I5 disclosure lifecycle", "C4 KYC events", "L3 finance snapshot",
                        "L4 operations snapshot", "D4 canonical ledger", "A2 audit ledger",
                        "J4 admin.emergency_playbook_executed")));
    }

    @Transactional
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> create(String idempotencyKey, RegulatoryReportRequest request) {
        ApiResult<Map<String, Object>> guard = validate(idempotencyKey, request);
        if (guard != null) return guard;
        String templateCode = normalize(request.templateCode());
        RegulatoryDisclosureSnapshot disclosure = disclosureFacade
                .resolveCurrent(request.jurisdictionCode(), request.disclosureVersion())
                .orElse(null);
        if (disclosure == null) return ApiResult.fail(422, "I5_CURRENT_DISCLOSURE_NOT_FOUND");
        if (disclosure.chapterCount() != 7) return ApiResult.fail(422, "I5_DISCLOSURE_CHAPTERS_INCOMPLETE");
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                "L5_REGULATORY_REPORT_CREATE",
                idempotencyKey.trim(),
                requestHash(request),
                ApiResult.class,
                () -> createOnce(templateCode, disclosure, idempotencyKey.trim(), request));
    }

    private ApiResult<Map<String, Object>> createOnce(
            String templateCode,
            RegulatoryDisclosureSnapshot disclosure,
            String idempotencyKey,
            RegulatoryReportRequest request) {
        String reportId = "REG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        List<List<Object>> metrics = regulatoryMetrics(templateCode, disclosure);
        String period = request.period().trim();
        String scope = period + " · " + disclosure.jurisdictionName() + "(" + disclosure.jurisdictionCode()
                + ") · 披露 " + disclosure.disclosureVersion();
        String fields = "法域、披露版本、七章完整性、确认进度、拦截数、C4/L3/L4/D4 聚合指标、A2/J4 追溯";
        String note = TEMPLATE_LABELS.get(templateCode) + " · " + disclosure.jurisdictionCode()
                + "/" + disclosure.disclosureVersion() + " · 工单:" + request.ticket().trim();
        BiReportView created = reportRepository.createReport(new BiReportCreateCommand(
                reportId,
                TEMPLATE_LABELS.get(templateCode),
                "REGULATORY",
                period,
                "CSV",
                scope,
                fields,
                (long) metrics.size(),
                Boolean.FALSE,
                "MASKED",
                "READY",
                note));
        reportRepository.saveSnapshotCsv(reportId, regulatoryCsv(templateCode, disclosure, request, metrics));
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("admin.report_exported")
                .resourceType("BI_REPORT")
                .resourceId(reportId)
                .bizNo(reportId)
                .actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve(null))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(linked(
                        "exportType", "REGULATORY",
                        "templateCode", templateCode,
                        "period", period,
                        "scope", scope,
                        "fields", fields,
                        "rowCount", metrics.size(),
                        "containsPii", false,
                        "maskingPolicy", "MASKED",
                        "format", "CSV",
                        "jurisdictionCode", disclosure.jurisdictionCode(),
                        "jurisdictionName", disclosure.jurisdictionName(),
                        "disclosureVersion", disclosure.disclosureVersion(),
                        "disclosureContentHash", disclosure.contentHash(),
                        "chapterCount", disclosure.chapterCount(),
                        "recipient", request.recipient().trim(),
                        "ticket", request.ticket().trim(),
                        "reason", request.reason().trim(),
                        "idempotencyKey", idempotencyKey,
                        "dataMinimization", "AGGREGATE_ONLY_NO_USER_ROWS"))
                .build());
        return ApiResult.ok(linked(
                "created", created,
                "taskStatus", "READY",
                "disclosure", option(disclosure),
                "downloadPath", "/api/admin/bi/exports/" + reportId + "/download-token"));
    }

    private ApiResult<Map<String, Object>> validate(String idempotencyKey, RegulatoryReportRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) return ApiResult.fail(400, "IDEMPOTENCY_KEY_REQUIRED");
        if (request == null) return ApiResult.fail(400, "REGULATORY_REPORT_REQUEST_REQUIRED");
        if (!TEMPLATE_CODES.contains(normalize(request.templateCode()))) return ApiResult.fail(422, "REGULATORY_TEMPLATE_INVALID");
        if (!bounded(request.period(), 1, 64)) return ApiResult.fail(422, "REGULATORY_PERIOD_INVALID");
        if (!bounded(request.jurisdictionCode(), 2, 16)) return ApiResult.fail(422, "REGULATORY_JURISDICTION_INVALID");
        if (!bounded(request.disclosureVersion(), 1, 32)) return ApiResult.fail(422, "REGULATORY_DISCLOSURE_VERSION_INVALID");
        if (!bounded(request.recipient(), 2, 100)) return ApiResult.fail(422, "REGULATORY_RECIPIENT_INVALID");
        if (!bounded(request.ticket(), 3, 100)) return ApiResult.fail(422, "REGULATORY_TICKET_INVALID");
        if (!bounded(request.reason(), 8, 200)) return ApiResult.fail(422, "REGULATORY_REASON_LENGTH_INVALID");
        return null;
    }

    private List<List<Object>> regulatoryMetrics(String templateCode, RegulatoryDisclosureSnapshot disclosure) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("I5", "disclosure_chapters", disclosure.chapterCount(), "I5 current published disclosure"));
        rows.add(List.of("I5", "affected_users", disclosure.affectedUsers(), "I5 disclosure acknowledgement ledger"));
        rows.add(List.of("I5", "acknowledgement_progress_pct", disclosure.acknowledgementProgress(), "I5 disclosure acknowledgement ledger"));
        rows.add(List.of("I5", "blocked_users", disclosure.blockedUsers(), "I5 disclosure gate"));
        rows.add(List.of("A2", "prior_export_audit_rows",
                auditLogService.countByActionAndResourceType("ADMIN.REPORT_EXPORTED", "BI_REPORT"),
                "A2 append-only audit ledger"));
        rows.add(List.of("J4", "emergency_playbook_executed",
                reportRepository.countRegisteredServerEvent("admin.emergency_playbook_executed"),
                "A4 registered server-authoritative J4 event"));
        if ("KYC_COMPLIANCE".equals(templateCode)) {
            appendKycEvidence(rows);
        } else if ("PAYOUT_REPORT".equals(templateCode)) {
            appendFinanceEvidence(rows);
            rows.add(List.of("D4", "ledger_rows", ledgerRepository.countLedgerBills(null, null, null), "D4 canonical ledger"));
        } else if ("AML_REPORT".equals(templateCode)) {
            appendKycEvidence(rows);
            appendFinanceEvidence(rows);
            rows.add(List.of("D4", "ledger_rows", ledgerRepository.countLedgerBills(null, null, null), "D4 canonical ledger"));
        } else if ("JURISDICTION_SPECIAL".equals(templateCode)) {
            appendKycEvidence(rows);
            appendOperationsEvidence(rows);
        }
        return rows;
    }

    private Object stageCount(String key) {
        Map<String, Object> dashboard = reportRepository.dashboard("L2");
        Object stages = dashboard == null ? null : dashboard.get("stages");
        if (!(stages instanceof List<?> rows)) return "UNAVAILABLE";
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row) || !key.equals(String.valueOf(row.get("key")))) continue;
            Object value = row.get("count");
            if (value instanceof Number number) return number.longValue();
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return "UNAVAILABLE";
            }
        }
        return "UNAVAILABLE";
    }

    private void appendKycEvidence(List<List<Object>> rows) {
        appendKycMetric(rows, "kyc_submitted_users", stageCount("kycSubmitted"),
                "A4 registered server-authoritative kyc.express_started actors");
        appendKycMetric(rows, "kyc_verified_users", stageCount("kycApproved"),
                "A4 registered server-authoritative kyc.express_verified actors");
    }

    private void appendKycMetric(List<List<Object>> rows, String key, Object value, String evidence) {
        rows.add(List.of(
                "C4",
                key,
                value,
                value instanceof Number ? evidence : evidence + " (source field unavailable; no value inferred)"));
    }

    private void appendFinanceEvidence(List<List<Object>> rows) {
        Map<String, Object> finance = objectMap(financeAnalyticsFacade.currentFinanceSnapshot());
        Map<String, Object> snapshot = objectMap(finance.get("snapshot"));
        appendNumeric(rows, "L3", snapshot, "reserveUsd", "reserve_usd", "L3 canonical treasury snapshot");
        appendNumeric(rows, "L3", snapshot, "liabilitiesUsd", "liabilities_usd", "L3 canonical treasury snapshot");
        appendNumeric(rows, "L3", snapshot, "coverageRatio", "coverage_ratio", "L3 canonical treasury snapshot");
        appendNumeric(rows, "L3", snapshot, "netFlow24hUsd", "net_flow_24h_usd", "L3 canonical treasury snapshot");
        appendNumeric(rows, "L3", snapshot, "queueBacklogCount", "withdraw_queue_count", "L3 canonical treasury snapshot");
        appendNumeric(rows, "L3", snapshot, "queueBacklogUsd", "withdraw_queue_usd", "L3 canonical treasury snapshot");
    }

    private void appendOperationsEvidence(List<List<Object>> rows) {
        Map<String, Object> liveFacts = objectMap(reportRepository
                .operationsDashboard("month", "ALL", null, null)
                .get("liveFacts"));
        appendNumeric(rows, "L4", liveFacts, "activeUserDevices", "active_user_devices", "L4 server-authoritative operations snapshot");
        appendNumeric(rows, "L4", liveFacts, "completedComputeTasks", "completed_compute_tasks", "L4 server-authoritative operations snapshot");
        appendNumeric(rows, "L4", liveFacts, "teamRelationships", "team_relationships", "L4 server-authoritative operations snapshot");
        appendNumeric(rows, "L4", liveFacts, "commissionEvents", "commission_events", "L4 server-authoritative operations snapshot");
        appendNumeric(rows, "L4", liveFacts, "tradeinApplications", "tradein_applications", "L4 server-authoritative operations snapshot");
    }

    private void appendNumeric(
            List<List<Object>> rows,
            String section,
            Map<String, Object> source,
            String sourceKey,
            String reportKey,
            String evidence) {
        Object value = source.get(sourceKey);
        rows.add(List.of(
                section,
                reportKey,
                value instanceof Number ? value : "UNAVAILABLE",
                value instanceof Number ? evidence : evidence + " (source field unavailable; no value inferred)"));
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String regulatoryCsv(
            String templateCode,
            RegulatoryDisclosureSnapshot disclosure,
            RegulatoryReportRequest request,
            List<List<Object>> metrics) {
        StringBuilder csv = new StringBuilder();
        csv.append(csvRow(List.of("section", "key", "value", "source")));
        csv.append(csvRow(List.of("context", "template", TEMPLATE_LABELS.get(templateCode), "L5 canonical template")));
        csv.append(csvRow(List.of("context", "period", request.period().trim(), "operator request")));
        csv.append(csvRow(List.of("context", "jurisdiction", disclosure.jurisdictionCode() + " " + disclosure.jurisdictionName(), "I5 current mapping")));
        csv.append(csvRow(List.of("context", "disclosure_version", disclosure.disclosureVersion(), "I5 current mapping")));
        csv.append(csvRow(List.of("context", "disclosure_content_hash", disclosure.contentHash(), "I5 immutable published snapshot")));
        csv.append(csvRow(List.of("context", "data_minimization", "aggregate_only_no_user_rows", "L5 export policy")));
        metrics.forEach(row -> csv.append(csvRow(row)));
        return csv.toString();
    }

    private Map<String, Object> option(RegulatoryDisclosureSnapshot row) {
        return linked(
                "jurisdictionCode", row.jurisdictionCode(),
                "jurisdictionName", row.jurisdictionName(),
                "countryCodes", row.countryCodes(),
                "disclosureVersion", row.disclosureVersion(),
                "disclosureStatus", row.disclosureStatus(),
                "chapterCount", row.chapterCount(),
                "publishedAt", row.publishedAt());
    }

    private String csvRow(List<?> values) {
        return values.stream().map(this::csvCell).reduce((a, b) -> a + "," + b).orElse("") + "\r\n";
    }

    private String csvCell(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw);
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private boolean bounded(String value, int min, int max) {
        if (!StringUtils.hasText(value)) return false;
        int length = value.trim().length();
        return length >= min && length <= max;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String requestHash(RegulatoryReportRequest request) {
        return sha256(String.join("\u001f",
                normalize(request.templateCode()), request.period().trim(), normalize(request.jurisdictionCode()),
                request.disclosureVersion().trim(), request.recipient().trim(), request.ticket().trim(), request.reason().trim()));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Map<String, Object> linked(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        return result;
    }
}
