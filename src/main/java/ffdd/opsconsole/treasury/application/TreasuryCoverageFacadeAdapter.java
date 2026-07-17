package ffdd.opsconsole.treasury.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TreasuryCoverageFacadeAdapter implements TreasuryCoverageFacade {
    private final OpsTreasuryService treasuryService;

    @Override
    public TreasuryCoverageSnapshot snapshot() {
        Map<String, Object> dualLedger = treasuryService.dualLedger().getData();
        if (dualLedger == null) {
            return new TreasuryCoverageSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        Object snapshot = dualLedger.get("snapshot");
        if (!(snapshot instanceof Map<?, ?> values)) {
            return new TreasuryCoverageSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        BigDecimal coverageRatio = decimal(values.get("coverageRatio"));
        BigDecimal redlinePct = decimal(values.get("redlinePct"));
        boolean reliable = coverageRatio != null && redlinePct != null
                && Boolean.TRUE.equals(values.get("valuationReliable"));
        return new TreasuryCoverageSnapshot(
                coverageRatio == null ? BigDecimal.ZERO : coverageRatio,
                redlinePct == null ? BigDecimal.ZERO : redlinePct,
                reliable);
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
