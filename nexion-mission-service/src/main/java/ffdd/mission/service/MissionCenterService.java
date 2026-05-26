package ffdd.mission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.mission.domain.DailyCheckIn;
import ffdd.mission.domain.Mission;
import ffdd.mission.domain.PointsLedger;
import ffdd.mission.domain.UserMission;
import ffdd.mission.dto.DailyCheckInResponse;
import ffdd.mission.dto.MissionItemResponse;
import ffdd.mission.dto.MissionListResponse;
import ffdd.mission.dto.PointsSummaryResponse;
import ffdd.mission.mapper.DailyCheckInMapper;
import ffdd.mission.mapper.MissionMapper;
import ffdd.mission.mapper.PointsLedgerMapper;
import ffdd.mission.mapper.UserMissionMapper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MissionCenterService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final String MISSION_DAILY_CHECK_IN = "DAILY_CHECK_IN";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_ALREADY_CHECKED_IN = "ALREADY_CHECKED_IN";
    private static final String STATUS_PENDING = "PENDING";
    private static final String BIZ_TYPE_DAILY_CHECK_IN = "DAILY_CHECK_IN";

    private final MissionMapper missionMapper;
    private final UserMissionMapper userMissionMapper;
    private final PointsLedgerMapper pointsLedgerMapper;
    private final DailyCheckInMapper dailyCheckInMapper;

    public MissionCenterService(
            MissionMapper missionMapper,
            UserMissionMapper userMissionMapper,
            PointsLedgerMapper pointsLedgerMapper,
            DailyCheckInMapper dailyCheckInMapper) {
        this.missionMapper = missionMapper;
        this.userMissionMapper = userMissionMapper;
        this.pointsLedgerMapper = pointsLedgerMapper;
        this.dailyCheckInMapper = dailyCheckInMapper;
    }

    public MissionListResponse listMissions(Long userId) {
        requireUserId(userId);
        LocalDate today = LocalDate.now();
        DailyCheckIn todayCheckIn = findDailyCheckIn(userId, today);
        Map<Long, UserMission> completions = userCompletions(userId).stream()
                .collect(Collectors.toMap(UserMission::getMissionId, Function.identity(), (left, right) -> left));
        List<MissionItemResponse> records = activeMissions().stream()
                .map(mission -> toMissionItem(mission, completions.get(mission.getId()), todayCheckIn))
                .toList();
        long completedCount = records.stream().filter(MissionItemResponse::isCompleted).count();
        return new MissionListResponse(userId, records.size(), completedCount, todayCheckIn != null, records);
    }

    @Transactional(rollbackFor = Exception.class)
    public DailyCheckInResponse dailyCheckIn(Long userId) {
        requireUserId(userId);
        LocalDate today = LocalDate.now();
        Mission mission = requireDailyCheckInMission();
        DailyCheckIn existing = findDailyCheckIn(userId, today);
        int currentTotalPoints = totalPoints(userId);
        if (existing != null) {
            return new DailyCheckInResponse(userId, today, false, 0, currentTotalPoints, STATUS_ALREADY_CHECKED_IN);
        }

        int rewardPoints = rewardPoints(mission);
        DailyCheckIn checkIn = new DailyCheckIn();
        checkIn.setUserId(userId);
        checkIn.setMissionId(mission.getId());
        checkIn.setCheckInDate(today);
        checkIn.setRewardPoints(rewardPoints);
        checkIn.setIsDeleted(0);
        try {
            dailyCheckInMapper.insert(checkIn);
        } catch (DuplicateKeyException ex) {
            return new DailyCheckInResponse(
                    userId, today, false, 0, totalPoints(userId), STATUS_ALREADY_CHECKED_IN);
        }

        int balanceAfter = currentTotalPoints + rewardPoints;
        insertPointsLedgerIfNeeded(userId, dailyCheckInBizNo(userId, today), rewardPoints, balanceAfter);
        return new DailyCheckInResponse(userId, today, true, rewardPoints, balanceAfter, STATUS_COMPLETED);
    }

    public PointsSummaryResponse pointsSummary(Long userId, long pageNum, long pageSize) {
        requireUserId(userId);
        long normalizedPageNum = Math.max(1, pageNum);
        long normalizedPageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        Page<PointsLedger> page = pointsLedgerMapper.selectPage(
                new Page<>(normalizedPageNum, normalizedPageSize),
                new LambdaQueryWrapper<PointsLedger>()
                        .eq(PointsLedger::getUserId, userId)
                        .eq(PointsLedger::getIsDeleted, 0)
                        .orderByDesc(PointsLedger::getCreatedAt)
                        .orderByDesc(PointsLedger::getId));
        PageResult<PointsLedger> recentLedgers =
                new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
        return new PointsSummaryResponse(userId, totalPoints(userId), recentLedgers);
    }

    private List<Mission> activeMissions() {
        return missionMapper.selectList(new LambdaQueryWrapper<Mission>()
                .eq(Mission::getStatus, 1)
                .eq(Mission::getIsDeleted, 0)
                .orderByAsc(Mission::getId));
    }

    private List<UserMission> userCompletions(Long userId) {
        return userMissionMapper.selectList(new LambdaQueryWrapper<UserMission>()
                .eq(UserMission::getUserId, userId)
                .eq(UserMission::getIsDeleted, 0));
    }

    private MissionItemResponse toMissionItem(
            Mission mission,
            UserMission completion,
            DailyCheckIn todayCheckIn) {
        boolean dailyMission = MISSION_DAILY_CHECK_IN.equals(mission.getMissionCode());
        boolean completed = dailyMission ? todayCheckIn != null : isCompleted(completion);
        return new MissionItemResponse(
                mission.getId(),
                mission.getMissionCode(),
                mission.getMissionName(),
                mission.getMissionType(),
                rewardPoints(mission),
                completed,
                completed ? STATUS_COMPLETED : STATUS_PENDING,
                dailyMission || completion == null ? null : completion.getCompletedAt());
    }

    private Mission requireDailyCheckInMission() {
        Mission mission = missionMapper.selectOne(new LambdaQueryWrapper<Mission>()
                .eq(Mission::getMissionCode, MISSION_DAILY_CHECK_IN)
                .eq(Mission::getStatus, 1)
                .eq(Mission::getIsDeleted, 0));
        if (mission == null) {
            throw new BizException("Daily check-in mission is not configured");
        }
        return mission;
    }

    private DailyCheckIn findDailyCheckIn(Long userId, LocalDate date) {
        return dailyCheckInMapper.selectOne(new LambdaQueryWrapper<DailyCheckIn>()
                .eq(DailyCheckIn::getUserId, userId)
                .eq(DailyCheckIn::getCheckInDate, date)
                .eq(DailyCheckIn::getIsDeleted, 0));
    }

    private void insertPointsLedgerIfNeeded(Long userId, String bizNo, int points, int balanceAfter) {
        if (points <= 0) {
            return;
        }
        PointsLedger existing = pointsLedgerMapper.selectOne(new LambdaQueryWrapper<PointsLedger>()
                .eq(PointsLedger::getBizNo, bizNo)
                .eq(PointsLedger::getIsDeleted, 0));
        if (existing != null) {
            return;
        }
        PointsLedger ledger = new PointsLedger();
        ledger.setUserId(userId);
        ledger.setBizNo(bizNo);
        ledger.setBizType(BIZ_TYPE_DAILY_CHECK_IN);
        ledger.setPoints(points);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setIsDeleted(0);
        try {
            pointsLedgerMapper.insert(ledger);
        } catch (DuplicateKeyException ignored) {
            // A duplicate ledger means another request won the same user/day idempotency race.
        }
    }

    private boolean isCompleted(UserMission completion) {
        return completion != null && STATUS_COMPLETED.equals(completion.getMissionStatus());
    }

    private String dailyCheckInBizNo(Long userId, LocalDate date) {
        return "CHECKIN-DAILY-" + userId + "-" + DateTimeFormatter.BASIC_ISO_DATE.format(date);
    }

    private int totalPoints(Long userId) {
        Integer total = pointsLedgerMapper.sumPointsByUser(userId);
        return total == null ? 0 : total;
    }

    private int rewardPoints(Mission mission) {
        return mission.getRewardPoints() == null ? 0 : mission.getRewardPoints();
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId < 1) {
            throw new BizException("User id is required");
        }
    }
}
