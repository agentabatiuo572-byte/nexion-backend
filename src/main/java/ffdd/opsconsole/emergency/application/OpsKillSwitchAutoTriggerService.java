package ffdd.opsconsole.emergency.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.treasury.facade.TreasuryEmergencySignalFacade;
import ffdd.opsconsole.treasury.facade.TreasuryEmergencySignalSnapshot;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;

@ApplicationService
@RequiredArgsConstructor
public class OpsKillSwitchAutoTriggerService {
    private final TreasuryEmergencySignalFacade signalFacade;
    private final OpsKillSwitchService killSwitchService;

    public Map<String, Object> evaluateAndApply() {
        TreasuryEmergencySignalSnapshot signals = signalFacade.snapshot();
        BigDecimal bankRunRedlinePct = signals.bankRunRedlinePct();
        BigDecimal gapThreshold = killSwitchService.maturityGapThresholdUsdt();
        boolean withdrawTriggered = false;
        boolean exchangeTriggered = false;
        if (signals.bankRunRatioPct().compareTo(bankRunRedlinePct) >= 0) {
            withdrawTriggered = killSwitchService.autoDisable(
                    "withdraw", "withdrawSurge", signals.bankRunRatioPct(), bankRunRedlinePct);
        }
        if (signals.reconciliationGapUsdt().compareTo(gapThreshold) > 0) {
            exchangeTriggered = killSwitchService.autoDisable(
                    "exchange", "maturityGap", signals.reconciliationGapUsdt(), gapThreshold);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bankRunRatioPct", signals.bankRunRatioPct());
        result.put("bankRunRedlinePct", bankRunRedlinePct);
        result.put("reconciliationGapUsdt", signals.reconciliationGapUsdt());
        result.put("withdrawTriggered", withdrawTriggered);
        result.put("exchangeTriggered", exchangeTriggered);
        return result;
    }
}
