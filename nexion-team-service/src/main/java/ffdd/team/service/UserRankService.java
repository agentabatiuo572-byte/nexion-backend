package ffdd.team.service;

import ffdd.team.domain.UserLevelConfig;
import ffdd.team.domain.VRankConfig;
import ffdd.team.dto.UserRankResponse;
import java.util.List;

public interface UserRankService {
    List<UserLevelConfig> userLevels();

    List<VRankConfig> vRanks();

    UserRankResponse myRank();
}

