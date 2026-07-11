package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.CopyAudiencePhaseProvider;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GrowthContentAudiencePhaseProvider implements CopyAudiencePhaseProvider {
    private final PlatformConfigFacade platformConfigFacade;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    @Override
    public String currentPhase() {
        return GrowthRhythmSnapshot.from(platformConfigFacade, readTimeSeedPolicy).currentPhase();
    }
}
