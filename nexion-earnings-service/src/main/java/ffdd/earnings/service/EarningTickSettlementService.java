package ffdd.earnings.service;

import ffdd.common.exception.BizException;
import ffdd.earnings.domain.EarningTickDevice;
import ffdd.earnings.dto.EarningMilestoneRewardResult;
import ffdd.earnings.dto.EarningTickBatchRequest;
import ffdd.earnings.dto.EarningTickBatchResult;
import ffdd.earnings.dto.EarningTickRequest;
import ffdd.earnings.dto.ReceiptSettleRequest;
import ffdd.earnings.mapper.EarningTickDeviceMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EarningTickSettlementService {
    private static final int SCALE = 6;
    private static final int MAX_BATCH_SIZE = 500;
    private static final BigDecimal SECONDS_PER_DAY = new BigDecimal("86400");
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final DateTimeFormatter TICK_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final EarningsService earningsService;
    private final EarningMilestoneRewardService milestoneRewardService;
    private final EarningTickDeviceMapper tickDeviceMapper;
    private final long tickIntervalSeconds;
    private final int defaultBatchSize;

    public EarningTickSettlementService(
            EarningsService earningsService,
            EarningMilestoneRewardService milestoneRewardService,
            EarningTickDeviceMapper tickDeviceMapper,
            @Value("${nexion.earnings.tick.interval-seconds:3600}") long tickIntervalSeconds,
            @Value("${nexion.earnings.tick.batch-size:100}") int defaultBatchSize) {
        this.earningsService = earningsService;
        this.milestoneRewardService = milestoneRewardService;
        this.tickDeviceMapper = tickDeviceMapper;
        this.tickIntervalSeconds = Math.max(60L, Math.min(tickIntervalSeconds, 86400L));
        this.defaultBatchSize = Math.max(1, Math.min(defaultBatchSize, 500));
    }

    @Transactional(rollbackFor = Exception.class)
    public EarningTickBatchResult settleBatch(EarningTickBatchRequest request) {
        if (request == null || request.getTicks() == null || request.getTicks().isEmpty()) {
            throw new BizException("ticks are required");
        }
        if (request.getTicks().size() > MAX_BATCH_SIZE) {
            throw new BizException("tick batch size must be <= " + MAX_BATCH_SIZE);
        }
        EarningTickBatchResult result = new EarningTickBatchResult();
        result.setRequested(request.getTicks().size());
        Set<Long> affectedUserIds = new TreeSet<>();

        for (EarningTickRequest tick : request.getTicks()) {
            ReceiptSettleRequest settleRequest = toSettleRequest(tick);
            earningsService.settleReceipt(settleRequest);
            result.setSettled(result.getSettled() + 1);
            result.getReceiptNos().add(settleRequest.getReceiptNo());
            affectedUserIds.add(settleRequest.getUserId());
        }

        if (request.isSettleMilestones() && !affectedUserIds.isEmpty()) {
            EarningMilestoneRewardResult milestoneResult =
                    milestoneRewardService.scanAndReward(new ArrayList<>(affectedUserIds));
            result.setMilestoneRewards(milestoneResult.getRewarded());
            result.getMilestoneEventNos().addAll(milestoneResult.getEventNos());
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public EarningTickBatchResult settleDeviceTicks(LocalDateTime tickAt, Integer limit) {
        int batchSize = normalizeLimit(limit);
        LocalDateTime effectiveTickAt = tickAt == null ? LocalDateTime.now() : tickAt;
        List<EarningTickDevice> devices = tickDeviceMapper.selectTickableDevices(batchSize);
        List<EarningTickRequest> ticks = new ArrayList<>();
        EarningTickBatchResult emptyResult = new EarningTickBatchResult();

        for (EarningTickDevice device : devices) {
            EarningTickRequest tick = toDeviceTickRequest(device, effectiveTickAt);
            if (tick == null) {
                emptyResult.setSkipped(emptyResult.getSkipped() + 1);
                emptyResult.getSkippedReasons().add("device " + (device == null ? null : device.getId()) + " has no positive reward");
            } else {
                ticks.add(tick);
            }
        }
        if (ticks.isEmpty()) {
            emptyResult.setRequested(devices.size());
            return emptyResult;
        }

        EarningTickBatchRequest request = new EarningTickBatchRequest();
        request.setTicks(ticks);
        request.setSettleMilestones(true);
        EarningTickBatchResult result = settleBatch(request);
        result.setRequested(devices.size());
        result.setSkipped(result.getSkipped() + emptyResult.getSkipped());
        result.getSkippedReasons().addAll(emptyResult.getSkippedReasons());
        return result;
    }

    private ReceiptSettleRequest toSettleRequest(EarningTickRequest tick) {
        if (tick == null || tick.getUserId() == null || tick.getUserId() < 1) {
            throw new BizException("tick userId is required");
        }
        if (tick.getUserDeviceId() != null && tick.getUserDeviceId() < 1) {
            throw new BizException("tick userDeviceId must be positive");
        }
        BigDecimal rewardUsdt = scaled(tick.getRewardUsdt());
        BigDecimal rewardNex = scaled(tick.getRewardNex());
        if (rewardUsdt.compareTo(BigDecimal.ZERO) < 0 || rewardNex.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("tick rewards must be non-negative");
        }
        if (rewardUsdt.compareTo(BigDecimal.ZERO) == 0 && rewardNex.compareTo(BigDecimal.ZERO) == 0) {
            throw new BizException("tick must include a positive reward");
        }

        ReceiptSettleRequest request = new ReceiptSettleRequest();
        request.setUserId(tick.getUserId());
        request.setUserDeviceId(tick.getUserDeviceId());
        request.setReceiptNo(resolveTickNo(tick));
        request.setRewardUsdt(rewardUsdt);
        request.setRewardNex(rewardNex);
        request.setCompletedAt(tick.getTickAt() == null ? LocalDateTime.now() : tick.getTickAt());
        return request;
    }

    private String resolveTickNo(EarningTickRequest tick) {
        if (StringUtils.hasText(tick.getTickNo())) {
            String tickNo = tick.getTickNo().trim();
            if (tickNo.length() > 96) {
                throw new BizException("tickNo length must be <= 96");
            }
            return tickNo;
        }
        LocalDateTime tickAt = tick.getTickAt() == null ? LocalDateTime.now() : tick.getTickAt();
        String owner = tick.getUserDeviceId() == null ? "U" + tick.getUserId() : "D" + tick.getUserDeviceId();
        return "TICK-" + tickAt.format(TICK_NO_FORMATTER) + "-" + owner;
    }

    private EarningTickRequest toDeviceTickRequest(EarningTickDevice device, LocalDateTime tickAt) {
        if (device == null || device.getId() == null || device.getUserId() == null) {
            return null;
        }
        LocalDateTime slotStart = floorToInterval(tickAt);
        LocalDateTime slotEnd = slotStart.plusSeconds(tickIntervalSeconds);
        BigDecimal rewardUsdt = prorate(device.getDailyUsdt());
        BigDecimal rewardNex = prorate(device.getDailyNex());
        if (rewardUsdt.compareTo(BigDecimal.ZERO) == 0 && rewardNex.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        EarningTickRequest tick = new EarningTickRequest();
        tick.setTickNo("TICK-" + slotStart.format(TICK_NO_FORMATTER) + "-" + device.getId());
        tick.setUserId(device.getUserId());
        tick.setUserDeviceId(device.getId());
        tick.setRewardUsdt(rewardUsdt);
        tick.setRewardNex(rewardNex);
        tick.setTickAt(slotEnd);
        tick.setSource("DEVICE_SNAPSHOT");
        return tick;
    }

    private LocalDateTime floorToInterval(LocalDateTime time) {
        long seconds = Duration.between(EPOCH, time).getSeconds();
        long flooredSeconds = Math.floorDiv(seconds, tickIntervalSeconds) * tickIntervalSeconds;
        return EPOCH.plusSeconds(flooredSeconds);
    }

    private BigDecimal prorate(BigDecimal dailyAmount) {
        return scaled(dailyAmount)
                .multiply(new BigDecimal(tickIntervalSeconds))
                .divide(SECONDS_PER_DAY, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal scaled(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return defaultBatchSize;
        }
        return Math.min(limit, MAX_BATCH_SIZE);
    }
}
