package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.bi.application.OpsBiService;
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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OpsDashboardServiceTest {
    private final OpsTreasuryService treasuryService = mock(OpsTreasuryService.class);
    private final OpsFinanceService financeService = mock(OpsFinanceService.class);
    private final OpsRiskService riskService = mock(OpsRiskService.class);
    private final OpsKillSwitchService killSwitchService = mock(OpsKillSwitchService.class);
    private final OpsBiService biService = mock(OpsBiService.class);
    private final OpsConversationService conversationService = mock(OpsConversationService.class);
    private final PlatformConfigFacade configFacade = new PlatformConfigFacade() {
        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.empty();
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
        }
    };
    private final OpsDashboardService service = new OpsDashboardService(
            treasuryService,
            financeService,
            riskService,
            killSwitchService,
            biService,
            conversationService,
            configFacade);

    @Test
    void summaryAggregatesRealAdminEndpointsForCommandCenter() {
        when(treasuryService.dualLedger()).thenReturn(ApiResult.ok(Map.of(
                "snapshot", linked(
                        "reserveUsd", new BigDecimal("1200000"),
                        "liabilitiesUsd", new BigDecimal("1000000"),
                        "coverageRatio", new BigDecimal("120.00"),
                        "redlinePct", new BigDecimal("100.00"),
                        "healthyPct", new BigDecimal("110.00"),
                        "netFlow24hUsd", new BigDecimal("80000"),
                        "queueBacklogCount", 2,
                        "queueBacklogUsd", new BigDecimal("24000"),
                        "avgRiskScore", 62,
                        "coverageSeries", List.of(113, 115, 118, 120),
                        "scope", "active liabilities"),
                "accounts", List.of(
                        Map.of("key", "balance", "label", "withdrawable USDT", "amount", new BigDecimal("600000"), "source", "wallet"),
                        Map.of("key", "withdraw_queue", "label", "withdrawal queue", "amount", new BigDecimal("24000"), "source", "withdrawal")),
                "maturity7d", List.of(Map.of("day", "D+1", "withdrawUsd", new BigDecimal("3000"), "interestUsd", new BigDecimal("700"))),
                "prev", Map.of("reserveUsd", new BigDecimal("1160000"), "netFlow24hUsd", new BigDecimal("65000")))));
        when(financeService.topupOverview()).thenReturn(ApiResult.ok(Map.of("ledgerCount", 12L)));
        when(financeService.withdrawals(new WithdrawalQueryRequest(null, null, null, 1, 100)))
                .thenReturn(ApiResult.ok(new PageResult<>(2, 1, 100, List.of(
                        withdrawal("WD-1", "REVIEWING", new BigDecimal("9000")),
                        withdrawal("WD-2", "PENDING_CHAIN", new BigDecimal("15000"))))));
        when(riskService.overview()).thenReturn(ApiResult.ok(Map.of("highRisk", 9L, "manualReview", 3L)));
        when(killSwitchService.matrix()).thenReturn(ApiResult.ok(Map.of(
                "activeGates", List.of(
                        Map.of("key", "withdraw", "enabled", true),
                        Map.of("key", "staking", "enabled", false)))));
        when(biService.overview()).thenReturn(ApiResult.ok(Map.of(
                "l1", Map.of("kpis", List.of(Map.of(
                        "n", 1,
                        "name", "Day 0 自动接入率",
                        "value", 96.4,
                        "target", 95,
                        "unit", "%",
                        "dir", "gte",
                        "cohort", "注册后90秒内首笔收益",
                        "spark", List.of(94, 95, 96.4)))),
                "l2", Map.of("funnel", List.of(
                        Map.of("stage", "注册", "users", 1200),
                        Map.of("stage", "首购", "users", 240, "cvr", 20))))));
        when(conversationService.overview()).thenReturn(ApiResult.ok(Map.of("pending", 4L, "transferred", 1L)));

        Map<String, Object> data = service.summary().getData();

        assertThat(data).containsKeys("treasury", "operations", "risk", "killSwitch", "funnel", "kpis", "pendingOperations", "domainPulse", "exportTarget", "sources");
        List<String> sources = cast(data.get("sources"));
        assertThat(sources)
                .contains(
                        "/api/admin/treasury/dual-ledger",
                        "/api/admin/finance/withdrawals",
                        "/api/admin/emergency/kill-switches",
                        "/api/admin/bi/overview",
                        "/api/admin/content/conversations/overview");
        Map<String, Object> exportTarget = cast(data.get("exportTarget"));
        assertThat(exportTarget).containsEntry("href", "/analytics/export");
        assertThat(data.toString()).doesNotContain("Premium").doesNotContain("Points").doesNotContain("NEX v2");

        Map<String, Object> operations = cast(data.get("operations"));
        assertThat(operations).containsEntry("pendingWithdrawalCount", 2L);
        assertThat(operations).containsEntry("pendingWithdrawalUsd", new BigDecimal("24000.00"));

        Map<String, Object> killSwitch = cast(data.get("killSwitch"));
        assertThat((List<?>) killSwitch.get("gates")).hasSize(2);

        List<Map<String, Object>> pendingOperations = cast(data.get("pendingOperations"));
        assertThat(pendingOperations)
                .filteredOn(row -> "L-EXPORT".equals(row.get("id")))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("count", 0L));

        Map<String, Object> domainPulse = cast(data.get("domainPulse"));
        assertThat(domainPulse)
                .containsEntry("A", "")
                .containsEntry("C", "")
                .containsEntry("E", "")
                .containsEntry("F", "")
                .containsEntry("G", "")
                .containsEntry("H", "");
        assertThat(domainPulse.toString())
                .doesNotContain("online")
                .doesNotContain("server profile contracts")
                .doesNotContain("server-canonical")
                .doesNotContain("active NEX rules");
    }

    private WithdrawalOrderView withdrawal(String withdrawalNo, String status, BigDecimal amount) {
        return new WithdrawalOrderView(
                null,
                1001L,
                withdrawalNo,
                "USDT",
                "TRC20",
                amount,
                BigDecimal.ONE,
                "T***",
                null,
                null,
                status,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private Map<String, Object> linked(Object... pairs) {
        Map<String, Object> response = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            response.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private <T> T cast(Object value) {
        return (T) value;
    }
}
