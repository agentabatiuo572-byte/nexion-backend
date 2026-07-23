package ffdd.opsconsole.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class L3FinanceReportAssemblerTest {
    private final L3FinanceReportAssembler assembler = new L3FinanceReportAssembler();

    @Test
    void preservesAuthoritativeCoverageAndBuildsTheCompleteMonthlyReport() {
        L3FinanceReportAssembler.Report report = assembler.assemble(fullInputs());

        assertThat(report.period().granularity()).isEqualTo("month");
        assertThat(report.revenue().streams())
                .extracting(L3FinanceReportAssembler.RevenueRow::key)
                .containsExactly("device_sales", "team_commission", "token_economy", "compute_matching");
        assertThat(report.revenue().totalUsd()).isEqualByComparingTo("1000.00");
        assertThat(report.revenue().streams().get(0).sharePct()).isEqualByComparingTo("40.0");
        assertThat(report.revenue().streams().get(0).momPct()).isEqualByComparingTo("100.0");

        assertThat(report.redemption().ratePct()).isEqualByComparingTo("70.0");
        assertThat(report.coverage().coverageRatioPct()).isEqualByComparingTo("123.45");
        assertThat(report.coverage().netExposureUsd()).isEqualByComparingTo("120.00");
        assertThat(report.liabilities())
                .extracting(L3FinanceReportAssembler.Liability::category)
                .containsExactlyElementsOf(L3FinanceReportAssembler.CANONICAL_LIABILITY_CATEGORIES);
        assertThat(report.quality().liabilityTotalMatches()).isTrue();
        assertThat(report.maturity7d().days()).hasSize(7);
        assertThat(report.maturity30d().days()).hasSize(30);
        assertThat(report.maturity7d().genesisDueUsd()).isEqualByComparingTo("7.00");
    }

    @Test
    void keepsZeroDenominatorsUnknownInsteadOfFabricatingRates() {
        L3FinanceReportAssembler.Inputs base = fullInputs();
        List<L3FinanceReportAssembler.RevenueStream> zeroRevenue = base.revenueStreams().stream()
                .map(row -> new L3FinanceReportAssembler.RevenueStream(
                        row.key(), row.label(), row.source(), BigDecimal.ZERO, BigDecimal.ZERO))
                .toList();
        L3FinanceReportAssembler.Inputs zero = new L3FinanceReportAssembler.Inputs(
                base.period(), zeroRevenue,
                new L3FinanceReportAssembler.Redemption(0, 0, 0, 0, 0, null),
                base.coverage(), zeroLiabilities(), base.maturity7d(), base.maturity30d(),
                base.reserveCoverDays());

        L3FinanceReportAssembler.Report report = assembler.assemble(zero);

        assertThat(report.revenue().totalUsd()).isEqualByComparingTo("0.00");
        assertThat(report.revenue().streams()).allSatisfy(row -> {
            assertThat(row.sharePct()).isNull();
            assertThat(row.momPct()).isNull();
            assertThat(row.up()).isNull();
        });
        assertThat(report.redemption().ratePct()).isNull();
        assertThat(report.quality().liabilityTotalMatches()).isFalse();
    }

    @Test
    void rejectsMissingOrDuplicateCanonicalLiabilityCategories() {
        L3FinanceReportAssembler.Inputs base = fullInputs();
        List<L3FinanceReportAssembler.Liability> missing = new ArrayList<>(base.liabilities());
        missing.remove(0);

        assertThatThrownBy(() -> assembler.assemble(withLiabilities(base, missing)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eight canonical liability categories");

        List<L3FinanceReportAssembler.Liability> duplicate = new ArrayList<>(base.liabilities());
        duplicate.set(7, duplicate.get(0));
        assertThatThrownBy(() -> assembler.assemble(withLiabilities(base, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eight canonical liability categories");
    }

    @Test
    void rejectsNonConsecutiveOrWrongLengthMaturityWindows() {
        L3FinanceReportAssembler.Inputs base = fullInputs();
        List<L3FinanceReportAssembler.MaturityDay> broken = new ArrayList<>(base.maturity7d());
        broken.set(3, maturityDay(LocalDate.of(2026, 7, 30)));
        L3FinanceReportAssembler.Inputs invalid = new L3FinanceReportAssembler.Inputs(
                base.period(), base.revenueStreams(), base.redemption(), base.coverage(), base.liabilities(),
                broken, base.maturity30d(), base.reserveCoverDays());

        assertThatThrownBy(() -> assembler.assemble(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maturity7d");
    }

    private L3FinanceReportAssembler.Inputs fullInputs() {
        LocalDate start = LocalDate.of(2026, 7, 1);
        return new L3FinanceReportAssembler.Inputs(
                new L3FinanceReportAssembler.Period("month", start, start.plusMonths(1).minusDays(1), "2026-07"),
                List.of(
                        revenue("device_sales", "设备销售 GMV", "设备订单", "400", "200"),
                        revenue("team_commission", "团队佣金", "佣金账本", "300", "300"),
                        revenue("token_economy", "代币经济", "兑换账本", "200", "250"),
                        revenue("compute_matching", "算力撮合服务费", "任务账本", "100", "0")),
                new L3FinanceReportAssembler.Redemption(100, 70, 10, 15, 5, new BigDecimal("2.50")),
                new L3FinanceReportAssembler.Coverage(
                        new BigDecimal("1000.00"), new BigDecimal("880.00"), new BigDecimal("123.45"),
                        new BigDecimal("100.00"), new BigDecimal("110.00"), new BigDecimal("120.00"),
                        List.of(
                                new L3FinanceReportAssembler.CoveragePoint("2026-W27", new BigDecimal("121.00")),
                                new L3FinanceReportAssembler.CoveragePoint("2026-W28", new BigDecimal("123.45"))),
                        List.of()),
                canonicalLiabilities(),
                maturityDays(start, 7), maturityDays(start, 30), 42);
    }

    private List<L3FinanceReportAssembler.Liability> canonicalLiabilities() {
        List<L3FinanceReportAssembler.Liability> rows = new ArrayList<>();
        BigDecimal[] values = {
                new BigDecimal("500"), new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("25"),
                new BigDecimal("75"), new BigDecimal("40"), new BigDecimal("30"), new BigDecimal("60")
        };
        for (int index = 0; index < L3FinanceReportAssembler.CANONICAL_LIABILITY_CATEGORIES.size(); index++) {
            rows.add(new L3FinanceReportAssembler.Liability(
                    L3FinanceReportAssembler.CANONICAL_LIABILITY_CATEGORIES.get(index),
                    L3FinanceReportAssembler.CANONICAL_LIABILITY_LABELS.get(index), values[index]));
        }
        return rows;
    }

    private List<L3FinanceReportAssembler.Liability> zeroLiabilities() {
        return canonicalLiabilities().stream()
                .map(row -> new L3FinanceReportAssembler.Liability(row.category(), row.label(), BigDecimal.ZERO))
                .toList();
    }

    private List<L3FinanceReportAssembler.MaturityDay> maturityDays(LocalDate start, int days) {
        return java.util.stream.IntStream.range(0, days)
                .mapToObj(offset -> maturityDay(start.plusDays(offset)))
                .toList();
    }

    private L3FinanceReportAssembler.MaturityDay maturityDay(LocalDate date) {
        return new L3FinanceReportAssembler.MaturityDay(
                date, new BigDecimal("10.00"), new BigDecimal("2.00"), new BigDecimal("1.00"));
    }

    private L3FinanceReportAssembler.RevenueStream revenue(
            String key, String label, String source, String current, String previous) {
        return new L3FinanceReportAssembler.RevenueStream(
                key, label, source, new BigDecimal(current), new BigDecimal(previous));
    }

    private L3FinanceReportAssembler.Inputs withLiabilities(
            L3FinanceReportAssembler.Inputs base, List<L3FinanceReportAssembler.Liability> liabilities) {
        return new L3FinanceReportAssembler.Inputs(
                base.period(), base.revenueStreams(), base.redemption(), base.coverage(), liabilities,
                base.maturity7d(), base.maturity30d(), base.reserveCoverDays());
    }
}
