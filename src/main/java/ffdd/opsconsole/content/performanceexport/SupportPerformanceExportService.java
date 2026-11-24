package ffdd.opsconsole.content.performanceexport;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.content.performanceexport.PerformanceExportFactSource.Coverage;
import ffdd.opsconsole.content.performanceexport.PerformanceExportFactSource.Scope;
import ffdd.opsconsole.content.performanceexport.PerformanceExportFactSource.Status;
import ffdd.opsconsole.content.performanceexport.PerformanceExportFactSource.Table;
import ffdd.opsconsole.content.performanceexport.PerformanceExportFactSource.Type;
import ffdd.opsconsole.content.performanceexport.PerformanceExportFactSource.Window;
import ffdd.opsconsole.content.performanceexport.mapper.SupportPerformanceExportMapper;
import ffdd.opsconsole.content.performanceexport.mapper.SupportPerformanceExportMapper.TaskRow;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.config.StorageTime;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * B80-BE-06 performance CSV exports. Tasks, decisions, frozen content and vouchers follow the
 * existing L5 practice; facts and aggregates arrive through {@link PerformanceExportFactSource}.
 */
@Service
@RequiredArgsConstructor
public class SupportPerformanceExportService {
    private static final Logger LOG = LoggerFactory.getLogger(SupportPerformanceExportService.class);
    static final String SUMMARY_PERMISSION = "service_performance_export_summary";
    static final String DETAIL_PERMISSION = "service_performance_export_detail";
    static final String APPROVE_PERMISSION = "service_performance_export_approve";
    static final int SUMMARY_LIMIT = 5000;
    static final int CUSTOMER_DETAIL_LIMIT = 5000;
    static final int TRANSACTION_DETAIL_LIMIT = 100000;
    static final int MIN_REASON_CHARS = 8;
    static final int MAX_REASON_CHARS = 200;
    private static final int TOKEN_HOURS = 24;
    private static final int FILE_RETENTION_DAYS = 7;

    private final SupportPerformanceExportMapper mapper;
    private final PerformanceExportFactSource facts;
    private final SupportOwnershipService ownership;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService auditLog;
    private final ObjectMapper json;
    private final ffdd.opsconsole.content.mapper.SupportAnalyticsMapper analyticsMapper;
    private final ffdd.opsconsole.shared.security.AdminPermissionCache permissionCache;
    private final ffdd.opsconsole.shared.security.SuperAdminAuthorization superAdmins;
    private final ffdd.opsconsole.auth.mapper.AdminMapper adminMapper;

    public Map<String, Object> options() {
        Long actor = actorId();
        if (!readCode() || !(owns(SUMMARY_PERMISSION) || owns(DETAIL_PERMISSION)) || !outCapable(actor)) {
            throw new BizException(403, "PERMISSION_DENIED");
        }
        List<Map<String, Object>> scopes = scopes(actor);
        List<Map<String, Object>> months = new ArrayList<>();
        for (String month : facts.months()) {
            months.add(row("month", month, "availability", "UNKNOWN", "reasonCodes", List.of("HISTORY_INCOMPLETE")));
        }
        return row("months", months, "scopes", scopes,
                "limits", Map.of("SUMMARY", SUMMARY_LIMIT, "CUSTOMER_DETAIL", CUSTOMER_DETAIL_LIMIT,
                        "TRANSACTION_DETAIL", TRANSACTION_DETAIL_LIMIT),
                "minReasonChars", MIN_REASON_CHARS, "maxReasonChars", MAX_REASON_CHARS);
    }

