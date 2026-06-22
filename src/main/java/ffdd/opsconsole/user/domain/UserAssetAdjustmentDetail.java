package ffdd.opsconsole.user.domain;

import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.util.List;

public record UserAssetAdjustmentDetail(
        UserAssetAdjustmentView adjustment,
        UserAccountView user,
        TreasuryCoverageSnapshot coverage,
        List<String> reviewTrail,
        List<String> redlines,
        List<String> sources) {
}
