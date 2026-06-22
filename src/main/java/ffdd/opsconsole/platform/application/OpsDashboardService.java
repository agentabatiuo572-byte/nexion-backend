package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.bi.application.OpsBiService;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.application.OpsConversationService;
import ffdd.opsconsole.emergency.application.OpsKillSwitchService;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.WithdrawalQueryRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@ApplicationService
@RequiredArgsConstructor
public class OpsDashboardService {
    private static final List<String> SOURCES = List.of(
            "/api/admin/treasury/dual-ledger",
            "/api/admin/finance/topup/overview",
            "/api/admin/finance/withdrawals",
            "/api/admin/risk/overview",
            "/api/admin/emergency/kill-switches",
            "/api/admin/bi/overview",
            "/api/admin/content/conversations/overview");
    private static final Set<String> FINAL_WITHDRAWAL_STATUSES = Set.of("SUCCESS", "FAILED", "REJECTED");
    private static final String B1_COVERAGE_ACK_KEY = "feature.B.alert.coverage-redline.ack";
    private static final Map<String, String> ACCOUNT_COLORS = Map.of(
            "balance", "--v5-success",
            "stake_principal", "--v5-brand",
            "stake_interest", "--v5-tech-cyan",
            "nex_payable", "--v5-brand-2",
            "withdraw_queue", "--v5-warning",
            "commission_cooling", "--admin-domain-f",
            "pending_withdraw", "--v5-danger");

    private final OpsTreasuryService treasuryService;
    private final OpsFinanceService financeService;
    private final OpsRiskService riskService;
    private final OpsKillSwitchService killSwitchService;
    private final OpsBiService biService;
    private final OpsConversationService conversationService;
    private final PlatformConfigFacade configFacade;

    public ApiResult<Map<String, Object>> summary() {
        Map<String, Object> dualLedger = data(treasuryService.dualLedger());
        Map<String, Object> topupOverview = data(financeService.topupOverview());
        PageResult<WithdrawalOrderView> withdrawals = data(financeService.withdrawals(
                new WithdrawalQueryRequest(null, null, null, 1, 100)));
        Map<String, Object> riskOverview = data(riskService.overview());
        Map<String, Object> killSwitchMatrix = data(killSwitchService.matrix());
        Map<String, Object> biOverview = data(biService.overview());
        Map<String, Object> conversationOverview = data(conversationService.overview());

        Map<String, Object> treasury = treasury(dualLedger);
        Map<String, Object> operations = operations(withdrawals, topupOverview, dualLedger, conversationOverview);
        Map<String, Object> risk = risk(riskOverview, dualLedger);
        Map<String, Object> killSwitch = killSwitch(killSwitchMatrix);
        List<Map<String, Object>> kpis = kpis(biOverview);
        List<Map<String, Object>> funnel = funnel(biOverview);

        Map<String, Object> response = linked(
                "service", "ops-dashboard",
                "treasury", treasury,
                "operations", operations,
                "risk", risk,
                "killSwitch", killSwitch,
                "alerts", alerts(treasury, risk, killSwitch, operations),
                "funnel", funnel,
                "kpis", kpis,
                "pendingOperations", pendingOperations(operations, risk, conversationOverview),
                "domainPulse", domainPulse(treasury, operations, risk, killSwitch, kpis, conversationOverview),
                "exportTarget", exportTarget(),
                "sources", SOURCES);
        return ApiResult.ok(response);
    }

