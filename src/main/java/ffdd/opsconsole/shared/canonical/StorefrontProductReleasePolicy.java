package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceGenerationGateView;
import ffdd.opsconsole.device.domain.DevicePhaseView;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The single server-side release predicate shared by storefront projection and
 * order submission. E1 phase ids are database identities, never H1 P-codes;
 * availability is therefore computed here instead of compared by the client.
 */
@Service
@RequiredArgsConstructor
public class StorefrontProductReleasePolicy {
    private static final String E1_SCOPE = "E1";

    private final DeviceCatalogRepository catalogRepository;
    private final GrowthRhythmFacade growthRhythmFacade;

    public Decision evaluate(String productNo, String unlockPhaseId) {
        GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
        if (rhythm == null || rhythm.currentMonth() < 1) {
            return Decision.closed("H1_RHYTHM_UNAVAILABLE", unlockPhaseId);
        }
        int platformMonth = Math.min(12, rhythm.currentMonth());
        List<DevicePhaseView> phases = catalogRepository.listPhases(E1_SCOPE, false);
        if (phases == null || phases.isEmpty()) {
            return Decision.closed("E1_PHASE_CONFIG_UNAVAILABLE", unlockPhaseId);
        }
        int currentIndex = Math.min(phases.size() - 1, ((platformMonth - 1) * phases.size()) / 12);

        DeviceGenerationGateView gate = StringUtils.hasText(productNo)
                ? catalogRepository.findGenerationGate(productNo).orElse(null)
                : null;
        return evaluateWithContext(unlockPhaseId, platformMonth, phases, currentIndex, gate);
    }

    /**
     * Applies the E1 release gate to the trade-in side door. Early access is
     * deliberately narrower than ordinary storefront release: it can only
     * relax the release-month check of an otherwise eligible, already-reached
     * generation gate. It never bypasses phase, eligibility, or malformed
     * configuration failures.
     */
    public Decision evaluateTradein(
            String productNo, String unlockPhaseId, boolean earlyAccessEnabled, int earlyAccessLeadDays) {
        GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
        if (rhythm == null || rhythm.currentMonth() < 1) {
            return Decision.closed("H1_RHYTHM_UNAVAILABLE", unlockPhaseId);
        }
        int platformMonth = Math.min(12, rhythm.currentMonth());
        List<DevicePhaseView> phases = catalogRepository.listPhases(E1_SCOPE, false);
        if (phases == null || phases.isEmpty()) {
            return Decision.closed("E1_PHASE_CONFIG_UNAVAILABLE", unlockPhaseId);
        }
        int currentIndex = Math.min(phases.size() - 1, ((platformMonth - 1) * phases.size()) / 12);
        DeviceGenerationGateView gate = StringUtils.hasText(productNo)
                ? catalogRepository.findGenerationGate(productNo).orElse(null)
                : null;
        Decision ordinary = evaluateWithContext(unlockPhaseId, platformMonth, phases, currentIndex, gate);
        if (ordinary.available()
                || !earlyAccessEnabled
                || gate == null
                || !"E1_GENERATION_RELEASE_MONTH_NOT_REACHED".equals(ordinary.reason())) {
            return ordinary;
        }

        int releaseMonth = gate.releaseMonth() == null ? 0 : gate.releaseMonth();
        int phaseOffset = gate.phaseOffset() == null ? 0 : gate.phaseOffset();
        int releaseAtMonth = releaseMonth + phaseOffset;
        int elapsedDaysInMonth = Math.floorDiv(Math.max(0, Math.min(100, rhythm.phaseProgressPct())) * 30, 100);
        int remainingDays = Math.max(0, (releaseAtMonth - platformMonth) * 30 - elapsedDaysInMonth);
        return remainingDays > 0 && remainingDays <= earlyAccessLeadDays
                ? Decision.openEarly(unlockPhaseId)
                : ordinary;
    }

