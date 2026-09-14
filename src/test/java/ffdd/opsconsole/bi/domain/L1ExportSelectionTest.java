package ffdd.opsconsole.bi.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class L1ExportSelectionTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    @Test
    void usesTheReadModelBusinessDayAcrossShanghaiMidnightAndUtcMidnight() {
        String[][] cases = {
                {"2026-09-13T15:59:59Z", "2026-09-13"},
                {"2026-09-13T16:00:00Z", "2026-09-14"},
                {"2026-09-13T23:59:59Z", "2026-09-14"},
                {"2026-09-14T00:00:00Z", "2026-09-14"},
                {"2026-09-30T16:00:00Z", "2026-10-01"}
        };
        for (String[] example : cases) {
            Instant now = Instant.parse(example[0]);
            String today = example[1], tomorrow = LocalDate.parse(today).plusDays(1).toString();
            assertThat(L1KpiAnalytics.businessDate(now)).isEqualTo(LocalDate.parse(today));
            assertThat(L1ExportSelection.parse("custom", today, today, null, null, null, null, now).to())
                    .isEqualTo(today);
            assertThatThrownBy(() -> L1ExportSelection.parse("custom", today, tomorrow, null, null, null, null, now))
                    .hasMessage("L1_CUSTOM_WINDOW_INVALID");
        }
    }

    @Test
    void keepsTheExactSelectedCustomWindowAndNormalizesDimensions() {
        L1ExportSelection scope = L1ExportSelection.parse(" CUSTOM ", "2026-09-01", "2026-09-14",
                "2026-W36", "p2", "VI-vn", "partner:one", TODAY);
        assertThat(scope.analyticsWindow()).isEqualTo("custom|2026-09-01|2026-09-14");
        assertThat(scope.auditFields()).containsEntry("phase", "P2").containsEntry("locale", "vi-vn")
                .containsEntry("cohort", "2026-W36").containsEntry("ref", "partner:one");
    }

    @Test
    void defaultsOnlyAnAbsentWindowAndRejectsUnknownOrInconsistentWindows() {
        assertThat(L1ExportSelection.parse(null, null, null, null, null, null, null, TODAY).analyticsWindow()).isEqualTo("7d");
        assertThatThrownBy(() -> L1ExportSelection.parse("90d", null, null, null, null, null, null, TODAY))
                .hasMessage("L1_WINDOW_INVALID");
        assertThatThrownBy(() -> L1ExportSelection.parse("7d", "2026-09-01", "2026-09-14", null, null, null, null, TODAY))
                .hasMessage("L1_CUSTOM_WINDOW_NOT_SUPPORTED");
    }

    @Test
    void refusesIncompleteReversedFutureAndOversizedCustomRanges() {
        for (String[] dates : new String[][] {{"", "2026-09-14"}, {"2026-09-14", "2026-09-13"},
                {"2026-09-01", "2026-09-15"}, {"2025-01-01", "2026-09-14"}, {"invalid", "2026-09-14"}}) {
            assertThatThrownBy(() -> L1ExportSelection.parse("custom", dates[0], dates[1], null, null, null, null, TODAY))
                    .hasMessage("L1_CUSTOM_WINDOW_INVALID");
        }
    }

    @Test
    void rejectsUnsupportedDimensionsInsteadOfWideningTheExportScope() {
        assertThatThrownBy(() -> L1ExportSelection.parse("7d", null, null, "2026-W54", null, null, null, TODAY))
                .hasMessage("L1_COHORT_INVALID");
        assertThatThrownBy(() -> L1ExportSelection.parse("7d", null, null, null, "P9", null, null, TODAY))
                .hasMessage("L1_PHASE_INVALID");
        assertThatThrownBy(() -> L1ExportSelection.parse("7d", null, null, null, null, "*", null, TODAY))
                .hasMessage("L1_LOCALE_INVALID");
        assertThatThrownBy(() -> L1ExportSelection.parse("7d", null, null, null, null, null, "x;scope=ALL", TODAY))
                .hasMessage("L1_REF_INVALID");
    }
}
