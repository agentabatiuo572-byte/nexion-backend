package ffdd.team.service;

import ffdd.team.dto.RankTriggerRequest;
import ffdd.team.dto.RankUpgradeResult;

public interface RankUpgradeService {
    RankUpgradeResult evaluateAndUpgrade(RankTriggerRequest request);
}