    private Map<String, Object> treasury(Map<String, Object> dualLedger) {
        Map<String, Object> snapshot = map(dualLedger.get("snapshot"));
        List<Map<String, Object>> accounts = list(dualLedger.get("accounts")).stream()
                .map(this::ledgerAccount)
                .toList();
        Map<String, Object> ledger = linked(
                "reserveUsd", money(snapshot.get("reserveUsd")),
                "liabilitiesUsd", money(snapshot.get("liabilitiesUsd")),
                "coverageRatio", decimal(snapshot.get("coverageRatio")),
                "redlinePct", decimal(snapshot.get("redlinePct")),
                "healthyPct", decimal(snapshot.get("healthyPct")),
                "runRiskPct", decimal(snapshot.get("runRiskPct")),
                "redlineBreached", Boolean.TRUE.equals(snapshot.get("redlineBreached")),
                "netFlow24hUsd", money(snapshot.get("netFlow24hUsd")),
                "queueBacklogCount", longValue(snapshot.get("queueBacklogCount")),
                "queueBacklogUsd", money(snapshot.get("queueBacklogUsd")),
                "avgRiskScore", longValue(snapshot.get("avgRiskScore")),
                "coverageSeries", snapshot.getOrDefault("coverageSeries", List.of()),
                "scope", stringValue(snapshot.get("scope")),
                "accounts", accounts,
                "prev", map(dualLedger.get("prev")));
        return linked(
                "ledger", ledger,
                "maturity7d", maturity7d(dualLedger.get("maturity7d")),
                "raw", dualLedger);
    }

    private Map<String, Object> ledgerAccount(Map<String, Object> account) {
        String key = stringValue(account.get("key"));
        return linked(
                "key", key,
                "label", stringValue(account.get("label")),
                "amount", money(account.get("amount")),
                "source", stringValue(account.get("source")),
                "catVar", ACCOUNT_COLORS.getOrDefault(key, "--v5-ink-4"));
    }

