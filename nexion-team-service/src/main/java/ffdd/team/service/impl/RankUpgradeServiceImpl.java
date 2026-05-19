package ffdd.team.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.team.domain.UserLevelLog;
import ffdd.team.domain.UserRankSnapshot;
import ffdd.team.domain.VRankConfig;
import ffdd.team.dto.RankTriggerRequest;
import ffdd.team.dto.RankUpgradeResult;
import ffdd.team.mapper.UserLevelLogMapper;
import ffdd.team.mapper.UserRankSnapshotMapper;
import ffdd.team.mapper.VRankConfigMapper;
import ffdd.team.service.RankUpgradeService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RankUpgradeServiceImpl implements RankUpgradeService {
    private final UserRankSnapshotMapper userMapper;
    private final VRankConfigMapper vRankConfigMapper;
    private final UserLevelLogMapper levelLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RankUpgradeResult evaluateAndUpgrade(RankTriggerRequest request) {
        UserRankSnapshot user = userMapper.selectById(request.getUserId());
        if (user == null) {
            throw new BizException("User does not exist");
        }

        String oldLevel = normalizeLevel(user.getUserLevel());
        String oldRank = normalizeRank(user.getVRank());
        List<String> reasons = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        String targetLevel = evaluateUserLevel(request, reasons);
        String targetRank = evaluateVRank(request, reasons, missing);

        boolean levelUpgraded = levelIndex(targetLevel) > levelIndex(oldLevel);
        boolean rankUpgraded = rankIndex(targetRank) > rankIndex(oldRank);

        if (levelUpgraded) {
            user.setUserLevel(targetLevel);
            writeLog(user.getId(), "USER_LEVEL", oldLevel, targetLevel, String.join("; ", reasons));
        }
        if (rankUpgraded) {
            user.setVRank(targetRank);
            writeLog(user.getId(), "V_RANK", oldRank, targetRank, String.join("; ", reasons));
        }
        if (levelUpgraded || rankUpgraded) {
            userMapper.updateById(user);
        }

        return new RankUpgradeResult(user.getId(), request.getEventType(), oldLevel, user.getUserLevel(),
                oldRank, user.getVRank(), levelUpgraded, rankUpgraded, reasons, missing);
    }

    private String evaluateUserLevel(RankTriggerRequest request, List<String> reasons) {
        String target = "L0";
        if (Boolean.TRUE.equals(request.getRegistered()) && Boolean.TRUE.equals(request.getPhoneComputeConnected())) {
            target = "L1";
            reasons.add("L1: registered and phone compute connected");
        }
        if (gte(request.getLifetimeEarnedUsdt(), new BigDecimal("5"))) {
            target = "L2";
            reasons.add("L2: lifetime earnings >= 5 USDT");
        }
        if (Boolean.TRUE.equals(request.getViewedStore())) {
            target = "L3";
            reasons.add("L3: viewed device store");
        }
        if (safeInt(request.getPurchasedDeviceCount()) >= 1 || gte(request.getSelfBuyUsd(), new BigDecimal("299"))) {
            target = "L4";
            reasons.add("L4: purchased NexionBox or self buy >= 299 USDT");
        }
        if (safeInt(request.getDirectRefs()) >= 3) {
            target = "L5";
            reasons.add("L5: direct referrals >= 3");
        }
        return target;
    }

    private String evaluateVRank(RankTriggerRequest request, List<String> reasons, List<String> missing) {
        List<VRankConfig> configs = vRankConfigMapper.selectList(new LambdaQueryWrapper<VRankConfig>()
                .eq(VRankConfig::getStatus, 1)
                .orderByDesc(VRankConfig::getSortOrder));
        if (configs.isEmpty()) {
            return "V0";
        }
        configs.sort(Comparator.comparing(VRankConfig::getSortOrder).reversed());
        for (VRankConfig config : configs) {
            if (matchVRank(config, request, missing)) {
                reasons.add(config.getRankCode() + ": matched V rank conditions");
                return config.getRankCode();
            }
        }
        return "V0";
    }

    private boolean matchVRank(VRankConfig config, RankTriggerRequest request, List<String> missing) {
        if (!gte(request.getSelfBuyUsd(), config.getSelfBuyUsd())) {
            missing.add(config.getRankCode() + " self buy needs " + config.getSelfBuyUsd());
            return false;
        }
        if (safeInt(request.getDirectRefs()) < safeInt(config.getDirectRefs())) {
            missing.add(config.getRankCode() + " direct referrals needs " + config.getDirectRefs());
            return false;
        }
        if (!gte(request.getTeamVolumeUsd(), config.getTeamVolumeUsd())) {
            missing.add(config.getRankCode() + " team volume needs " + config.getTeamVolumeUsd());
            return false;
        }
        String requiredRank = config.getRequiredDownlineRank();
        Integer requiredCount = config.getRequiredDownlineCount();
        if (requiredRank != null && !requiredRank.isBlank() && safeInt(requiredCount) > 0) {
            int actual = request.getDownlineRankCounts().getOrDefault(requiredRank, 0);
            if (actual < requiredCount) {
                missing.add(config.getRankCode() + " needs " + requiredCount + " downlines at " + requiredRank);
                return false;
            }
        }
        return true;
    }

    private void writeLog(Long userId, String type, String from, String to, String reason) {
        UserLevelLog log = new UserLevelLog();
        log.setUserId(userId);
        log.setLevelType(type);
        log.setFromCode(from);
        log.setToCode(to);
        log.setReason(reason);
        log.setIsDeleted(0);
        levelLogMapper.insert(log);
    }

    private boolean gte(BigDecimal actual, BigDecimal required) {
        BigDecimal safeActual = actual == null ? BigDecimal.ZERO : actual;
        BigDecimal safeRequired = required == null ? BigDecimal.ZERO : required;
        return safeActual.compareTo(safeRequired) >= 0;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String normalizeLevel(String level) {
        return level == null || level.isBlank() ? "L0" : level;
    }

    private String normalizeRank(String rank) {
        return rank == null || rank.isBlank() ? "V0" : rank;
    }

    private int levelIndex(String level) {
        return Integer.parseInt(level.substring(1));
    }

    private int rankIndex(String rank) {
        return Integer.parseInt(rank.substring(1));
    }
}

