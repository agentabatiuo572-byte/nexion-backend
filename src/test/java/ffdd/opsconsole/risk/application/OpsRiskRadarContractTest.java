package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpsRiskRadarContractTest {
    // Source-level guard keeps the independently owned B5 boundary from drifting back into b-domain.

    @Test
    void b5HasIndependentFiveDimensionApiAndFixedPressureRedline() throws Exception {
        String service = source("src/main/java/ffdd/opsconsole/risk/application/OpsRiskRadarService.java");
        String controller = source("src/main/java/ffdd/opsconsole/risk/web/OpsRiskRadarController.java");
        String mapper = source("src/main/java/ffdd/opsconsole/risk/mapper/B5RiskRadarMapper.java");

        assertThat(controller).contains("/radar", "/bankrun-thresholds", "/alert-subscription",
                "overview_b5_read", "overview_b5_threshold_write", "overview_b5_subscribe");
        assertThat(service).contains("PRESSURE_RED_LINE = new BigDecimal(\"0.7\")",
                "\"submitted\"", "\"review-passed\"", "\"processing\"",
                "\"withdraw\"", "\"staking\"", "\"genesis\"", "\"exchange\"", "\"trial\"");
        assertThat(service).doesNotContain("\"geo-block\"");
        assertThat(mapper).contains("nx_withdrawal_order", "nx_risk_signal", "INTERVAL 48 HOUR");
    }

    @Test
    void b5MutationsAreValidatedVersionedIdempotentAndAudited() throws Exception {
        String service = source("src/main/java/ffdd/opsconsole/risk/application/OpsRiskRadarService.java");
        assertThat(service).contains("AdminIdempotencyService", "expectedVersion",
                "BANKRUN_REDLINE_MUST_EXCEED_YELLOW", "REASON_REQUIRED",
                "B5_BANKRUN_THRESHOLDS_CHANGED", "B5_ALERT_SUBSCRIPTION_CHANGED",
                "B5_TRIAGE_JUMPED", "recordRequired");
    }

    private String source(String relative) throws Exception {
        return Files.readString(Path.of(relative));
    }
}
