package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GrowthRhythmFacadeAdapter implements GrowthRhythmFacade {
    private final PlatformConfigFacade configFacade;

    @Override
    public GrowthRhythmSnapshot snapshot() {
        return GrowthRhythmSnapshot.from(configFacade);
    }
}
