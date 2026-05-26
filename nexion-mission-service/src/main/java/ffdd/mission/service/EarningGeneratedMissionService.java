package ffdd.mission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.mission.domain.Mission;
import ffdd.mission.domain.PointsLedger;
import ffdd.mission.domain.UserMission;
import ffdd.mission.dto.EarningGeneratedPayload;
import ffdd.mission.dto.MissionConsumeResult;
import ffdd.mission.mapper.MissionMapper;
import ffdd.mission.mapper.PointsLedgerMapper;
import ffdd.mission.mapper.UserMissionMapper;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EarningGeneratedMissionService {
    private static final String MISSION_FIRST_RECEIPT = "FIRST_RECEIPT";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String BIZ_TYPE_MISSION = "MISSION";

    private final MissionMapper missionMapper;
    private final UserMissionMapper userMissionMapper;
    private final PointsLedgerMapper pointsLedgerMapper;

    public EarningGeneratedMissionService(
            MissionMapper missionMapper,
            UserMissionMapper userMissionMapper,
            PointsLedgerMapper pointsLedgerMapper) {
        this.missionMapper = missionMapper;
        this.userMissionMapper = userMissionMapper;
        this.pointsLedgerMapper = pointsLedgerMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public MissionConsumeResult consume(EarningGeneratedPayload payload) {
        validate(payload);
        Mission mission = findFirstReceiptMission();
        if (mission == null) {
            return new MissionConsumeResult(false, 0, "MISSION_NOT_CONFIGURED");
        }
        if (findUserMission(payload.getUserId(), mission.getId()) != null) {
            return new MissionConsumeResult(false, 0, "ALREADY_COMPLETED");
        }

        UserMission userMission = new UserMission();
        userMission.setUserId(payload.getUserId());
        userMission.setMissionId(mission.getId());
        userMission.setMissionStatus(STATUS_COMPLETED);
        userMission.setCompletedAt(LocalDateTime.now());
        userMission.setIsDeleted(0);
        try {
            userMissionMapper.insert(userMission);
        } catch (DuplicateKeyException ex) {
            return new MissionConsumeResult(false, 0, "ALREADY_COMPLETED");
        }

        int points = rewardPoints(mission);
        insertPointsLedgerIfNeeded(payload.getUserId(), mission.getMissionCode(), points);
        return new MissionConsumeResult(true, points, "COMPLETED");
    }

    private Mission findFirstReceiptMission() {
        return missionMapper.selectOne(new LambdaQueryWrapper<Mission>()
                .eq(Mission::getMissionCode, MISSION_FIRST_RECEIPT)
                .eq(Mission::getStatus, 1)
                .eq(Mission::getIsDeleted, 0));
    }

    private UserMission findUserMission(Long userId, Long missionId) {
        return userMissionMapper.selectOne(new LambdaQueryWrapper<UserMission>()
                .eq(UserMission::getUserId, userId)
                .eq(UserMission::getMissionId, missionId)
                .eq(UserMission::getIsDeleted, 0));
    }

    private void insertPointsLedgerIfNeeded(Long userId, String missionCode, int points) {
        if (points <= 0) {
            return;
        }
        String bizNo = pointsBizNo(missionCode, userId);
        PointsLedger existing = pointsLedgerMapper.selectOne(new LambdaQueryWrapper<PointsLedger>()
                .eq(PointsLedger::getBizNo, bizNo)
                .eq(PointsLedger::getIsDeleted, 0));
        if (existing != null) {
            return;
        }
        PointsLedger ledger = new PointsLedger();
        ledger.setUserId(userId);
        ledger.setBizNo(bizNo);
        ledger.setBizType(BIZ_TYPE_MISSION);
        ledger.setPoints(points);
        ledger.setBalanceAfter(totalPoints(userId) + points);
        ledger.setIsDeleted(0);
        try {
            pointsLedgerMapper.insert(ledger);
        } catch (DuplicateKeyException ignored) {
            // The mission completion is already idempotent; a duplicate ledger means another consumer won the race.
        }
    }

    private String pointsBizNo(String missionCode, Long userId) {
        return "MISSION-" + missionCode + "-" + userId;
    }

    private int rewardPoints(Mission mission) {
        return mission.getRewardPoints() == null ? 0 : mission.getRewardPoints();
    }

    private int totalPoints(Long userId) {
        Integer total = pointsLedgerMapper.sumPointsByUser(userId);
        return total == null ? 0 : total;
    }

    private void validate(EarningGeneratedPayload payload) {
        if (payload == null) {
            throw new BizException("EarningGenerated payload is required");
        }
        if (!StringUtils.hasText(payload.getEventNo())) {
            throw new BizException("EarningGenerated eventNo is required");
        }
        if (payload.getUserId() == null) {
            throw new BizException("EarningGenerated userId is required");
        }
    }
}