    public Map<String, Object> preview(Map<String, Object> request) {
        Long actor = actorId();
        var plan = plan(actor, request, true);
        return row("previewVersion", plan.previewVersion(), "window", plan.window() == null ? null : windowView(plan.window()),
                "scope", Map.of("kind", plan.scope().kind(), "groupIds", plan.scope().groupIds(), "names", plan.scope().names()),
                "items", plan.items());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(String idempotencyKey, Map<String, Object> request) {
        Long actor = actorId();
        requireKey(idempotencyKey);
        String reason = reason(request == null ? null : request.get("reason"));
        String requestedVersion = request == null ? null : text(request.get("previewVersion"));
        String hash = sha256(String.valueOf(request == null ? "" : request.get("month")) + "|"
                + String.valueOf(request == null ? "" : request.get("scope")) + "|"
                + String.valueOf(request == null ? "" : request.get("types")) + "|" + reason + "|" + requestedVersion);
        Map<?, ?> created = idempotency.executeRetained("M_PERFORMANCE_EXPORT:" + actor, idempotencyKey.trim(), hash,
                Map.class, () -> createTasks(actor, idempotencyKey.trim(), request, reason, requestedVersion));
        return projectBatch(actor, String.valueOf(created.get("requestId")));
    }

    private Map<String, Object> createTasks(Long actor, String idempotencyKey, Map<String, Object> request,
                                            String reason, String requestedVersion) {
        var plan = plan(actor, request, false);
        if (requestedVersion == null || !requestedVersion.equals(plan.previewVersion())) {
            throw new BizException(409, "EXPORT_PREVIEW_CHANGED");
        }
        for (var item : plan.items()) {
            String status = text(item.get("status"));
            if ("LIMIT_EXCEEDED".equals(status)) throw new BizException(422, "EXPORT_ROW_LIMIT");
            if ("EMPTY".equals(status)) throw new BizException(422, "EXPORT_EMPTY");
            Object reasons = item.get("reasonCodes");
            boolean denied = reasons instanceof List<?> codes
                    && codes.stream().anyMatch(code -> "PERMISSION_DENIED".equals(String.valueOf(code)));
            if ("UNAVAILABLE".equals(status) && denied) {
                throw new BizException(403, "PERMISSION_DENIED");
            }
            if (!"AVAILABLE".equals(status)) throw new BizException(503, "EXPORT_SOURCE_UNAVAILABLE");
        }
        mapper.insertRequest(idempotencyKey, actor);
        String requestId = String.valueOf(mapper.lastInsertId());
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (var item : plan.tables()) {
            boolean summary = item.type() == Type.SUMMARY;
            String status = summary ? "PENDING_GENERATION" : "PENDING_APPROVAL";
            mapper.insertTask(requestId, actor, item.type().name(), write(plan.scope()), write(plan.window()),
                    plan.previewVersion(), reason, status, item.table().rows().size(),
                    item.table().restrictedHistoryRows(), write(item.table().columns()),
                    write(coverageMap(item.table().coverage())), write(item.table().rows()),
                    write(item.table().disclosedCustomerIds()));
            TaskRow task = mapper.findTaskByRequestAndType(requestId, item.type().name());
            if (summary) writeFrozenFile(task, item.table());
            audit(summary ? "M_PERFORMANCE_EXPORT_SUMMARY_SUBMITTED" : "M_PERFORMANCE_EXPORT_DETAIL_SUBMITTED",
                    task, actor, reason, row("rows", item.table().rows().size(), "type", item.type().name()));
            tasks.add(taskView(mapper.findTask(task.id()), actor));
        }
        return row("requestId", requestId, "items", tasks);
    }

    public Map<String, Object> list(String view, String requestKey, int pageNum, int pageSize) {
        Long actor = actorId();
        String normalized = view == null ? "MINE" : view.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("MINE", "APPROVALS").contains(normalized)) throw new BizException(422, "EXPORT_REQUEST_INVALID");
        int page = Math.max(1, pageNum);
        int size = Math.max(1, Math.min(pageSize, 100));
        int offset = (page - 1) * size;
        List<TaskRow> rows;
        long total;
        boolean allScope = platformSuperAdmin();
        List<String> groups = groupsOf(actor).stream().map(String::valueOf).toList();
        if ("APPROVALS".equals(normalized)) {
            if (!readCode() || !owns(APPROVE_PERMISSION) || !outCapable(actor)) {
                throw new BizException(403, "PERMISSION_DENIED");
            }
            rows = mapper.listApprovals(allScope, groups, offset, size);
            total = mapper.countApprovals(allScope, groups);
        } else {
            if (!readCode() || !(owns(SUMMARY_PERMISSION) || owns(DETAIL_PERMISSION))) {
                throw new BizException(403, "PERMISSION_DENIED");
            }
            List<String> types = laneTypes(actor);
            List<String> requestIds = null;
            String key = requestKey == null || requestKey.isBlank() ? null : requestKey.trim();
            if (key != null) {
                requestIds = mapper.requestIdsForKey(actor, key, key.chars().allMatch(Character::isDigit))
                        .stream().map(String::valueOf).toList();
                if (requestIds.isEmpty()) return row("records", List.of(), "total", 0, "pageNum", page, "pageSize", size);
            }
            rows = mapper.listMine(actor, types, requestIds, offset, size);
            total = mapper.countMine(actor, types, requestIds);
        }
        return row("records", rows.stream()
                        .map(task -> scopeStillAllowed(actor, task) ? taskView(task, actor) : minimalView(task, actor))
                        .toList(),
                "total", total, "pageNum", page, "pageSize", size);
    }

    public Map<String, Object> detail(Long taskId) {
        return detailView(actorId(), required(taskId));
    }

    public Map<String, Object> decide(Long taskId, String idempotencyKey, Map<String, Object> request) {
        Long actor = actorId();
        requireKey(idempotencyKey);
        String decision = (request == null ? "" : text(request.get("decision"))).toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVE", "REJECT").contains(decision)) throw new BizException(422, "EXPORT_REQUEST_INVALID");
        String reason = reason(request == null ? null : request.get("reason"));
        int expectedVersion = integer(request == null ? null : request.get("expectedVersion"));
        String hash = sha256(taskId + "|" + decision + "|" + expectedVersion + "|" + reason);
        Map<?, ?> decided = idempotency.executeRetained("M_PERFORMANCE_EXPORT_DECISION:" + actor, idempotencyKey.trim(),
                hash, Map.class, () -> applyDecision(taskId, actor, decision, reason, expectedVersion));
        Long decidedId = Long.valueOf(String.valueOf(decided.get("taskId")));
        if ("APPROVE".equals(decision)) generateApproved(decidedId, actor);
        return commandView(actor, mapper.findTask(decidedId));
    }

    private Map<String, Object> applyDecision(Long taskId, Long actor, String decision, String reason,
                                              int expectedVersion) {
        if (!readCode() || !owns(APPROVE_PERMISSION) || !outCapable(actor)) {
            throw new BizException(403, "PERMISSION_DENIED");
        }
        TaskRow task = required(taskId);
        if (task.requesterId().equals(actor)) throw new BizException(403, "EXPORT_SELF_APPROVAL_FORBIDDEN");
        if (!"PENDING_APPROVAL".equals(task.status())) throw new BizException(409, "EXPORT_INVALID_STATE");
        if (!reviewerScopeOk(actor, task)) throw new BizException(409, "EXPORT_SCOPE_CHANGED");
        if ("APPROVE".equals(decision)) {
            if (!applicantStillEligible(task)) throw new BizException(409, "EXPORT_APPLICANT_INELIGIBLE");
            if (!applicantScopeOk(task)) throw new BizException(409, "EXPORT_SCOPE_CHANGED");
        }
        int updated = mapper.decide(taskId, expectedVersion,
                "APPROVE".equals(decision) ? "APPROVED" : "REJECTED", actor, reason,
                "APPROVE".equals(decision) ? LocalDateTime.now() : null);
        if (updated != 1) throw new BizException(409, "EXPORT_TASK_VERSION_CHANGED");
        TaskRow decided = mapper.findTask(taskId);
        audit("APPROVE".equals(decision) ? "M_PERFORMANCE_EXPORT_APPROVED" : "M_PERFORMANCE_EXPORT_REJECTED",
                decided, actor, reason, row("requester", decided.requesterId()));
        return row("taskId", String.valueOf(decided.id()));
    }

    public Map<String, Object> withdraw(Long taskId, String idempotencyKey, Map<String, Object> request) {
        Long actor = actorId();
        requireKey(idempotencyKey);
        String reason = reason(request == null ? null : request.get("reason"));
        int expectedVersion = integer(request == null ? null : request.get("expectedVersion"));
        String hash = sha256(taskId + "|WITHDRAW|" + expectedVersion + "|" + reason);
        Map<?, ?> withdrawn = idempotency.executeRetained("M_PERFORMANCE_EXPORT_WITHDRAW:" + actor,
                idempotencyKey.trim(), hash, Map.class, () -> applyWithdraw(taskId, actor, reason, expectedVersion));
        return commandView(actor, mapper.findTask(Long.valueOf(String.valueOf(withdrawn.get("taskId")))));
    }

