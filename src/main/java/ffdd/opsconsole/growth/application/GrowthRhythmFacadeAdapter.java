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

    @Override
    public GrowthRhythmSnapshot snapshotAtMonth(int month) {
        return GrowthRhythmSnapshot.fromMonth(configFacade, readTimeSeedPolicy, month);
    }

    @Override
    public String phaseForMonth(int month) {
        GrowthRhythmSnapshot current = snapshot();
        if (current.totalMonths() < 1 || month < 1) {
            return "";
        }
        return GrowthRhythmSnapshot.phaseForRhythmMonth(
                Math.min(month, current.totalMonths()),
                current.totalMonths());
    }
}
