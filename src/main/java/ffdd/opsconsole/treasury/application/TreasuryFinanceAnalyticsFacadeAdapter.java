package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.treasury.facade.TreasuryFinanceAnalyticsFacade;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TreasuryFinanceAnalyticsFacadeAdapter implements TreasuryFinanceAnalyticsFacade {
    private final OpsTreasuryService treasuryService;

    @Override
    public Map<String, Object> currentFinanceSnapshot() {
        Map<String, Object> snapshot = treasuryService.dualLedger().getData();
        return snapshot == null ? Map.of() : new LinkedHashMap<>(snapshot);
    }
}