    private Map<String, Object> applyWithdraw(Long taskId, Long actor, String reason, int expectedVersion) {
        if (!readCode() || !owns(DETAIL_PERMISSION)) throw new BizException(403, "PERMISSION_DENIED");
        if (mapper.withdraw(taskId, actor, expectedVersion) != 1) {
            TaskRow task = required(taskId);
            if (!task.requesterId().equals(actor)) throw new BizException(404, "EXPORT_TASK_NOT_FOUND");
            throw new BizException(409, "EXPORT_TASK_VERSION_CHANGED");
        }
        TaskRow task = mapper.findTask(taskId);
        audit("M_PERFORMANCE_EXPORT_WITHDRAWN", task, actor, reason, row());
        return row("taskId", String.valueOf(task.id()));
    }

    public Map<String, Object> downloadToken(Long taskId) {
        Long actor = actorId();
        TaskRow task = required(taskId);
        if (!task.requesterId().equals(actor) || !readCode() || !ownsType(task.taskType(), actor)
                || !employmentAllows(actor, task) || !scopeStillAllowed(actor, task)
                || !disclosedCustomersAllowed(task)) {
            throw new BizException(403, "PERMISSION_DENIED");
        }
        if (!"READY".equals(task.status())) throw new BizException(409, "EXPORT_NOT_READY");
        LocalDateTime created = mapper.fileCreatedAt(taskId);
        if (created != null && created.plusDays(FILE_RETENTION_DAYS).isBefore(LocalDateTime.now())) {
            throw new BizException(410, "EXPORT_FILE_EXPIRED");
        }
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(TOKEN_HOURS);
        mapper.insertToken(sha256(token), taskId, actor, expiresAt);
        audit("M_PERFORMANCE_EXPORT_TOKEN_ISSUED", task, actor, "download voucher",
                row("expiresAt", instant(expiresAt)));
        return row("token", token, "expiresAt", instant(expiresAt));
    }

    public FilePayload file(Long taskId, String token) {
        Long actor = actorId();
        TaskRow task = required(taskId);
        if (!StringUtils.hasText(token)) throw new BizException(401, "EXPORT_TOKEN_REQUIRED");
        if (!task.requesterId().equals(actor) || !readCode() || !ownsType(task.taskType(), actor)) {
            throw new BizException(403, "PERMISSION_DENIED");
        }
        String hash = sha256(token.trim());
        if (mapper.tokenExists(hash, taskId, actor) == 0) throw new BizException(403, "EXPORT_TOKEN_INVALID");
        if (mapper.tokenExpired(hash, LocalDateTime.now()) > 0) throw new BizException(410, "EXPORT_TOKEN_EXPIRED");
        if (!"READY".equals(task.status())) throw new BizException(409, "EXPORT_NOT_READY");
        if (!employmentAllows(actor, task) || !scopeStillAllowed(actor, task) || !disclosedCustomersAllowed(task)) {
            throw new BizException(409, "EXPORT_SCOPE_CHANGED");
        }
        LocalDateTime created = mapper.fileCreatedAt(taskId);
        if (created != null && created.plusDays(FILE_RETENTION_DAYS).isBefore(LocalDateTime.now())) {
            throw new BizException(410, "EXPORT_FILE_EXPIRED");
        }
        String csv = mapper.fileCsv(taskId);
        if (csv == null) throw new BizException(409, "EXPORT_NOT_READY");
        if (mapper.useToken(hash, LocalDateTime.now()) != 1) throw new BizException(410, "EXPORT_TOKEN_EXPIRED");
        mapper.markDelivered(taskId, LocalDateTime.now());
        audit("M_PERFORMANCE_EXPORT_DOWNLOADED", task, actor, "download", row());
        return new FilePayload(task.fileName(), csv.getBytes(StandardCharsets.UTF_8));
    }

    /** Runs after the approval transaction committed; a failure keeps APPROVED and records FAILED. */
    private void generateApproved(Long taskId, Long actor) {
        TaskRow task = mapper.findTask(taskId);
        if (task == null || !"APPROVED".equals(task.status())) return;
        String outward = null;
        try {
            if (!applicantStillEligible(task)) {
                outward = "EXPORT_APPLICANT_INELIGIBLE";
            } else if (!applicantScopeOk(task)) {
                outward = "EXPORT_SCOPE_CHANGED";
            } else {
                writeFrozenFile(task, frozenTable(task));
                return;
            }
        } catch (RuntimeException ex) {
            LOG.warn("Performance export generation failed for task {}", taskId, ex);
            outward = "EXPORT_GENERATION_FAILED";
        }
        if (mapper.markFailed(taskId, outward) == 1) {
            audit("M_PERFORMANCE_EXPORT_GENERATION_FAILED", mapper.findTask(taskId), actor, outward, row());
        }
    }

    public record FilePayload(String fileName, byte[] content) { }

    // ---------- planning ----------

    private record TableItem(Type type, Table table) { }

    private record Plan(Window window, Scope scope, String previewVersion, List<Map<String, Object>> items,
                        List<TableItem> tables) { }

    private Plan plan(Long actor, Map<String, Object> request, boolean previewOnly) {
        if (!readCode() || !(owns(SUMMARY_PERMISSION) || owns(DETAIL_PERMISSION)) || !outCapable(actor)) {
            throw new BizException(403, "PERMISSION_DENIED");
        }
        String month = text(request == null ? null : request.get("month"));
        Scope scope = resolveScope(actor, request == null ? null : request.get("scope"));
        List<Type> types = types(request == null ? null : request.get("types"));
        List<Type> permitted = allowedTypes(actor);
        if (types.stream().noneMatch(permitted::contains)) throw new BizException(403, "PERMISSION_DENIED");
        Window window = month == null ? null : facts.window(month, scope);
        List<Map<String, Object>> items = new ArrayList<>();
        List<TableItem> tables = new ArrayList<>();
        StringBuilder seed = new StringBuilder(month + "|" + scope.kind() + "|" + String.join(",", scope.groupIds()));
        if (window != null) seed.append("|").append(window.sourceVersion()).append("|").append(window.asOf());
        for (Type type : types) {
            boolean allowed = permitted.contains(type);
            Table table = !allowed || window == null ? null : facts.read(type, window, scope);
            Map<String, Object> item = item(type, table, allowed, window);
            items.add(item);
            seed.append("|").append(type).append(":").append(item.get("status")).append(":").append(item.get("availableRows"));
            if (table != null) tables.add(new TableItem(type, table));
        }
        return new Plan(window, scope, sha256(seed.toString()), items, tables);
    }

