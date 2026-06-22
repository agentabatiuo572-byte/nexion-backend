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
        Object snapshot = dualLedger.get("snapshot");
        if (!(snapshot instanceof Map<?, ?> values)) {
            return new TreasuryCoverageSnapshot(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return new TreasuryCoverageSnapshot(decimal(values.get("coverageRatio")), decimal(values.get("redlinePct")));
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }
}
