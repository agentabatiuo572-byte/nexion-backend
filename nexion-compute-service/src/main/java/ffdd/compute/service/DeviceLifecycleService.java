package ffdd.compute.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.compute.domain.DeviceLifecycleRule;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.DeviceLifecycleResponse;
import ffdd.compute.dto.DeviceLifecycleRuleCreateRequest;
import ffdd.compute.dto.DeviceLifecycleRuleUpdateRequest;
import ffdd.compute.mapper.DeviceLifecycleRuleMapper;
import ffdd.compute.mapper.UserDeviceMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DeviceLifecycleService {
    private static final int ACTIVE = 1;
    private static final BigDecimal DEFAULT_FLOOR = new BigDecimal("0.22");
    private static final Set<String> SCOPE_TYPES = Set.of("DEFAULT", "PRODUCT_TYPE", "TIER", "PRODUCT_ID");

    private final DeviceLifecycleRuleMapper ruleMapper;
    private final UserDeviceMapper userDeviceMapper;
    private final Clock clock;

    @Autowired
    public DeviceLifecycleService(DeviceLifecycleRuleMapper ruleMapper, UserDeviceMapper userDeviceMapper) {
        this(ruleMapper, userDeviceMapper, Clock.systemDefaultZone());
    }

    DeviceLifecycleService(DeviceLifecycleRuleMapper ruleMapper, UserDeviceMapper userDeviceMapper, Clock clock) {
        this.ruleMapper = ruleMapper;
        this.userDeviceMapper = userDeviceMapper;
        this.clock = clock;
    }

    public DeviceLifecycleResponse lifecycle(Long userDeviceId) {
        UserDevice device = userDeviceMapper.selectById(userDeviceId);
        if (device == null || Integer.valueOf(1).equals(device.getIsDeleted())) {
            throw new BizException("Device not found");
        }
        return evaluate(device);
    }

    public DeviceLifecycleResponse evaluate(UserDevice device) {
        if (device == null) {
            throw new BizException("Device is required");
        }
        List<DeviceLifecycleRule> rules = activeRules();
        int monthsOwned = monthsOwned(device);
        BigDecimal dailyUsdt = defaultDecimal(device.getDailyUsdt());
        BigDecimal dailyNex = defaultDecimal(device.getDailyNex());
        if (isExempt(device, rules)) {
            return new DeviceLifecycleResponse(
                    device.getId(),
                    monthsOwned,
                    BigDecimal.ONE.setScale(6, RoundingMode.HALF_UP),
                    scale(dailyUsdt),
                    scale(dailyNex),
                    BigDecimal.ONE.setScale(6, RoundingMode.HALF_UP),
                    true);
        }

        BigDecimal efficiency = BigDecimal.ONE;
        BigDecimal floor = matchingFloor(device, rules);
        for (int month = 1; month <= monthsOwned; month++) {
            DeviceLifecycleRule rule = bestRuleForMonth(device, rules, month);
            if (rule == null || rule.getMonthlyDecayRate() == null) {
                continue;
            }
            efficiency = efficiency.multiply(BigDecimal.ONE.subtract(rule.getMonthlyDecayRate()));
        }
        efficiency = scale(efficiency.max(floor));
        return new DeviceLifecycleResponse(
                device.getId(),
                monthsOwned,
                efficiency,
                scale(dailyUsdt.multiply(efficiency)),
                scale(dailyNex.multiply(efficiency)),
                scale(floor),
                false);
    }

    public List<DeviceLifecycleRule> listRules(Integer status) {
        return ruleMapper.selectList(new LambdaQueryWrapper<DeviceLifecycleRule>()
                .eq(DeviceLifecycleRule::getIsDeleted, 0)
                .eq(status != null, DeviceLifecycleRule::getStatus, status)
                .orderByAsc(DeviceLifecycleRule::getSortOrder)
                .orderByAsc(DeviceLifecycleRule::getId));
    }

    public DeviceLifecycleRule createRule(DeviceLifecycleRuleCreateRequest request) {
        if (request == null) {
            throw new BizException("Device lifecycle rule request is required");
        }
        DeviceLifecycleRule rule = new DeviceLifecycleRule();
        applyRule(rule, request.getScopeType(), request.getScopeValue(), request.getStartMonth(), request.getEndMonth(),
                request.getMonthlyDecayRate(), request.getFloorEfficiency(), request.getExempt(), request.getStatus(),
                request.getSortOrder(), true);
        rule.setIsDeleted(0);
        ruleMapper.insert(rule);
        return rule;
    }

    public DeviceLifecycleRule updateRule(Long id, DeviceLifecycleRuleUpdateRequest request) {
        if (id == null) {
            throw new BizException("Device lifecycle rule id is required");
        }
        if (request == null) {
            throw new BizException("Device lifecycle rule request is required");
        }
        DeviceLifecycleRule rule = ruleMapper.selectById(id);
        if (rule == null || Integer.valueOf(1).equals(rule.getIsDeleted())) {
            throw new BizException(404, "Device lifecycle rule not found");
        }
        applyRule(rule, request.getScopeType(), request.getScopeValue(), request.getStartMonth(), request.getEndMonth(),
                request.getMonthlyDecayRate(), request.getFloorEfficiency(), request.getExempt(), request.getStatus(),
                request.getSortOrder(), false);
        ruleMapper.updateById(rule);
        return rule;
    }

    private void applyRule(
            DeviceLifecycleRule rule,
            String scopeType,
            String scopeValue,
            Integer startMonth,
            Integer endMonth,
            BigDecimal monthlyDecayRate,
            BigDecimal floorEfficiency,
            Integer exempt,
            Integer status,
            Integer sortOrder,
            boolean create) {
        if (create || scopeType != null) {
            rule.setScopeType(normalizeScopeType(scopeType));
        }
        if (create || scopeType != null || scopeValue != null) {
            rule.setScopeValue(normalizeScopeValue(rule.getScopeType(), scopeValue));
        }
        if (create || startMonth != null) {
            rule.setStartMonth(startMonth == null ? 0 : Math.max(0, startMonth));
        }
        if (create || endMonth != null) {
            rule.setEndMonth(endMonth);
        }
        if (rule.getEndMonth() != null && rule.getStartMonth() != null && rule.getEndMonth() < rule.getStartMonth()) {
            throw new BizException("endMonth must be greater than or equal to startMonth");
        }
        if (create || monthlyDecayRate != null) {
            rule.setMonthlyDecayRate(monthlyDecayRate == null ? BigDecimal.ZERO : monthlyDecayRate);
        }
        if (create || floorEfficiency != null) {
            rule.setFloorEfficiency(floorEfficiency == null ? DEFAULT_FLOOR : floorEfficiency);
        }
        if (create || exempt != null) {
            rule.setExempt(exempt == null ? 0 : exempt);
        }
        if (create || status != null) {
            rule.setStatus(status == null ? ACTIVE : status);
        }
        if (create || sortOrder != null) {
            rule.setSortOrder(sortOrder == null ? 0 : sortOrder);
        }
    }

    private List<DeviceLifecycleRule> activeRules() {
        return ruleMapper.selectList(new LambdaQueryWrapper<DeviceLifecycleRule>()
                .eq(DeviceLifecycleRule::getIsDeleted, 0)
                .eq(DeviceLifecycleRule::getStatus, ACTIVE));
    }

    private boolean isExempt(UserDevice device, List<DeviceLifecycleRule> rules) {
        return rules.stream()
                .anyMatch(rule -> Integer.valueOf(1).equals(rule.getExempt()) && matches(device, rule));
    }

    private BigDecimal matchingFloor(UserDevice device, List<DeviceLifecycleRule> rules) {
        return rules.stream()
                .filter(rule -> !Integer.valueOf(1).equals(rule.getExempt()))
                .filter(rule -> matches(device, rule))
                .max(ruleComparator())
                .map(DeviceLifecycleRule::getFloorEfficiency)
                .filter(value -> value != null)
                .orElse(DEFAULT_FLOOR);
    }

    private DeviceLifecycleRule bestRuleForMonth(UserDevice device, List<DeviceLifecycleRule> rules, int month) {
        return rules.stream()
                .filter(rule -> !Integer.valueOf(1).equals(rule.getExempt()))
                .filter(rule -> matches(device, rule))
                .filter(rule -> month >= nullToZero(rule.getStartMonth()))
                .filter(rule -> rule.getEndMonth() == null || month <= rule.getEndMonth())
                .max(ruleComparator())
                .orElse(null);
    }

    private Comparator<DeviceLifecycleRule> ruleComparator() {
        return Comparator
                .comparingInt((DeviceLifecycleRule rule) -> priority(rule.getScopeType()))
                .thenComparingInt(rule -> rule.getSortOrder() == null ? 0 : rule.getSortOrder())
                .thenComparingLong(rule -> rule.getId() == null ? 0L : rule.getId());
    }

    private boolean matches(UserDevice device, DeviceLifecycleRule rule) {
        String scopeType = normalizeScopeType(rule.getScopeType());
        String scopeValue = normalize(rule.getScopeValue());
        return switch (scopeType) {
            case "DEFAULT" -> true;
            case "PRODUCT_TYPE" -> scopeValue.equals(normalize(device.getDeviceType()));
            case "TIER" -> scopeValue.equals(normalize(device.getProductTier()));
            case "PRODUCT_ID" -> device.getProductId() != null && scopeValue.equals(String.valueOf(device.getProductId()));
            default -> false;
        };
    }

    private int priority(String scopeType) {
        return switch (normalizeScopeType(scopeType)) {
            case "PRODUCT_ID" -> 40;
            case "TIER" -> 30;
            case "PRODUCT_TYPE" -> 20;
            default -> 10;
        };
    }

    private int monthsOwned(UserDevice device) {
        LocalDateTime since = device.getPurchasedAt() == null ? device.getActivatedAt() : device.getPurchasedAt();
        if (since == null) {
            return 0;
        }
        return Math.max(0, (int) ChronoUnit.MONTHS.between(since, LocalDateTime.now(clock)));
    }

    private String normalizeScopeType(String scopeType) {
        String normalized = normalize(scopeType);
        if (!StringUtils.hasText(normalized) || !SCOPE_TYPES.contains(normalized)) {
            throw new BizException("Unsupported device lifecycle scope type");
        }
        return normalized;
    }

    private String normalizeScopeValue(String scopeType, String scopeValue) {
        if ("DEFAULT".equals(scopeType)) {
            return null;
        }
        String normalized = normalize(scopeValue);
        if (!StringUtils.hasText(normalized)) {
            throw new BizException("scopeValue is required");
        }
        return normalized;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }
}