    private List<Map<String, Object>> maturity7d(Object raw) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : list(raw)) {
            rows.add(linked(
                    "d", stringValue(row.get("day")),
                    "withdraw", money(row.get("withdrawUsd")),
                    "interest", money(row.get("interestUsd")),
                    "genesis", money(row.get("genesisUsd"))));
        }
        return rows;
    }

    private Map<String, Object> operations(
            PageResult<WithdrawalOrderView> withdrawals,
            Map<String, Object> topupOverview,
            Map<String, Object> dualLedger,
            Map<String, Object> conversationOverview) {
        List<WithdrawalOrderView> records = withdrawals == null || withdrawals.getRecords() == null
                ? List.of()
                : withdrawals.getRecords();
        List<WithdrawalOrderView> pending = records.stream()
                .filter(row -> !FINAL_WITHDRAWAL_STATUSES.contains(normalize(row.status())))
                .toList();
        BigDecimal pendingUsd = pending.stream()
                .map(WithdrawalOrderView::amount)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> snapshot = map(dualLedger.get("snapshot"));
        long pendingCount = pending.isEmpty() ? longValue(snapshot.get("queueBacklogCount")) : pending.size();
        BigDecimal pendingAmount = pendingUsd.compareTo(BigDecimal.ZERO) == 0
                ? money(snapshot.get("queueBacklogUsd"))
                : money(pendingUsd);
        return linked(
                "pendingWithdrawalCount", pendingCount,
                "pendingWithdrawalUsd", pendingAmount,
                "withdrawalTotal", withdrawals == null ? 0L : withdrawals.getTotal(),
                "topupLedgerCount", longValue(topupOverview.get("ledgerCount")),
                "supportPending", longValue(conversationOverview.get("pending")),
                "supportTransferred", longValue(conversationOverview.get("transferred")));
    }

    private Map<String, Object> risk(Map<String, Object> riskOverview, Map<String, Object> dualLedger) {
        Map<String, Object> snapshot = map(dualLedger.get("snapshot"));
        return linked(
                "flaggedAccounts", longValue(riskOverview.get("highRisk")),
                "manualReview", longValue(riskOverview.get("manualReview")),
                "blocked", longValue(riskOverview.get("blocked")),
                "avgRiskScore", longValue(snapshot.get("avgRiskScore")),
                "sources", riskOverview.getOrDefault("sources", List.of()));
    }

    private Map<String, Object> killSwitch(Map<String, Object> killSwitchMatrix) {
        List<Map<String, Object>> gates = list(killSwitchMatrix.get("activeGates")).stream()
                .map(gate -> linked(
                        "key", stringValue(gate.get("key")),
                        "on", Boolean.TRUE.equals(gate.get("enabled")),
                        "status", stringValue(gate.get("status")),
                        "ownerDomain", stringValue(gate.get("ownerDomain"))))
                .toList();
        return linked(
                "gates", gates,
                "activeGateCount", longValue(killSwitchMatrix.get("activeGateCount")),
                "coverage", killSwitchMatrix.getOrDefault("coverage", Map.of()),
                "executionModel", stringValue(killSwitchMatrix.get("executionModel")));
    }

    private List<Map<String, Object>> kpis(Map<String, Object> biOverview) {
        Map<String, Object> l1 = map(biOverview.get("l1"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : list(l1.get("kpis"))) {
            BigDecimal value = decimal(row.get("value"));
            BigDecimal target = decimal(row.get("target"));
            String dir = stringValue(row.get("dir"));
            rows.add(linked(
                    "key", "KPI-" + stringValue(row.get("n")),
                    "label", stringValue(row.get("name")),
                    "value", value,
                    "target", target,
                    "unit", stringValue(row.get("unit")),
                    "pass", passKpi(value, target, dir, row.get("band")),
                    "series", row.getOrDefault("spark", List.of()),
                    "hint", stringValue(row.get("cohort"))));
        }
        return rows;
    }

    private boolean passKpi(BigDecimal value, BigDecimal target, String dir, Object bandRaw) {
        if ("lte".equalsIgnoreCase(dir)) {
            return value.compareTo(target) <= 0;
        }
        if ("band".equalsIgnoreCase(dir) && bandRaw instanceof List<?> values && values.size() >= 2) {
            return value.compareTo(decimal(values.get(0))) >= 0 && value.compareTo(decimal(values.get(1))) <= 0;
        }
        return value.compareTo(target) >= 0;
    }

    private List<Map<String, Object>> funnel(Map<String, Object> biOverview) {
        Map<String, Object> l2 = map(biOverview.get("l2"));
        List<Map<String, Object>> raw = list(l2.get("funnel"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> row = raw.get(i);
            long count = longValue(row.get("users"));
            rows.add(linked(
                    "key", "stage-" + (i + 1),
                    "label", stringValue(row.get("stage")),
                    "event", stringValue(row.get("ev")),
                    "count", count,
                    "prevCount", count,
                    "conversion", row.getOrDefault("cvr", null),
                    "target", row.getOrDefault("target", null)));
        }
        return rows;
    }

    private List<Map<String, Object>> alerts(
            Map<String, Object> treasury,
            Map<String, Object> risk,
            Map<String, Object> killSwitch,
            Map<String, Object> operations) {
        Map<String, Object> ledger = map(treasury.get("ledger"));
        List<Map<String, Object>> alerts = new ArrayList<>();
        BigDecimal coverage = decimal(ledger.get("coverageRatio"));
        BigDecimal redline = decimal(ledger.get("redlinePct"));
        BigDecimal healthy = decimal(ledger.get("healthyPct"));
        String level = coverage.compareTo(redline) < 0 ? "high" : coverage.compareTo(healthy) < 0 ? "mid" : "low";
        boolean coverageAcked = flagEnabled(B1_COVERAGE_ACK_KEY);
        alerts.add(linked(
                "id", "B1-COVERAGE",
                "domain", "B",
                "level", level,
                "title", "B1 coverage ratio " + coverage.toPlainString() + "%",
                "hint", "Server-canonical treasury ledger",
                "acknowledged", coverageAcked));
        long manualReview = longValue(risk.get("manualReview"));
        if (manualReview > 0) {
            alerts.add(linked(
                    "id", "K-MANUAL",
                    "domain", "K",
                    "level", manualReview > 10 ? "high" : "mid",
                    "title", "Risk manual review " + manualReview,
                    "hint", "Open K risk workbench"));
        }
        long pendingWithdrawals = longValue(operations.get("pendingWithdrawalCount"));
        if (pendingWithdrawals > 0) {
            alerts.add(linked(
                    "id", "D-WITHDRAWAL",
                    "domain", "D",
                    "level", "mid",
                    "title", "Withdrawal queue " + pendingWithdrawals,
                    "hint", "Review requires Idempotency-Key and reason"));
        }
        long disabled = list(killSwitch.get("gates")).stream()
                .filter(gate -> !Boolean.TRUE.equals(gate.get("on")))
                .count();
        if (disabled > 0) {
            alerts.add(linked(
                    "id", "J-KILLSWITCH",
                    "domain", "J",
                    "level", "high",
                    "title", "Kill-switch disabled gates " + disabled,
                    "hint", "Recovery prechecks B1 coverage"));
        }
        return alerts;
    }

    private List<Map<String, Object>> pendingOperations(
            Map<String, Object> operations,
            Map<String, Object> risk,
            Map<String, Object> conversationOverview) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(linked(
                "id", "D-WITHDRAWAL",
                "role", "finance",
                "count", longValue(operations.get("pendingWithdrawalCount")),
                "title", "Withdrawal review queue",
                "href", "/finance/withdrawals"));
        rows.add(linked(
                "id", "K-MANUAL",
                "role", "risk",
                "count", longValue(risk.get("manualReview")),
                "title", "Manual risk decisions",
                "href", "/risk/cases"));
        rows.add(linked(
                "id", "M-SUPPORT",
                "role", "support",
                "count", longValue(conversationOverview.get("pending")),
                "title", "Open support conversations",
                "href", "/service/sessions"));
        rows.add(linked(
                "id", "L-EXPORT",
                "role", "audit",
                "count", 1L,
                "title", "Sensitive export review",
                "href", "/analytics/export"));
        return rows;
    }

    private Map<String, Object> domainPulse(
            Map<String, Object> treasury,
            Map<String, Object> operations,
            Map<String, Object> risk,
            Map<String, Object> killSwitch,
            List<Map<String, Object>> kpis,
            Map<String, Object> conversationOverview) {
        Map<String, Object> ledger = map(treasury.get("ledger"));
        long enabledGates = list(killSwitch.get("gates")).stream()
                .filter(gate -> Boolean.TRUE.equals(gate.get("on")))
                .count();
        long kpiPass = kpis.stream().filter(row -> Boolean.TRUE.equals(row.get("pass"))).count();
        return linked(
                "A", "RBAC and A2 audit online",
                "B", "Coverage " + decimal(ledger.get("coverageRatio")).toPlainString() + "%",
                "C", "User 360 uses server profile contracts",
                "D", "Pending withdrawals " + longValue(operations.get("pendingWithdrawalCount")),
                "E", "Device lifecycle actions use admin APIs",
                "F", "Team commission reads server ranks",
                "G", "NEX market curve is server-canonical",
                "H", "Growth phase and check-in rewards use active NEX rules",
                "I", "I9 transferred conversations " + longValue(conversationOverview.get("transferred")),
                "J", "Active gates " + enabledGates + "/" + list(killSwitch.get("gates")).size(),
                "K", "High risk accounts " + longValue(risk.get("flaggedAccounts")),
                "L", "KPI pass " + kpiPass + "/" + kpis.size(),
                "M", "Support pending " + longValue(conversationOverview.get("pending")));
    }

    private Map<String, Object> exportTarget() {
        return linked(
                "href", "/analytics/export",
                "api", "/api/admin/bi/reports/{reportId}/DOWNLOAD",
                "policy", "sensitive exports require confirm-with-reason and A2 audit");
    }

    private <T> T data(ApiResult<T> result) {
        return result == null ? null : result.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> raw ? (List<Map<String, Object>>) raw : List.of();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value).replace(",", "").replace("%", "").trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal money(Object value) {
        return decimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean flagEnabled(String configKey) {
        return configFacade.activeValue(configKey)
                .map(value -> {
                    String normalized = value.trim().toLowerCase(Locale.ROOT);
                    return "on".equals(normalized) || "true".equals(normalized) || "enabled".equals(normalized) || "1".equals(normalized);
                })
                .orElse(false);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Map<String, Object> linked(Object... pairs) {
        Map<String, Object> response = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                response.put(String.valueOf(pairs[i]), value);
            }
        }
        return response;
    }
}