    private Map<String, Object> item(Type type, Table table, boolean allowed, Window window) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type.name());
        item.put("limit", limit(type));
        item.put("requiresApproval", type != Type.SUMMARY);
        if (!allowed) {
            item.put("columns", List.of());
            item.put("availableRows", null);
            item.put("restrictedHistoryRows", null);
            item.put("coverage", null);
            item.put("status", "UNAVAILABLE");
            item.put("reasonCodes", List.of("PERMISSION_DENIED"));
            return item;
        }
        if (window == null || table == null) {
            item.put("columns", List.of());
            item.put("availableRows", null);
            item.put("restrictedHistoryRows", null);
            item.put("coverage", null);
            item.put("status", "UNAVAILABLE");
            item.put("reasonCodes", window == null
                    ? List.of("EXPORT_GENERATION_UPGRADING", "EXPORT_ROWS_UNVERIFIED")
                    : List.of("EXPORT_SOURCE_UNAVAILABLE"));
            return item;
        }
        Coverage coverage = table.coverage();
        boolean readable = coverage == null || coverage.status() == Status.COMPLETE || coverage.status() == Status.PARTIAL;
        String status;
        List<String> reasons = coverage == null ? List.of() : coverage.reasons();
        if (!readable) {
            status = "READ_FAILED";
            reasons = append(reasons, "SOURCE_READ_FAILED", "EXPORT_SOURCE_UNAVAILABLE");
        } else if (table.rows().isEmpty()) {
            status = "EMPTY";
            reasons = List.of("EXPORT_EMPTY");
        } else if (table.rows().size() > limit(type)) {
            status = "LIMIT_EXCEEDED";
            reasons = append(reasons, "EXPORT_ROW_LIMIT");
        } else {
            status = "AVAILABLE";
        }
        item.put("columns", columnLabels(table.columns()));
        item.put("availableRows", table.rows().size());
        item.put("restrictedHistoryRows", table.restrictedHistoryRows());
        item.put("coverage", coverageMap(coverage));
        item.put("status", status);
        item.put("reasonCodes", reasons);
        return item;
    }

    // ---------- frozen content ----------

    private Table frozenTable(TaskRow task) {
        List<String> columns = readList(task.columnsJson());
        List<Map<String, String>> rows = readRows(task.contentJson());
        Coverage coverage = coverageFrom(readMap(task.coverageJson()));
        return new Table(Type.valueOf(task.taskType()), columns, rows, coverage, task.restrictedRows(),
                readCustomerIds(task.disclosedCustomersJson()));
    }

    private void writeFrozenFile(TaskRow task, Table table) {
        if (table == null) throw new BizException(503, "EXPORT_SOURCE_UNAVAILABLE");
        String csv = csv(table);
        String fileName = "support-performance-" + task.taskType().toLowerCase(Locale.ROOT).replace('_', '-')
                + "-" + task.requestId() + ".csv";
        if (mapper.markReady(task.id(), task.status(), csv, fileName, LocalDateTime.now()) != 1) {
            throw new BizException(409, "EXPORT_INVALID_STATE");
        }
    }

    static String csv(Table table) {
        StringBuilder out = new StringBuilder("\uFEFF");
        List<String> labels = columnLabels(table.columns());
        out.append(String.join(",", labels.stream().map(SupportPerformanceExportService::csvCell).toList())).append("\r\n");
        for (Map<String, String> row : table.rows()) {
            List<String> cells = new ArrayList<>(table.columns().size());
            for (String column : table.columns()) {
                String value = row.get(column);
                cells.add(isMoney(column) && value != null && !value.isBlank() ? value : csvCell(value));
            }
            out.append(String.join(",", cells)).append("\r\n");
        }
        return out.toString();
    }

    private static boolean isMoney(String column) {
        String c = column.toLowerCase(Locale.ROOT);
        return c.contains("amount") || c.contains("usdt") || c.contains("fee") || c.contains("received")
                || c.contains("total") || c.contains("correction") || c.contains("vnd")
                || c.contains("walletdebited") || c.contains("netreceived")
                || c.contains("inflow") || c.contains("withdrawal");
    }

    static String csvCell(String value) {
        String text = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
        String stripped = text.stripLeading();
        if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static List<String> columnLabels(List<String> columns) {
        List<String> labels = new ArrayList<>(columns.size());
        for (String column : columns) labels.add(label(column));
        return labels;
    }

    private static String label(String column) {
        String c = column.toLowerCase(Locale.ROOT);
        if (c.startsWith("counts.")) return switch (c.substring(7)) {
            case "customers" -> "客户数";
            case "negativeperformancecustomers" -> "负业绩客户数";
            case "firstpaymentcustomers" -> "首充客户数";
            case "directinvitecustomers" -> "直接邀请客户数";
            case "withinrewardrangecustomers" -> "范围内客户数";
            case "beyondrewardrangecustomers" -> "超出范围客户数";
            default -> c.substring(7);
        };
        if (c.startsWith("inflowbychannel.")) return "入金-" + c.substring(16);
        if (c.startsWith("correctionbychannel.")) return "更正-" + c.substring(20);
        if (c.startsWith("withdrawalbychannel.")) return "提现-" + c.substring(20);
        if (c.startsWith("vndreference.")) return "VND-" + c.substring(13);
        if (c.startsWith("references.")) return "参考-" + c.substring(11);
        return switch (c) {
            case "agent" -> "客服";
            case "group" -> "事件组";
            case "supervisoratcutoff" -> "截止时主管";
            case "month" -> "月份";
            case "performanceamount" -> "业绩额(USDT)";
            case "inflowtotal" -> "入金合计(USDT)";
            case "withdrawaltotal" -> "提现合计(USDT)";
            case "correctiontotal" -> "本期更正(USDT)";
            case "customerno" -> "客户编码";
            case "maskednickname" -> "客户昵称";
            case "eventagent" -> "事件客服";
            case "eventgroup" -> "事件组";
            case "bindingperiods" -> "绑定段";
            case "devicepurchases" -> "购机参考";
            case "refunds" -> "退款";
            case "customeragentcumulativeperformance" -> "当前客服累计业绩(USDT)";
            case "effectiveat" -> "生效时间";
            case "occurredat" -> "发生时间";
            case "kind" -> "类型";
            case "referencecategories" -> "参考类别";
            case "channel" -> "渠道";
            case "network" -> "网络";
            case "businessno" -> "业务单号";
            case "businessamount" -> "业务金额";
            case "businesscurrency" -> "业务币种";
            case "roundingadjustmentusdt" -> "进位调整(USDT)";
            case "totalpaidusdt" -> "总实付(USDT)";
            case "performancedeltausdt" -> "业绩变动(USDT)";
            case "walletdebitedusdt" -> "钱包扣款(USDT)";
            case "withdrawalfeeusdt" -> "提现费(USDT)";
            case "channelfeeusdt" -> "通道费(USDT)";
            case "networkfeeusdt" -> "网络费(USDT)";
            case "receivedusdt" -> "到账(USDT)";
            case "vndamount" -> "VND 参考";
            case "sourcestatus" -> "来源状态";
            case "status" -> "状态";
            case "originalbusinessno" -> "原单号";
            case "coveragereason" -> "覆盖原因";
            case "asof" -> "统计截至";
            case "businesszone" -> "业务时区";
            case "coverage" -> "数据覆盖";
            case "missingsources" -> "缺失来源";
            default -> c;
        };
    }

    // ---------- authorization ----------

    private boolean readCode() {
        return owns("service_m1_read") || owns("service_m3_read");
    }

    private boolean owns(String permission) {
        return SupportOwnershipService.hasAuthority(permission);
    }

    private boolean superAdmin() {
        return platformSuperAdmin();
    }

    private boolean platformSuperAdmin() {
        try {
            return superAdmins.isSuperAdmin(org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean ownsType(String taskType, Long actor) {
        if ("SUMMARY".equals(taskType)) return owns(SUMMARY_PERMISSION);
        if ("CUSTOMER_DETAIL".equals(taskType) || "TRANSACTION_DETAIL".equals(taskType)) return owns(DETAIL_PERMISSION);
        return false;
    }

    private boolean outCapable(Long actor) {
        if (superAdmin()) return true;
        if (!selfEmployed(actor)) return false;
        if (!ownedGroups().isEmpty()) return true;
        // A supervisor qualification without owned groups blocks exports entirely.
        return !managerQualified();
    }

    private boolean managerQualified() {
        try {
            return ownership.availableReadModes().contains(
                    ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.MANAGED);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private List<Map<String, Object>> scopes(Long actor) {
        List<Map<String, Object>> scopes = new ArrayList<>();
        List<Long> groups = ownedGroups();
        List<Type> types = allowedTypes(actor);
        if (groups.isEmpty() && !superAdmin() && managerQualified()) {
            scopes.add(row("kind", "MANAGED_GROUPS", "groups", List.of(), "allowedTypes", List.of(),
                    "blockedReason", "NO_MANAGED_GROUP"));
            return scopes;
        }
        scopes.add(row("kind", "SELF", "groups", List.of(), "allowedTypes", types, "blockedReason", null));
        scopes.add(row("kind", "MANAGED_GROUPS", "groups",
                groups.stream().map(id -> Map.of("id", String.valueOf(id), "name", "")).toList(),
                "allowedTypes", groups.isEmpty() ? List.of() : types,
                "blockedReason", groups.isEmpty() ? "NO_MANAGED_GROUP" : null));
        if (superAdmin()) scopes.add(row("kind", "ALL", "groups", List.of(), "allowedTypes", types, "blockedReason", null));
        return scopes;
    }

    /** Formal employment modes; an empty list means no current support qualification at all. */
    private List<ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode> employmentModes(Long actor) {
        try {
            var modes = ownership.availableReadModes(actor);
            return modes == null ? List.of() : modes;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private boolean supervisorCurrent(Long actor) {
        List<ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode> modes = employmentModes(actor);
        return modes.contains(ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.MANAGED)
                || modes.contains(ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.ALL);
    }

    private boolean selfEmployed(Long actor) {
        return !employmentModes(actor).isEmpty();
    }

    /** §0.5: a supervisor with no owned group may not export; judged for the given actor, never the caller. */
    private boolean exportCapable(Long actor) {
        if (applicantSuperAdmin(actor)) return true;
        if (!groupsOf(actor).isEmpty()) return true;
        return !supervisorCurrent(actor);
    }

    /** Ordinary customer-service employment only buys the applicant's own summary. */
    private boolean employmentAllows(Long actor, TaskRow task) {
        if (!selfEmployed(actor) || !exportCapable(actor)) return false;
        boolean supervisor = supervisorCurrent(actor);
        String kind = text(readMap(task.scopeJson()).get("kind"));
        if ("MANAGED_GROUPS".equals(kind) && !supervisor) return false;
        if ("SELF".equals(kind) && !"SUMMARY".equals(task.taskType()) && !supervisor) return false;
        return true;
    }

    private List<Type> allowedTypes(Long actor) {
        List<Type> types = new ArrayList<>();
        if (!selfEmployed(actor)) return types;
        if (owns(SUMMARY_PERMISSION)) types.add(Type.SUMMARY);
        if (supervisorCurrent(actor) && owns(DETAIL_PERMISSION)) {
            types.add(Type.CUSTOMER_DETAIL);
            types.add(Type.TRANSACTION_DETAIL);
        }
        return types;
    }

    private Scope resolveScope(Long actor, Object raw) {
        if (!selfEmployed(actor)) throw new BizException(403, "PERMISSION_DENIED");
        Map<String, Object> scope = raw instanceof Map<?, ?> map ? toStringMap(map) : Map.of();
        String kind = text(scope.get("kind"));
        kind = kind == null || kind.isBlank() ? "SELF" : kind.toUpperCase(Locale.ROOT);
        if (!Set.of("SELF", "MANAGED_GROUPS", "ALL").contains(kind)) throw new BizException(422, "EXPORT_REQUEST_INVALID");
        List<String> groupIds = strings(scope.get("groupIds"));
        if (!"MANAGED_GROUPS".equals(kind) && !groupIds.isEmpty()) {
            throw new BizException(422, "EXPORT_REQUEST_INVALID");
        }
        if ("ALL".equals(kind) && !superAdmin()) throw new BizException(403, "PERMISSION_DENIED");
        if ("MANAGED_GROUPS".equals(kind)) {
            if (groupIds.isEmpty()) throw new BizException(422, "EXPORT_REQUEST_INVALID");
            if (!supervisorCurrent(actor)) throw new BizException(403, "PERMISSION_DENIED");
            Set<String> owned = ownedGroups().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
            if (!owned.containsAll(groupIds)) throw new BizException(403, "PERMISSION_DENIED");
        }
        return new Scope(kind, groupIds, List.of());
    }

    private List<Long> ownedGroups() {
        Long actor = ownership.actorId();
        return actor == null ? List.of() : groupsOf(actor);
    }

    /** Supervisor-owned groups of one account, read with the same predicate the M analytics uses. */
    private List<Long> groupsOf(Long adminId) {
        try {
            var scope = new ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope(adminId,
                    ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.MANAGED, null, null);
            return new ArrayList<>(analyticsMapper.currentGrantedGroupIds(scope));
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private boolean scopeStillAllowed(Long actor, TaskRow task) {
        Map<String, Object> scope = readMap(task.scopeJson());
        String kind = text(scope.get("kind"));
        if (kind == null) return false;
        if ("SELF".equals(kind)) return task.requesterId().equals(actor) || superAdmin();
        if ("ALL".equals(kind)) return superAdmin();
        List<String> groupIds = strings(scope.get("groupIds"));
        Set<String> owned = ownedGroups().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        return owned.containsAll(groupIds);
    }

    private boolean reviewerScopeOk(Long actor, TaskRow task) {
        String type = task.taskType();
        // Reviewers need R+A plus their own reviewable scope; the detail code is not required.
        if ("SUMMARY".equals(type) || !("CUSTOMER_DETAIL".equals(type) || "TRANSACTION_DETAIL".equals(type))) return false;
        Map<String, Object> scope = readMap(task.scopeJson());
        String kind = text(scope.get("kind"));
        if ("ALL".equals(kind)) return superAdmin();
        if ("MANAGED_GROUPS".equals(kind)) {
            Set<String> owned = groupsOf(actor).stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
            return owned.containsAll(strings(scope.get("groupIds")));
        }
        // A SELF batch is reviewable only at platform level.
        return superAdmin();
    }

    /** Approval rechecks the applicant's own codes, qualification and frozen event scope. */
    private boolean applicantStillEligible(TaskRow task) {
        Long applicant = task.requesterId();
        Set<String> codes;
        try {
            codes = permissionCache.getPermissionCodes(applicant);
        } catch (RuntimeException ex) {
            return false;
        }
        boolean read = codes.contains("service_m1_read") || codes.contains("service_m3_read");
        boolean typed = "SUMMARY".equals(task.taskType())
                ? codes.contains(SUMMARY_PERMISSION)
                : codes.contains(DETAIL_PERMISSION);
        if (!read || !typed) return false;
        return employmentAllows(applicant, task);
    }

    /** Frozen groups and disclosed customers must still be readable by the original applicant. */
    private boolean applicantScopeOk(TaskRow task) {
        if (!applicantCustomersReadable(task)) return false;
        Long applicant = task.requesterId();
        Map<String, Object> scope = readMap(task.scopeJson());
        String kind = text(scope.get("kind"));
        if ("ALL".equals(kind)) return applicantSuperAdmin(applicant);
        if ("MANAGED_GROUPS".equals(kind)) {
            Set<String> owned = groupsOf(applicant).stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
            return owned.containsAll(strings(scope.get("groupIds")));
        }
        return true;
    }

    /** Same rule as SuperAdminAuthorization, applied to the applicant instead of the caller. */
    private boolean applicantSuperAdmin(Long applicant) {
        try {
            var admin = adminMapper.selectById(applicant);
            return admin != null && !Integer.valueOf(1).equals(admin.getIsDeleted())
                    && Integer.valueOf(1).equals(admin.getStatus())
                    && Integer.valueOf(1).equals(admin.getSuperAdmin());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** The approval and the generation both recheck the original applicant's object rights. */
    private boolean applicantCustomersReadable(TaskRow task) {
        if ("SUMMARY".equals(task.taskType())) return true;
        for (Long id : readCustomerIds(task.disclosedCustomersJson())) {
            try {
                if (!ownership.canRead(task.requesterId(), id)) return false;
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return true;
    }

    private boolean disclosedCustomersAllowed(TaskRow task) {
        if ("SUMMARY".equals(task.taskType())) return true;
        List<Long> ids = readCustomerIds(task.disclosedCustomersJson());
        if (ids.isEmpty()) return true;
        try {
            return ownership.readableCustomerIds(ids).containsAll(ids);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ---------- views and helpers ----------

    private Map<String, Object> taskView(TaskRow task, Long actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", String.valueOf(task.id()));
        out.put("requestId", task.requestId());
        out.put("version", task.version());
        out.put("type", task.taskType());
        out.put("requesterId", String.valueOf(task.requesterId()));
        out.put("scope", task.scopeJson() == null ? null : readMap(task.scopeJson()));
        out.put("window", task.windowJson() == null ? null : readMap(task.windowJson()));
        out.put("status", task.status());
        out.put("reasonCodes", outwardReason(task.generationError()));
        out.put("requestedAt", instant(task.createdAt()));
        out.put("approvedAt", task.approvedAt() == null ? null : instant(task.approvedAt()));
        out.put("reviewerId", task.reviewerId() == null ? null : String.valueOf(task.reviewerId()));
        out.put("rowCount", task.rowCount());
        out.put("restrictedHistoryRows", task.restrictedRows());
        boolean self = task.requesterId().equals(actor);
        boolean fileLive = task.fileCreatedAt() == null
                || !task.fileCreatedAt().plusDays(FILE_RETENTION_DAYS).isBefore(LocalDateTime.now());
        out.put("canDownload", "READY".equals(task.status()) && self && readCode() && ownsType(task.taskType(), actor)
                && employmentAllows(actor, task) && scopeStillAllowed(actor, task)
                && disclosedCustomersAllowed(task) && fileLive);
        boolean pending = "PENDING_APPROVAL".equals(task.status());
        out.put("decisionCapabilities", Map.of(
                "APPROVE", decisionCapability(pending, self, task),
                "REJECT", rejectCapability(pending, self, task)));
        out.put("canWithdraw", self && pending && readCode() && owns(DETAIL_PERMISSION));
        return out;
    }

    private List<String> laneTypes(Long actor) {
        List<String> types = new ArrayList<>();
        if (owns(SUMMARY_PERMISSION)) types.add("SUMMARY");
        if (owns(DETAIL_PERMISSION)) {
            types.add("CUSTOMER_DETAIL");
            types.add("TRANSACTION_DETAIL");
        }
        return types;
    }

    private static final int ACCESS_NONE = 0;
    private static final int ACCESS_MINIMAL = 1;
    private static final int ACCESS_FULL = 2;

    /** One current-access decision shared by the detail shape and the command (ExportTask) shape. */
    private int accessLevel(Long actor, TaskRow task) {
        boolean requester = task.requesterId().equals(actor);
        if (!requester) {
            boolean reviewer = readCode() && owns(APPROVE_PERMISSION) && reviewerScopeOk(actor, task);
            return reviewer ? ACCESS_FULL : ACCESS_NONE;
        }
        if (!readCode() || !ownsType(task.taskType(), actor)) return ACCESS_NONE;
        return scopeStillAllowed(actor, task) ? ACCESS_FULL : ACCESS_MINIMAL;
    }

    private Map<String, Object> detailView(Long actor, TaskRow task) {
        int level = accessLevel(actor, task);
        if (level == ACCESS_NONE) throw new BizException(404, "EXPORT_TASK_NOT_FOUND");
        if (level == ACCESS_MINIMAL) {
            Map<String, Object> minimal = new LinkedHashMap<>(minimalView(task, actor));
            minimal.put("requestReason", task.requestReason());
            minimal.put("columns", null);
            minimal.put("coverage", null);
            minimal.put("scopeSummary", null);
            minimal.put("decisionReason", null);
            return minimal;
        }
        Map<String, Object> out = new LinkedHashMap<>(taskView(task, actor));
        out.put("requestReason", task.requestReason());
        out.put("columns", readList(task.columnsJson()));
        out.put("coverage", readMap(task.coverageJson()));
        out.put("scopeSummary", scopeSummary(task));
        out.put("decisionReason", task.decisionReason());
        return out;
    }

    /** §4.2 ExportTask: command replies never carry the detail-only keys. */
    private Map<String, Object> commandView(Long actor, TaskRow task) {
        int level = accessLevel(actor, task);
        if (level == ACCESS_NONE) throw new BizException(404, "EXPORT_TASK_NOT_FOUND");
        return level == ACCESS_MINIMAL ? minimalView(task, actor) : taskView(task, actor);
    }

    private Map<String, Object> minimalView(TaskRow task, Long actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", String.valueOf(task.id()));
        out.put("requestId", task.requestId());
        out.put("version", task.version());
        out.put("type", task.taskType());
        out.put("requesterId", String.valueOf(task.requesterId()));
        out.put("scope", null);
        out.put("window", null);
        out.put("status", task.status());
        out.put("reasonCodes", List.of());
        out.put("requestedAt", instant(task.createdAt()));
        out.put("approvedAt", task.approvedAt() == null ? null : instant(task.approvedAt()));
        out.put("reviewerId", null);
        out.put("rowCount", null);
        out.put("restrictedHistoryRows", null);
        out.put("canDownload", false);
        out.put("decisionCapabilities", Map.of(
                "APPROVE", capability(false, "EXPORT_SCOPE_CHANGED"),
                "REJECT", capability(false, "EXPORT_SCOPE_CHANGED")));
        out.put("canWithdraw", task.requesterId().equals(actor) && "PENDING_APPROVAL".equals(task.status()));
        return out;
    }

    private Map<String, Object> projectBatch(Long actor, String requestId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (TaskRow task : mapper.listByRequest(requestId)) {
            if (!readCode() || !ownsType(task.taskType(), actor)) continue;
            items.add(scopeStillAllowed(actor, task) ? taskView(task, actor) : minimalView(task, actor));
        }
        return row("requestId", requestId, "items", items);
    }

    private Map<String, Object> decisionCapability(boolean pending, boolean self, TaskRow task) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!pending) return capability(false, "EXPORT_INVALID_STATE");
        if (self) return capability(false, "EXPORT_SELF_APPROVAL_FORBIDDEN");
        if (!readCode() || !owns(APPROVE_PERMISSION) || !outCapable(actorId())) {
            return capability(false, "PERMISSION_DENIED");
        }
        if (!reviewerScopeOk(actorId(), task)) return capability(false, "EXPORT_SCOPE_CHANGED");
        if (!applicantStillEligible(task)) return capability(false, "EXPORT_APPLICANT_INELIGIBLE");
        if (!applicantScopeOk(task)) return capability(false, "EXPORT_SCOPE_CHANGED");
        return capability(true, null);
    }

    private Map<String, Object> rejectCapability(boolean pending, boolean self, TaskRow task) {
        if (!pending) return capability(false, "EXPORT_INVALID_STATE");
        if (self) return capability(false, "EXPORT_SELF_APPROVAL_FORBIDDEN");
        if (!readCode() || !owns(APPROVE_PERMISSION)) return capability(false, "PERMISSION_DENIED");
        if (!reviewerScopeOk(actorId(), task)) return capability(false, "EXPORT_SCOPE_CHANGED");
        return capability(true, null);
    }

    private static List<String> outwardReason(String error) {
        if (error == null) return List.of();
        return switch (error) {
            case "EXPORT_GENERATION_FAILED", "EXPORT_SOURCE_UNAVAILABLE", "EXPORT_SCOPE_CHANGED",
                 "EXPORT_APPLICANT_INELIGIBLE", "PERMISSION_DENIED" -> List.of(error);
            default -> List.of("EXPORT_GENERATION_FAILED");
        };
    }

    private static Map<String, Object> capability(boolean available, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", available);
        out.put("reasonCode", reason);
        return out;
    }

    private String scopeSummary(TaskRow task) {
        Map<String, Object> scope = readMap(task.scopeJson());
        String kind = text(scope.get("kind"));
        String month = text(readMap(task.windowJson()).get("month"));
        return kind + (month == null ? "" : " · " + month);
    }

    private Map<String, Object> windowView(Window window) {
        return row("definitionVersion", window.definitionVersion(), "sourceVersion", window.sourceVersion(),
                "month", window.month(), "basis", window.basis(), "from", window.from(), "to", window.to(),
                "asOf", window.asOf(), "businessZone", window.businessZone());
    }

    private void audit(String action, TaskRow task, Long actor, String reason, Map<String, Object> detail) {
        Map<String, Object> payload = new LinkedHashMap<>(detail);
        payload.put("reason", reason);
        payload.put("resourceId", String.valueOf(task.id()));
        payload.put("requestId", task.requestId());
        payload.put("type", task.taskType());
        auditLog.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("SUPPORT_PERFORMANCE_EXPORT")
                .resourceId(String.valueOf(task.id()))
                .bizNo(task.requestId())
                .actorType("ADMIN")
                .actorId(actor)
                .result("SUCCESS")
                .detail(payload)
                .build());
    }

    private TaskRow required(Long taskId) {
        if (taskId == null || taskId <= 0) throw new BizException(400, "EXPORT_REQUEST_INVALID");
        TaskRow task = mapper.findTask(taskId);
        if (task == null) throw new BizException(404, "EXPORT_TASK_NOT_FOUND");
        return task;
    }

    private Long actorId() {
        Long actor = ownership.actorId();
        if (actor == null || actor <= 0) throw new BizException(401, "ADMIN_AUTH_REQUIRED");
        return actor;
    }

    private void requireKey(String key) {
        if (!StringUtils.hasText(key) || key.trim().length() < 8 || key.trim().length() > 128) {
            throw new BizException(422, "EXPORT_IDEMPOTENCY_KEY_INVALID");
        }
    }

    private String reason(Object value) {
        String reason = text(value);
        if (reason == null || reason.strip().length() < MIN_REASON_CHARS
                || reason.strip().length() > MAX_REASON_CHARS) throw new BizException(422, "EXPORT_REASON_INVALID");
        return reason.strip();
    }

    private static List<Type> types(Object raw) {
        List<String> names = raw instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        if (names.isEmpty()) throw new BizException(422, "EXPORT_REQUEST_INVALID");
        List<Type> types = new ArrayList<>();
        for (String name : names) {
            try {
                Type type = Type.valueOf(name.trim().toUpperCase(Locale.ROOT));
                if (!types.contains(type)) types.add(type);
            } catch (IllegalArgumentException ex) {
                throw new BizException(422, "EXPORT_REQUEST_INVALID");
            }
        }
        return types;
    }

    private static int limit(Type type) {
        return switch (type) {
            case SUMMARY -> SUMMARY_LIMIT;
            case CUSTOMER_DETAIL -> CUSTOMER_DETAIL_LIMIT;
            case TRANSACTION_DETAIL -> TRANSACTION_DETAIL_LIMIT;
        };
    }

    private static List<String> append(List<String> reasons, String... extra) {
        List<String> out = new ArrayList<>(reasons);
        for (String value : extra) if (!out.contains(value)) out.add(value);
        return out;
    }

    private Map<String, Object> coverageMap(Coverage coverage) {
        if (coverage == null) return null;
        return row("status", coverage.status().name(), "reasonCodes", coverage.reasons());
    }

    private Coverage coverageFrom(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return null;
        String status = text(raw.get("status"));
        try {
            return new Coverage(Status.valueOf(status == null ? "UNKNOWN" : status), strings(raw.get("reasonCodes")));
        } catch (IllegalArgumentException ex) {
            return new Coverage(Status.UNKNOWN, strings(raw.get("reasonCodes")));
        }
    }

    private static String instant(LocalDateTime value) {
        if (value == null) return null;
        return LocalDateTime.ofInstant(value.atZone(StorageTime.ZONE).toInstant(), ZoneOffset.UTC).toString() + "Z";
    }

    private String clip(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw new BizException(422, "EXPORT_REQUEST_INVALID");
        }
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static Map<String, Object> toStringMap(Map<?, ?> value) {
        Map<String, Object> out = new LinkedHashMap<>();
        value.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return out;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("EXPORT_JSON_SERIALIZE_FAILED", ex);
        }
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Object parsed = json.readValue(value, Object.class);
            return parsed instanceof Map<?, ?> map ? toStringMap(map) : Map.of();
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<String> readList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            Object parsed = json.readValue(value, Object.class);
            return parsed instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        } catch (Exception ex) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> readRows(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            Object parsed = json.readValue(value, Object.class);
            if (!(parsed instanceof List<?> list)) return List.of();
            List<Map<String, String>> rows = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                Map<String, String> row = new LinkedHashMap<>();
                map.forEach((key, field) -> row.put(String.valueOf(key), field == null ? null : String.valueOf(field)));
                rows.add(row);
            }
            return rows;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<Long> readCustomerIds(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            Object parsed = json.readValue(value, Object.class);
            List<Long> ids = new ArrayList<>();
            if (parsed instanceof List<?> list) {
                for (Object item : list) {
                    try {
                        ids.add(Long.valueOf(String.valueOf(item)));
                    } catch (NumberFormatException ignored) {
                        // Skip non-numeric identifiers; the fact source owns their shape.
                    }
                }
            }
            return ids;
        } catch (Exception ex) {
            return List.of();
        }
    }
}
