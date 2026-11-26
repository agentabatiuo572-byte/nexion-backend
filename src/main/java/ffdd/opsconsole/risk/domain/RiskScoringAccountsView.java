package ffdd.opsconsole.risk.domain;

import java.util.List;

public record RiskScoringAccountsView(
        Band band, long total, int pageNum, int pageSize,
        String modelVersion, int bandLowMax, int bandHighMin, int autoEscalateScore, List<Row> records) {

    public enum Band {
        AUTO_REPORT, HIGH, HIGH_BELOW_AUTO, MEDIUM, STALE
    }

    public record Row(String userNo, Integer modelScore, Integer effectiveScore, boolean overridden,
                      String modelVersion, String asOf, boolean scored) {
    }
}
