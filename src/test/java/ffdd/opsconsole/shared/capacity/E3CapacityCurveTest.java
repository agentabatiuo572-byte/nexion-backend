package ffdd.opsconsole.shared.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class E3CapacityCurveTest {
    private final Map<String, String> config = Map.of(
            "capacityBand1DeltaPct", "-3",
            "capacityBand2DeltaPct", "-6",
            "capacityBand3DeltaPct", "-23.7",
            "stageEarlyEnd", "3",
            "stageMidEnd", "8",
            "cycleMonths", "12",
            "capacityFloorPct", "22");

    @Test
    void compoundsAcrossEveryBoundaryAndContinuesPastTheChartHorizonToTheFloor() {
        assertThat(E3CapacityCurve.capacityPct(0, config)).isEqualByComparingTo("100.000000");
        assertThat(E3CapacityCurve.capacityPct(3, config)).isEqualByComparingTo("91.267300");
        assertThat(E3CapacityCurve.capacityPct(4, config)).isEqualByComparingTo("85.791262");
        assertThat(E3CapacityCurve.capacityPct(8, config)).isEqualByComparingTo("66.981439");
        assertThat(E3CapacityCurve.capacityPct(9, config)).isEqualByComparingTo("51.106838");
        assertThat(E3CapacityCurve.capacityPct(13, config)).isEqualByComparingTo("22.000000");
        assertThat(E3CapacityCurve.capacityPct(20, config)).isEqualByComparingTo("22.000000");
    }

    @Test
    void chartHorizonNeverChangesRuntimeCapacity() {
        Map<String, String> widerChart = new java.util.LinkedHashMap<>(config);
        widerChart.put("cycleMonths", "24");

        assertThat(E3CapacityCurve.capacityPct(20, widerChart))
                .isEqualByComparingTo(E3CapacityCurve.capacityPct(20, config));
    }

    @Test
    void participationDirectionUsesActualCurveInsteadOfAssumingEnableAlwaysAmplifies() {
        assertThat(E3CapacityCurve.participationChangeAmplifies(config, config, true, false)).isTrue();
        assertThat(E3CapacityCurve.participationChangeAmplifies(config, config, false, true)).isFalse();

        Map<String, String> growth = new java.util.LinkedHashMap<>(config);
        growth.put("capacityBand1DeltaPct", "3");
        growth.put("capacityBand2DeltaPct", "6");
        growth.put("capacityBand3DeltaPct", "10");
        assertThat(E3CapacityCurve.participationChangeAmplifies(growth, growth, false, true)).isTrue();
        assertThat(E3CapacityCurve.participationChangeAmplifies(growth, growth, true, false)).isFalse();
    }
}