    /**
     * Evaluates a storefront candidate set from one rhythm snapshot, one phase
     * snapshot and one generation-gate snapshot. Home/Earn uses this path so a
     * conversion card does not multiply E1 reads by the number of candidates.
     */
    public Map<String, Decision> evaluateBatch(Map<String, String> productUnlockPhases) {
        Map<String, String> candidates = new LinkedHashMap<>();
        if (productUnlockPhases != null) {
            productUnlockPhases.forEach((productNo, unlockPhase) -> {
                if (StringUtils.hasText(productNo)) candidates.put(productNo.trim(), unlockPhase);
            });
        }
        if (candidates.isEmpty()) return Map.of();
        GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
        if (rhythm == null || rhythm.currentMonth() < 1) {
            return closedBatch(candidates, "H1_RHYTHM_UNAVAILABLE");
        }
        int platformMonth = Math.min(12, rhythm.currentMonth());
        List<DevicePhaseView> phases = catalogRepository.listPhases(E1_SCOPE, false);
        if (phases == null || phases.isEmpty()) {
            return closedBatch(candidates, "E1_PHASE_CONFIG_UNAVAILABLE");
        }
        int currentIndex = Math.min(phases.size() - 1, ((platformMonth - 1) * phases.size()) / 12);
        Map<String, DeviceGenerationGateView> gates = new LinkedHashMap<>();
        List<DeviceGenerationGateView> gateRows = catalogRepository.listGenerationGates(false);
        if (gateRows != null) {
            for (DeviceGenerationGateView gate : gateRows) {
                if (gate != null && StringUtils.hasText(gate.id())) gates.putIfAbsent(gate.id().trim(), gate);
            }
        }
        Map<String, Decision> decisions = new LinkedHashMap<>();
        candidates.forEach((productNo, unlockPhase) -> decisions.put(productNo,
                evaluateWithContext(unlockPhase, platformMonth, phases, currentIndex, gates.get(productNo))));
        return decisions;
    }

    private Map<String, Decision> closedBatch(Map<String, String> candidates, String reason) {
        Map<String, Decision> decisions = new LinkedHashMap<>();
        candidates.forEach((productNo, unlockPhase) -> decisions.put(
                productNo, Decision.closed(reason, unlockPhase)));
        return decisions;
    }

    private Decision evaluateWithContext(
            String unlockPhaseId, int platformMonth, List<DevicePhaseView> phases,
            int currentIndex, DeviceGenerationGateView gate) {
        if (gate != null && "active".equalsIgnoreCase(gate.status())) {
            if (!Boolean.TRUE.equals(gate.eligibility())) {
                return Decision.closed("E1_GENERATION_ELIGIBILITY_REQUIRED", unlockPhaseId);
            }
            int gateIndex = phaseIndex(phases, gate.phase());
            if (gateIndex < 0 || gateIndex > currentIndex) {
                return Decision.closed("E1_GENERATION_PHASE_NOT_REACHED", unlockPhaseId);
            }
            if (StringUtils.hasText(unlockPhaseId) && !gate.phase().equals(unlockPhaseId.trim())) {
                return Decision.closed("E1_GENERATION_PHASE_MISMATCH", unlockPhaseId);
            }
            int releaseMonth = gate.releaseMonth() == null ? 0 : gate.releaseMonth();
            int phaseOffset = gate.phaseOffset() == null ? 0 : gate.phaseOffset();
            if (!Boolean.TRUE.equals(gate.forceUnlock()) && platformMonth < releaseMonth + phaseOffset) {
                return Decision.closed("E1_GENERATION_RELEASE_MONTH_NOT_REACHED", unlockPhaseId);
            }
            return Decision.open(unlockPhaseId);
        }

        if (!StringUtils.hasText(unlockPhaseId)) {
            return Decision.open(null);
        }
        int targetIndex = phaseIndex(phases, unlockPhaseId);
        if (targetIndex < 0) {
            return Decision.closed("E1_UNLOCK_PHASE_INVALID", unlockPhaseId);
        }
        return targetIndex <= currentIndex
                ? Decision.open(unlockPhaseId)
                : Decision.closed("E1_PHASE_NOT_REACHED", unlockPhaseId);
    }

    private int phaseIndex(List<DevicePhaseView> phases, String phaseId) {
        if (!StringUtils.hasText(phaseId)) return -1;
        String normalized = phaseId.trim();
        for (int i = 0; i < phases.size(); i++) {
            if (normalized.equals(phases.get(i).p())) return i;
        }
        return -1;
    }

    public record Decision(boolean available, String reason, String releasePhaseId) {
        public static Decision open(String releasePhaseId) {
            return new Decision(true, "AVAILABLE", releasePhaseId);
        }

        public static Decision openEarly(String releasePhaseId) {
            return new Decision(true, "TRADEIN_EARLY_ACCESS_AVAILABLE", releasePhaseId);
        }

        public static Decision closed(String reason, String releasePhaseId) {
            return new Decision(false, reason, releasePhaseId);
        }
    }
}
