package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GrowthRhythmFacadeAdapter implements GrowthRhythmFacade {
    private final PlatformConfigFacade configFacade;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    @Override
    public GrowthRhythmSnapshot snapshot() {
        return GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy);
    }
}
