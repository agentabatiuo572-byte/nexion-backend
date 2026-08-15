package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceGenerationGateView;
import ffdd.opsconsole.device.domain.DevicePhaseView;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import java.util.List;
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

        public static Decision closed(String reason, String releasePhaseId) {
            return new Decision(false, reason, releasePhaseId);
        }
    }
}
