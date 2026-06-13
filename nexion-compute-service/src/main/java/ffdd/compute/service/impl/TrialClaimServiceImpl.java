package ffdd.compute.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.api.ApiResult;
import ffdd.common.exception.BizException;
import ffdd.compute.client.SystemConfigClient;
import ffdd.compute.domain.TrialClaim;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.DeviceActivateRequest;
import ffdd.compute.dto.TrialClaimResponse;
import ffdd.compute.mapper.TrialClaimMapper;
import ffdd.compute.service.ComputeService;
import ffdd.compute.service.TrialClaimService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TrialClaimServiceImpl implements TrialClaimService {
    private static final String STATUS_CLAIMED = "CLAIMED";

    private final TrialClaimMapper trialClaimMapper;
    private final ComputeService computeService;
    private final SystemConfigClient systemConfigClient;

    public TrialClaimServiceImpl(
            TrialClaimMapper trialClaimMapper,
            ComputeService computeService,
            SystemConfigClient systemConfigClient) {
        this.trialClaimMapper = trialClaimMapper;
        this.computeService = computeService;
        this.systemConfigClient = systemConfigClient;
    }

    @Override
    public TrialClaimResponse current(Long userId) {
        return TrialClaimResponse.from(findLatest(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TrialClaimResponse claim(Long userId, String clientRequestNo) {
        if (userId == null) {
            throw new BizException("Authenticated user id is required");
        }
        TrialClaim existing = findLatest(userId);
        if (existing != null) {
            return TrialClaimResponse.from(existing);
        }

        Map<String, Object> config = growthConfig();
        if (!boolValue(config.get("trial.enabled"), true) || !boolValue(config.get("trial.claim_enabled"), false)) {
            throw new BizException("Trial claim is not open");
        }
        int seatsLeft = Math.max(0, intValue(config.get("trial.seats_left_today"), 0));
        if (seatsLeft <= 0) {
            throw new BizException("No trial seats left today");
        }

        LocalDateTime now = LocalDateTime.now();
        int durationDays = Math.max(1, intValue(config.get("trial.duration_days"), 3));
        String deviceName = textValue(config.get("trial.device_name"), "NexionBox S1");
        BigDecimal dailyUsdt = decimalValue(config.get("trial.daily_usdt"), "0");
        BigDecimal dailyNex = decimalValue(config.get("trial.daily_nex"), "0");
        BigDecimal offsetCapUsdt = decimalValue(config.get("trial.offset_cap_usdt"), "0");
        BigDecimal priceUsdt = decimalValue(config.get("trial.price_usdt"), "0");

        TrialClaim claim = new TrialClaim();
        claim.setUserId(userId);
        claim.setClaimNo(nextClaimNo());
        claim.setClientRequestNo(trimToNull(clientRequestNo));
        claim.setStatus(STATUS_CLAIMED);
        claim.setDeviceName(deviceName);
        claim.setDurationDays(durationDays);
        claim.setDailyUsdt(dailyUsdt);
        claim.setDailyNex(dailyNex);
        claim.setSeatsLeftToday(seatsLeft);
        claim.setOffsetCapUsdt(offsetCapUsdt);
        claim.setPriceUsdt(priceUsdt);
        claim.setClaimedAt(now);
        claim.setExpiresAt(now.plusDays(durationDays));
        claim.setQuotaSnapshot(snapshot(durationDays, dailyUsdt, dailyNex, seatsLeft, offsetCapUsdt, priceUsdt));
        claim.setIsDeleted(0);
        trialClaimMapper.insert(claim);

        List<UserDevice> devices = computeService.activateDevices(toActivateRequest(claim));
        Long userDeviceId = devices.isEmpty() ? null : devices.get(0).getId();
        if (userDeviceId != null) {
            claim.setUserDeviceId(userDeviceId);
            trialClaimMapper.updateById(claim);
        }
        return TrialClaimResponse.from(claim);
    }

    private TrialClaim findLatest(Long userId) {
        if (userId == null) {
            return null;
        }
        return trialClaimMapper.selectOne(new LambdaQueryWrapper<TrialClaim>()
                .eq(TrialClaim::getUserId, userId)
                .eq(TrialClaim::getIsDeleted, 0)
                .orderByDesc(TrialClaim::getCreatedAt)
                .last("LIMIT 1"));
    }

    private Map<String, Object> growthConfig() {
        ApiResult<Map<String, Object>> result = systemConfigClient.growth();
        if (result == null || result.getData() == null) {
            return Map.of();
        }
        return result.getData();
    }

    private DeviceActivateRequest toActivateRequest(TrialClaim claim) {
        DeviceActivateRequest request = new DeviceActivateRequest();
        request.setUserId(claim.getUserId());
        request.setSourceOrderNo(claim.getClaimNo());
        request.setProductCode("trial-device");
        request.setProductTier("TRIAL");
        request.setProductName(claim.getDeviceName());
        request.setDeviceType("TRIAL_DEVICE");
        request.setGeneration(1);
        request.setPriceUsdtSnapshot(claim.getPriceUsdt());
        request.setOwnershipStatus("TRIAL");
        request.setSourceChannel("TRIAL");
        request.setDailyUsdt(claim.getDailyUsdt());
        request.setDailyNex(claim.getDailyNex());
        request.setQuantity(1);
        return request;
    }

    private static String nextClaimNo() {
        String day = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        return "TRIAL-" + day + "-" + suffix;
    }

    private static String snapshot(
            int durationDays,
            BigDecimal dailyUsdt,
            BigDecimal dailyNex,
            int seatsLeft,
            BigDecimal offsetCapUsdt,
            BigDecimal priceUsdt) {
        return "durationDays=" + durationDays
                + ";dailyUsdt=" + dailyUsdt
                + ";dailyNex=" + dailyNex
                + ";seatsLeftToday=" + seatsLeft
                + ";offsetCapUsdt=" + offsetCapUsdt
                + ";priceUsdt=" + priceUsdt;
    }

    private static boolean boolValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        if (List.of("true", "1", "yes", "on").contains(text)) {
            return true;
        }
        if (List.of("false", "0", "no", "off").contains(text)) {
            return false;
        }
        return fallback;
    }

    private static int intValue(Object value, int fallback) {
        try {
            return value == null ? fallback : new BigDecimal(String.valueOf(value)).intValue();
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static BigDecimal decimalValue(Object value, String fallback) {
        try {
            return new BigDecimal(String.valueOf(value == null ? fallback : value));
        } catch (NumberFormatException ignored) {
            return new BigDecimal(fallback);
        }
    }

    private static String textValue(Object value, String fallback) {
        String text = value == null ? null : String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : fallback;
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
