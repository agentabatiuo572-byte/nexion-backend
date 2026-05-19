package ffdd.team.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.team.domain.UserLevelConfig;
import ffdd.team.domain.UserRankSnapshot;
import ffdd.team.domain.VRankConfig;
import ffdd.team.dto.UserRankResponse;
import ffdd.team.mapper.UserLevelConfigMapper;
import ffdd.team.mapper.UserRankSnapshotMapper;
import ffdd.team.mapper.VRankConfigMapper;
import ffdd.team.service.UserRankService;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserRankServiceImpl implements UserRankService {
    private static final Long DEV_USER_ID = 10001L;

    private final UserLevelConfigMapper userLevelConfigMapper;
    private final VRankConfigMapper vRankConfigMapper;
    private final UserRankSnapshotMapper userRankSnapshotMapper;

    @Override
    public List<UserLevelConfig> userLevels() {
        return userLevelConfigMapper.selectList(new LambdaQueryWrapper<UserLevelConfig>()
                .eq(UserLevelConfig::getStatus, 1)
                .orderByAsc(UserLevelConfig::getSortOrder));
    }

    @Override
    public List<VRankConfig> vRanks() {
        return vRankConfigMapper.selectList(new LambdaQueryWrapper<VRankConfig>()
                .eq(VRankConfig::getStatus, 1)
                .orderByAsc(VRankConfig::getSortOrder));
    }

    @Override
    public UserRankResponse myRank() {
        UserRankSnapshot user = userRankSnapshotMapper.selectById(DEV_USER_ID);
        if (user == null) {
            throw new BizException("User does not exist");
        }
        String currentVRank = user.getVRank() == null ? "V0" : user.getVRank();
        String nextVRank = "V" + (Integer.parseInt(currentVRank.substring(1)) + 1);
        return new UserRankResponse(user.getUserLevel(), currentVRank, nextVRank, BigDecimal.ZERO,
                List.of(), userLevels(), vRanks());
    }
}
