package ffdd.commerce.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.commerce.client.ComputeClient;
import ffdd.commerce.domain.Product;
import ffdd.commerce.domain.TradeinApplication;
import ffdd.commerce.dto.TradeinApplicationQueryRequest;
import ffdd.commerce.dto.TradeinQuoteRequest;
import ffdd.commerce.dto.TradeinQuoteResponse;
import ffdd.commerce.dto.TradeinSubmitRequest;
import ffdd.commerce.mapper.ProductMapper;
import ffdd.commerce.mapper.TradeinApplicationMapper;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TradeinService {
    private static final String PRODUCT_ON_SALE = "ON_SALE";
    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final BigDecimal SALVAGE_RECOVERY_RATE = new BigDecimal("0.30");
    private static final BigDecimal EFFICIENCY_FLOOR = new BigDecimal("0.22");
    private static final BigDecimal MONTH_1_TO_3_FACTOR = new BigDecimal("0.96");
    private static final BigDecimal MONTH_4_TO_8_FACTOR = new BigDecimal("0.94");
    private static final BigDecimal MONTH_9_PLUS_FACTOR = new BigDecimal("0.90");

    private final ProductMapper productMapper;
    private final TradeinApplicationMapper applicationMapper;
    private final ComputeClient computeClient;
    private final Clock clock;

    public TradeinService(
            ProductMapper productMapper,
            TradeinApplicationMapper applicationMapper,
            ComputeClient computeClient) {
        this(productMapper, applicationMapper, computeClient, Clock.systemDefaultZone());
    }

    TradeinService(
            ProductMapper productMapper,
            TradeinApplicationMapper applicationMapper,
            ComputeClient computeClient,
            Clock clock) {
        this.productMapper = productMapper;
        this.applicationMapper = applicationMapper;
        this.computeClient = computeClient;
        this.clock = clock;
    }

    public TradeinQuoteResponse quote(TradeinQuoteRequest request) {
        Long userId = requirePositive(request.getUserId(), "User id is required");
        Long sourceDeviceId = requirePositive(request.getSourceDeviceId(), "Source device id is required");
        Map<String, Object> device = requireDevice(sourceDeviceId);
        Long ownerId = asLong(device.get("userId"));
        if (!Objects.equals(userId, ownerId)) {
            throw new BizException("Source device does not belong to user");
        }
        Long sourceProductId = asLong(device.get("productId"));
        if (sourceProductId == null) {
            throw new BizException("Source device product is missing");
        }

        Product sourceProduct = requireProduct(sourceProductId);
        TradeinRule rule = requireRule(sourceProduct);
        Product targetProduct = requireTargetProduct(rule);
        LocalDateTime activatedAt = asLocalDateTime(device.get("activatedAt"));
        if (activatedAt == null) {
            throw new BizException("Source device activation time is missing");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int monthsOwned = Math.max(0, (int) ChronoUnit.MONTHS.between(activatedAt, now));
        BigDecimal rawEfficiency = efficiencyForMonths(monthsOwned);
        BigDecimal efficiency = scale(rawEfficiency);
        BigDecimal sourcePrice = money(sourceProduct.getPriceUsdt());
        BigDecimal targetPrice = money(targetProduct.getPriceUsdt());
        BigDecimal salvage = scale(sourcePrice.multiply(rawEfficiency).multiply(SALVAGE_RECOVERY_RATE));
        BigDecimal discount = scale(rule.tradeinDiscountUsdt());
        BigDecimal netCost = scale(targetPrice.subtract(salvage).subtract(discount).max(BigDecimal.ZERO));

        return new TradeinQuoteResponse(
                true,
                "ELIGIBLE",
                userId,
                sourceDeviceId,
                asString(device.get("instanceNo")),
                sourceProduct.getId(),
                sourceProduct.getName(),
                sourceProduct.getTier(),
                targetProduct.getId(),
                targetProduct.getName(),
                targetProduct.getTier(),
                monthsOwned,
                efficiency,
                sourcePrice,
                targetPrice,
                salvage,
                discount,
                netCost);
    }

    @Transactional(rollbackFor = Exception.class)
    public TradeinApplication submit(TradeinSubmitRequest request) {
        TradeinQuoteRequest quoteRequest = new TradeinQuoteRequest();
        quoteRequest.setUserId(request.getUserId());
        quoteRequest.setSourceDeviceId(request.getSourceDeviceId());
        TradeinQuoteResponse quote = quote(quoteRequest);

        LocalDateTime now = LocalDateTime.now(clock);
        TradeinApplication application = new TradeinApplication();
        application.setTradeinNo(nextTradeinNo(now));
        application.setUserId(quote.getUserId());
        application.setSourceDeviceId(quote.getSourceDeviceId());
        application.setSourceInstanceNo(quote.getSourceInstanceNo());
        application.setSourceProductId(quote.getSourceProductId());
        application.setSourceProductName(quote.getSourceProductName());
        application.setSourceProductTier(quote.getSourceProductTier());
        application.setTargetProductId(quote.getTargetProductId());
        application.setTargetProductName(quote.getTargetProductName());
        application.setTargetProductTier(quote.getTargetProductTier());
        application.setMonthsOwned(quote.getMonthsOwned());
        application.setCurrentEfficiency(quote.getCurrentEfficiency());
        application.setSourcePriceUsdt(quote.getSourcePriceUsdt());
        application.setTargetPriceUsdt(quote.getTargetPriceUsdt());
        application.setSalvageValueUsdt(quote.getSalvageValueUsdt());
        application.setTradeinDiscountUsdt(quote.getTradeinDiscountUsdt());
        application.setNetUpgradeCostUsdt(quote.getNetUpgradeCostUsdt());
        application.setStatus(STATUS_SUBMITTED);
        application.setSubmittedAt(now);
        application.setIsDeleted(0);
        applicationMapper.insert(application);
        return application;
    }

    public PageResult<TradeinApplication> page(TradeinApplicationQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<TradeinApplication> wrapper = new LambdaQueryWrapper<TradeinApplication>()
                .eq(TradeinApplication::getIsDeleted, 0)
                .eq(request.getUserId() != null, TradeinApplication::getUserId, request.getUserId())
                .eq(StringUtils.hasText(request.getStatus()), TradeinApplication::getStatus, request.getStatus())
                .orderByDesc(TradeinApplication::getCreatedAt);
        Page<TradeinApplication> page = applicationMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public TradeinApplication get(String tradeinNo) {
        if (!StringUtils.hasText(tradeinNo)) {
            throw new BizException("Trade-in number is required");
        }
        TradeinApplication application = applicationMapper.selectOne(new LambdaQueryWrapper<TradeinApplication>()
                .eq(TradeinApplication::getTradeinNo, tradeinNo)
                .eq(TradeinApplication::getIsDeleted, 0));
        if (application == null) {
            throw new BizException("Trade-in application not found");
        }
        return application;
    }

    private Map<String, Object> requireDevice(Long sourceDeviceId) {
        ApiResult<Map<String, Object>> response = computeClient.getDevice(sourceDeviceId);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new BizException("Source device not found");
        }
        return response.getData();
    }

    private Product requireProduct(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || Integer.valueOf(1).equals(product.getIsDeleted())) {
            throw new BizException("Source product not found");
        }
        return product;
    }

    private Product requireTargetProduct(TradeinRule rule) {
        Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getTier, rule.targetTier())
                .eq(Product::getStatus, PRODUCT_ON_SALE)
                .eq(Product::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (product == null) {
            throw new BizException("Trade-in upgrade target product not found");
        }
        return product;
    }

    private TradeinRule requireRule(Product sourceProduct) {
        String tier = normalize(sourceProduct.getTier());
        String productNo = normalize(sourceProduct.getProductNo());
        if ("S1".equals(tier) || "NX-S1".equals(productNo) || "PRO".equals(tier) || "NX-PRO".equals(productNo)) {
            return new TradeinRule("PRO_V2", new BigDecimal("300.000000"));
        }
        if ("RACK".equals(tier) || "RACK_P1".equals(tier) || "NX-RACK".equals(productNo)) {
            return new TradeinRule("RACK_P2", new BigDecimal("800.000000"));
        }
        throw new BizException("Source product is not eligible for trade-in");
    }

    private BigDecimal efficiencyForMonths(int monthsOwned) {
        BigDecimal efficiency = BigDecimal.ONE;
        int remaining = Math.max(0, monthsOwned);
        int firstSegment = Math.min(remaining, 3);
        efficiency = multiplyRepeated(efficiency, MONTH_1_TO_3_FACTOR, firstSegment);
        remaining -= firstSegment;
        int secondSegment = Math.min(remaining, 5);
        efficiency = multiplyRepeated(efficiency, MONTH_4_TO_8_FACTOR, secondSegment);
        remaining -= secondSegment;
        efficiency = multiplyRepeated(efficiency, MONTH_9_PLUS_FACTOR, remaining);
        return efficiency.max(EFFICIENCY_FLOOR);
    }

    private BigDecimal multiplyRepeated(BigDecimal value, BigDecimal factor, int times) {
        BigDecimal result = value;
        for (int i = 0; i < times; i++) {
            result = result.multiply(factor);
        }
        return result;
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        return scale(value);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private Long requirePositive(Long value, String message) {
        if (value == null || value < 1) {
            throw new BizException(message);
        }
        return value;
    }

    private long normalizePageNum(Long pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private long normalizePageSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, 100);
    }

    private String nextTradeinNo(LocalDateTime now) {
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int suffix = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return "TRI-" + date + "-" + suffix;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Long.parseLong(text);
        }
        return null;
    }

    private LocalDateTime asLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toLocalDateTime();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            String normalized = text.trim();
            if (normalized.endsWith("Z") || normalized.contains("+")) {
                return OffsetDateTime.parse(normalized).toLocalDateTime();
            }
            return LocalDateTime.parse(normalized);
        }
        return null;
    }

    private record TradeinRule(String targetTier, BigDecimal tradeinDiscountUsdt) {
    }
}
