package ffdd.commerce.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.commerce.client.SystemConfigClient;
import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.PriceIndex;
import ffdd.commerce.domain.Product;
import ffdd.commerce.domain.ProductFaq;
import ffdd.commerce.domain.ProductReview;
import ffdd.commerce.domain.ProductSpec;
import ffdd.commerce.dto.PriceIndexRequest;
import ffdd.commerce.dto.ProductFaqRequest;
import ffdd.commerce.dto.ProductReviewRequest;
import ffdd.commerce.dto.ProductSpecRequest;
import ffdd.commerce.dto.StoreProductContentResponse;
import ffdd.common.api.ApiResult;
import ffdd.commerce.mapper.CommerceOrderMapper;
import ffdd.commerce.mapper.PriceIndexMapper;
import ffdd.commerce.mapper.ProductMapper;
import ffdd.commerce.mapper.ProductFaqMapper;
import ffdd.commerce.mapper.ProductReviewMapper;
import ffdd.commerce.mapper.ProductSpecMapper;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProductContentService {
    private static final String VISIBLE = "VISIBLE";
    private static final String HIDDEN = "HIDDEN";
    private static final String PENDING_REVIEW = "PENDING_REVIEW";
    private static final String ACTIVE = "ACTIVE";
    private static final String NEX_USDT_PRICE_KEY = "wallet.exchange.nex_usdt_price";
    private static final String NEX_24H_VOLUME_KEY = "wallet.nex_market.volume_24h_usdt";
    private static final int DEFAULT_REVIEW_MEDIA_MAX_COUNT = 6;
    private static final int DEFAULT_REVIEW_TITLE_MAX_LENGTH = 64;
    private static final int DEFAULT_REVIEW_CONTENT_MIN_LENGTH = 10;
    private static final int DEFAULT_REVIEW_CONTENT_MAX_LENGTH = 800;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ProductReviewMapper reviewMapper;
    private final ProductFaqMapper faqMapper;
    private final ProductSpecMapper specMapper;
    private final PriceIndexMapper priceIndexMapper;
    private final ProductMapper productMapper;
    private final CommerceOrderMapper orderMapper;
    private final SystemConfigClient systemConfigClient;

    public ProductContentService(
            ProductReviewMapper reviewMapper,
            ProductFaqMapper faqMapper,
            ProductSpecMapper specMapper,
            PriceIndexMapper priceIndexMapper,
            ProductMapper productMapper,
            CommerceOrderMapper orderMapper,
            SystemConfigClient systemConfigClient) {
        this.reviewMapper = reviewMapper;
        this.faqMapper = faqMapper;
        this.specMapper = specMapper;
        this.priceIndexMapper = priceIndexMapper;
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.systemConfigClient = systemConfigClient;
    }

    public StoreProductContentResponse appContent(Long productId) {
        StoreProductContentResponse response = new StoreProductContentResponse();
        response.setProductId(productId);
        List<ProductReview> reviews = listReviews(productId, true);
        response.setReviews(reviews);
        response.setFaqs(listFaqs(productId, true));
        response.setSpecs(listSpecs(productId, true));
        response.setReviewCount(reviews.size());
        BigDecimal total = reviews.stream()
                .map(ProductReview::getRating)
                .filter(rating -> rating != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        response.setAverageRating(reviews.isEmpty() ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(reviews.size()), 2, RoundingMode.HALF_UP));
        response.setRatingBars(ratingBars(reviews));
        return response;
    }

    private List<StoreProductContentResponse.RatingBar> ratingBars(List<ProductReview> reviews) {
        int[] counts = new int[6];
        int total = 0;
        for (ProductReview review : reviews) {
            if (review.getRating() == null) continue;
            int star = review.getRating().setScale(0, RoundingMode.HALF_UP).intValue();
            star = Math.max(1, Math.min(5, star));
            counts[star] += 1;
            total += 1;
        }
        return Arrays.asList(
                new StoreProductContentResponse.RatingBar(5, percent(counts[5], total)),
                new StoreProductContentResponse.RatingBar(4, percent(counts[4], total)),
                new StoreProductContentResponse.RatingBar(3, percent(counts[3], total)),
                new StoreProductContentResponse.RatingBar(2, percent(counts[2], total)),
                new StoreProductContentResponse.RatingBar(1, percent(counts[1], total))
        );
    }

    private int percent(int count, int total) {
        if (total <= 0) return 0;
        return BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP)
                .intValue();
    }

    public List<ProductReview> listReviews(Long productId, boolean visibleOnly) {
        return reviewMapper.selectList(new LambdaQueryWrapper<ProductReview>()
                .eq(ProductReview::getProductId, requireId(productId))
                .eq(ProductReview::getIsDeleted, 0)
                .eq(visibleOnly, ProductReview::getStatus, VISIBLE)
                .orderByAsc(ProductReview::getSortOrder)
                .orderByDesc(ProductReview::getCreatedAt));
    }

    public PageResult<ProductReview> pageReviews(Long productId, boolean visibleOnly, String status, Long pageNum, Long pageSize) {
        long normalizedPageNum = normalizePageNum(pageNum);
        long normalizedPageSize = normalizePageSize(pageSize);
        LambdaQueryWrapper<ProductReview> wrapper = new LambdaQueryWrapper<ProductReview>()
                .eq(ProductReview::getProductId, requireId(productId))
                .eq(ProductReview::getIsDeleted, 0)
                .eq(visibleOnly, ProductReview::getStatus, VISIBLE)
                .eq(!visibleOnly && StringUtils.hasText(status), ProductReview::getStatus, status == null ? null : status.trim().toUpperCase())
                .orderByAsc(ProductReview::getSortOrder)
                .orderByDesc(ProductReview::getCreatedAt);
        Page<ProductReview> page = reviewMapper.selectPage(new Page<>(normalizedPageNum, normalizedPageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public List<ProductFaq> listFaqs(Long productId, boolean visibleOnly) {
        return faqMapper.selectList(new LambdaQueryWrapper<ProductFaq>()
                .eq(ProductFaq::getProductId, requireId(productId))
                .eq(ProductFaq::getIsDeleted, 0)
                .eq(visibleOnly, ProductFaq::getStatus, VISIBLE)
                .orderByAsc(ProductFaq::getSortOrder)
                .orderByDesc(ProductFaq::getCreatedAt));
    }

    public List<ProductSpec> listSpecs(Long productId, boolean visibleOnly) {
        return specMapper.selectList(new LambdaQueryWrapper<ProductSpec>()
                .eq(ProductSpec::getProductId, requireId(productId))
                .eq(ProductSpec::getIsDeleted, 0)
                .eq(visibleOnly, ProductSpec::getStatus, VISIBLE)
                .orderByAsc(ProductSpec::getSortOrder)
                .orderByAsc(ProductSpec::getId));
    }

    public List<PriceIndex> listPriceIndex(boolean activeOnly) {
        return priceIndexMapper.selectList(new LambdaQueryWrapper<PriceIndex>()
                .eq(PriceIndex::getIsDeleted, 0)
                .eq(activeOnly, PriceIndex::getStatus, ACTIVE)
                .orderByDesc(PriceIndex::getSampledAt)
                .orderByAsc(PriceIndex::getMetricCode));
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductReview saveReview(Long id, ProductReviewRequest request) {
        ProductReview row = id == null ? new ProductReview() : requireReview(id);
        Long originalProductId = row.getProductId();
        row.setProductId(request.getProductId());
        row.setUserId(request.getUserId());
        row.setOrderId(request.getOrderId());
        row.setRating(request.getRating());
        row.setTitle(trim(request.getTitle()));
        row.setContent(trim(request.getContent()));
        row.setMediaObjectKeys(normalizeMediaObjectKeys(request.getMediaObjectKeys()));
        row.setAvatarObjectKey(normalizeAvatarObjectKey(request.getAvatarObjectKey()));
        row.setAvatarColor(trim(request.getAvatarColor()));
        row.setStatus(normalizeReviewStatus(request.getStatus(), VISIBLE));
        row.setSortOrder(defaultInt(request.getSortOrder()));
        row.setIsDeleted(0);
        if (id == null) reviewMapper.insert(row); else reviewMapper.updateById(row);
        recalculateProductRating(row.getProductId());
        if (originalProductId != null && !originalProductId.equals(row.getProductId())) {
            recalculateProductRating(originalProductId);
        }
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductReview submitAppReview(ProductReviewRequest request) {
        Long userId = requireUserId(request.getUserId());
        CommerceOrder order = requirePaidOrder(request.getOrderNo());
        if (!userId.equals(order.getUserId())) {
            throw new BizException("Order does not belong to authenticated user");
        }
        Long productId = requireId(request.getProductId());
        if (!productId.equals(order.getProductId())) {
            throw new BizException("Review product does not match order");
        }
        if (findReviewByOrder(order.getId(), userId) != null) {
            throw new BizException("Order already reviewed");
        }
        ReviewLimits limits = reviewLimits();
        String title = trim(request.getTitle());
        String content = trim(request.getContent());
        String mediaObjectKeys = normalizeMediaObjectKeys(request.getMediaObjectKeys(), limits.mediaMaxCount());
        validateAppReviewText(title, content, limits);

        ProductReview row = new ProductReview();
        row.setProductId(productId);
        row.setUserId(userId);
        row.setOrderId(order.getId());
        row.setRating(request.getRating());
        row.setTitle(title);
        row.setContent(content);
        row.setMediaObjectKeys(mediaObjectKeys);
        row.setAvatarObjectKey(normalizeAvatarObjectKey(request.getAvatarObjectKey()));
        row.setAvatarColor(trim(request.getAvatarColor()));
        row.setStatus(PENDING_REVIEW);
        row.setSortOrder(0);
        row.setIsDeleted(0);
        try {
            reviewMapper.insert(row);
        } catch (DuplicateKeyException ex) {
            throw new BizException("Order already reviewed");
        }
        return row;
    }

    public ProductReview findAppReviewByOrder(String orderNo, Long userId) {
        CommerceOrder order = requireOwnedOrder(orderNo, requireUserId(userId));
        return findReviewByOrder(order.getId(), userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductFaq saveFaq(Long id, ProductFaqRequest request) {
        ProductFaq row = id == null ? new ProductFaq() : requireFaq(id);
        row.setProductId(request.getProductId());
        row.setQuestion(request.getQuestion().trim());
        row.setAnswer(request.getAnswer().trim());
        row.setStatus(defaultText(request.getStatus(), VISIBLE));
        row.setSortOrder(defaultInt(request.getSortOrder()));
        row.setIsDeleted(0);
        if (id == null) faqMapper.insert(row); else faqMapper.updateById(row);
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductSpec saveSpec(Long id, ProductSpecRequest request) {
        ProductSpec row = id == null ? new ProductSpec() : requireSpec(id);
        row.setProductId(request.getProductId());
        row.setSpecGroup(defaultText(request.getSpecGroup(), "GENERAL"));
        row.setSpecKey(request.getSpecKey().trim());
        row.setSpecValue(request.getSpecValue().trim());
        row.setUnit(trim(request.getUnit()));
        row.setStatus(defaultText(request.getStatus(), VISIBLE));
        row.setSortOrder(defaultInt(request.getSortOrder()));
        row.setIsDeleted(0);
        if (id == null) specMapper.insert(row); else specMapper.updateById(row);
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public PriceIndex savePriceIndex(Long id, PriceIndexRequest request) {
        PriceIndex row = id == null ? new PriceIndex() : requirePriceIndex(id);
        row.setMetricCode(request.getMetricCode().trim().toUpperCase(Locale.ROOT));
        row.setMetricLabel(request.getMetricLabel().trim());
        row.setUnitLabel(request.getUnitLabel().trim());
        row.setPriceUsdt(request.getPriceUsdt());
        row.setDeltaPercent(request.getDeltaPercent() == null ? BigDecimal.ZERO : request.getDeltaPercent());
        row.setVolume24hUsdt(request.getVolume24hUsdt() == null ? BigDecimal.ZERO : request.getVolume24hUsdt());
        row.setSparkline(normalizeSparkline(request.getSparkline()));
        row.setStatus(defaultText(request.getStatus(), ACTIVE));
        row.setSampledAt(request.getSampledAt() == null ? LocalDateTime.now() : request.getSampledAt());
        row.setIsDeleted(0);
        if (id == null) priceIndexMapper.insert(row); else priceIndexMapper.updateById(row);
        syncNexMarketConfig(row);
        return row;
    }

    public void deleteReview(Long id) {
        ProductReview row = requireReview(id);
        row.setIsDeleted(1);
        reviewMapper.updateById(row);
        recalculateProductRating(row.getProductId());
    }

    public void deleteFaq(Long id) {
        ProductFaq row = requireFaq(id);
        row.setIsDeleted(1);
        faqMapper.updateById(row);
    }

    public void deleteSpec(Long id) {
        ProductSpec row = requireSpec(id);
        row.setIsDeleted(1);
        specMapper.updateById(row);
    }

    public void deletePriceIndex(Long id) {
        PriceIndex row = requirePriceIndex(id);
        row.setIsDeleted(1);
        priceIndexMapper.updateById(row);
    }

    private Long requireId(Long id) {
        if (id == null || id <= 0) throw new BizException("Product id is required");
        return id;
    }

    private Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) throw new BizException("User id is required");
        return userId;
    }

    private CommerceOrder requirePaidOrder(String orderNo) {
        CommerceOrder order = requireOrder(orderNo);
        String paymentStatus = order.getPaymentStatus();
        if (!"PAID".equalsIgnoreCase(paymentStatus) && !"SUCCESS".equalsIgnoreCase(paymentStatus)) {
            throw new BizException("Only paid orders can be reviewed");
        }
        return order;
    }

    private CommerceOrder requireOwnedOrder(String orderNo, Long userId) {
        CommerceOrder order = requireOrder(orderNo);
        if (!userId.equals(order.getUserId())) {
            throw new BizException("Order does not belong to authenticated user");
        }
        return order;
    }

    private CommerceOrder requireOrder(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BizException("Order no is required");
        }
        CommerceOrder order = orderMapper.selectOne(new LambdaQueryWrapper<CommerceOrder>()
                .eq(CommerceOrder::getOrderNo, orderNo.trim())
                .eq(CommerceOrder::getIsDeleted, 0));
        if (order == null) {
            throw new BizException("Order not found");
        }
        return order;
    }

    private ProductReview findReviewByOrder(Long orderId, Long userId) {
        if (orderId == null || userId == null) {
            return null;
        }
        return reviewMapper.selectOne(new LambdaQueryWrapper<ProductReview>()
                .eq(ProductReview::getOrderId, orderId)
                .eq(ProductReview::getUserId, userId)
                .eq(ProductReview::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private void recalculateProductRating(Long productId) {
        if (productMapper == null || productId == null) {
            return;
        }
        List<ProductReview> visibleReviews = reviewMapper.selectList(new LambdaQueryWrapper<ProductReview>()
                .eq(ProductReview::getProductId, productId)
                .eq(ProductReview::getIsDeleted, 0)
                .eq(ProductReview::getStatus, VISIBLE));
        if (visibleReviews == null) {
            visibleReviews = List.of();
        }
        BigDecimal total = visibleReviews.stream()
                .map(ProductReview::getRating)
                .filter(rating -> rating != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = visibleReviews.isEmpty()
                ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(visibleReviews.size()), 2, RoundingMode.HALF_UP);
        Product patch = new Product();
        patch.setId(productId);
        patch.setRatingValue(average);
        patch.setReviewCount(visibleReviews.size());
        productMapper.updateById(patch);
    }

    private ProductReview requireReview(Long id) {
        ProductReview row = reviewMapper.selectById(id);
        if (row == null || Integer.valueOf(1).equals(row.getIsDeleted())) throw new BizException("Product review not found");
        return row;
    }

    private ProductFaq requireFaq(Long id) {
        ProductFaq row = faqMapper.selectById(id);
        if (row == null || Integer.valueOf(1).equals(row.getIsDeleted())) throw new BizException("Product FAQ not found");
        return row;
    }

    private ProductSpec requireSpec(Long id) {
        ProductSpec row = specMapper.selectById(id);
        if (row == null || Integer.valueOf(1).equals(row.getIsDeleted())) throw new BizException("Product spec not found");
        return row;
    }

    private PriceIndex requirePriceIndex(Long id) {
        PriceIndex row = priceIndexMapper.selectById(id);
        if (row == null || Integer.valueOf(1).equals(row.getIsDeleted())) throw new BizException("Price index row not found");
        return row;
    }

    private void syncNexMarketConfig(PriceIndex row) {
        if (systemConfigClient == null || !isActiveNexPriceIndex(row)) {
            return;
        }
        upsertWalletConfig(
                NEX_USDT_PRICE_KEY,
                decimalText(row.getPriceUsdt()),
                "NEX/USDT exchange reference price synchronized from nx_price_index.");
        upsertWalletConfig(
                NEX_24H_VOLUME_KEY,
                decimalText(row.getVolume24hUsdt()),
                "Reported 24h NEX market volume synchronized from nx_price_index.");
    }

    private boolean isActiveNexPriceIndex(PriceIndex row) {
        if (row == null || !ACTIVE.equalsIgnoreCase(row.getStatus())) {
            return false;
        }
        String code = row.getMetricCode() == null ? "" : row.getMetricCode().trim().toUpperCase(Locale.ROOT);
        return "NEX".equals(code) || "NEX_USDT".equals(code);
    }

    private void upsertWalletConfig(String configKey, String value, String remark) {
        List<SystemConfigClient.ConfigItemResponse> existing = systemConfigClient.listConfigs(configKey, null, 20).getData();
        SystemConfigClient.ConfigItemResponse exact = existing == null ? null : existing.stream()
                .filter(item -> configKey.equals(item.configKey()))
                .findFirst()
                .orElse(null);
        if (exact == null) {
            systemConfigClient.createConfig(new SystemConfigClient.ConfigItemSaveRequest(
                    configKey,
                    value,
                    "NUMBER",
                    "wallet",
                    "PUBLIC",
                    remark,
                    1));
            return;
        }
        systemConfigClient.updateConfig(exact.id(), new SystemConfigClient.ConfigItemUpdateRequest(
                value,
                exact.valueType() == null ? "NUMBER" : exact.valueType(),
                exact.configGroup() == null ? "wallet" : exact.configGroup(),
                exact.visibility() == null ? "PUBLIC" : exact.visibility(),
                exact.remark() == null ? remark : exact.remark(),
                exact.status() == null ? 1 : exact.status()));
    }

    private String decimalText(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString();
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeMediaObjectKeys(List<String> mediaObjectKeys) {
        return normalizeMediaObjectKeys(mediaObjectKeys, Integer.MAX_VALUE);
    }

    private String normalizeMediaObjectKeys(List<String> mediaObjectKeys, int maxCount) {
        if (mediaObjectKeys == null || mediaObjectKeys.isEmpty()) return null;
        List<String> normalized = mediaObjectKeys.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalized.isEmpty()) return null;
        if (normalized.size() > maxCount) {
            throw new BizException("Review media limit is " + maxCount);
        }
        normalized.forEach(this::validateReviewMediaObjectKey);
        try {
            return OBJECT_MAPPER.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new BizException("Invalid review media object keys");
        }
    }

    private String normalizeAvatarObjectKey(String value) {
        String key = trim(value);
        if (key == null) {
            return null;
        }
        validateUploadedObjectKey(key, "auth/users/avatar/", "Reviewer avatar");
        return key;
    }

    private void validateReviewMediaObjectKey(String key) {
        validateUploadedObjectKey(key, "commerce/products/product_review/", "Review media");
    }

    private void validateAppReviewText(String title, String content, ReviewLimits limits) {
        if (title != null && title.length() > limits.titleMaxLength()) {
            throw new BizException("Review title must be at most " + limits.titleMaxLength() + " characters");
        }
        int contentLength = content == null ? 0 : content.length();
        if (contentLength < limits.contentMinLength()) {
            throw new BizException("Review content must be at least " + limits.contentMinLength() + " characters");
        }
        if (contentLength > limits.contentMaxLength()) {
            throw new BizException("Review content must be at most " + limits.contentMaxLength() + " characters");
        }
    }

    private ReviewLimits reviewLimits() {
        Map<String, Object> config = commerceConfig();
        int contentMin = positiveInt(config.get("review.content_min_length"), DEFAULT_REVIEW_CONTENT_MIN_LENGTH);
        int contentMax = Math.max(contentMin, positiveInt(config.get("review.content_max_length"), DEFAULT_REVIEW_CONTENT_MAX_LENGTH));
        return new ReviewLimits(
                positiveInt(config.get("review.media_max_count"), DEFAULT_REVIEW_MEDIA_MAX_COUNT),
                positiveInt(config.get("review.title_max_length"), DEFAULT_REVIEW_TITLE_MAX_LENGTH),
                contentMin,
                contentMax);
    }

    private Map<String, Object> commerceConfig() {
        if (systemConfigClient == null) {
            return Map.of();
        }
        try {
            ApiResult<Map<String, Object>> response = systemConfigClient.commerce();
            if (response != null && response.getCode() == 0 && response.getData() != null) {
                return response.getData();
            }
        } catch (RuntimeException ignored) {
            // Review submission keeps deterministic local defaults if system config is unavailable.
        }
        return Map.of();
    }

    private int positiveInt(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            int parsed = new BigDecimal(String.valueOf(value).trim()).intValue();
            return parsed > 0 ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void validateUploadedObjectKey(String key, String requiredPrefix, String fieldName) {
        String lower = key.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || key.startsWith("/")) {
            throw new BizException(fieldName + " must be uploaded through MinIO and saved as an object key");
        }
        if (!key.startsWith(requiredPrefix)
                || key.contains("..")
                || key.contains("\\")
                || key.endsWith("/")
                || key.length() > 255
                || key.chars().anyMatch(Character::isISOControl)) {
            throw new BizException(fieldName + " must be uploaded through MinIO and saved as an object key");
        }
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        if (!fileName.matches("[A-Za-z0-9._-]+")) {
            throw new BizException(fieldName + " must be uploaded through MinIO and saved as an object key");
        }
    }

    private String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String normalizeReviewStatus(String value, String defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "VISIBLE", "PUBLISHED" -> VISIBLE;
            case "HIDDEN" -> HIDDEN;
            case "PENDING", "PENDING_REVIEW" -> PENDING_REVIEW;
            default -> throw new BizException("Unsupported product review status");
        };
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private long normalizePageNum(Long pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private long normalizePageSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) return 20;
        return Math.min(pageSize, 100);
    }

    private String normalizeSparkline(String value) {
        String raw = trim(value);
        if (raw == null) {
            return null;
        }
        if (raw.startsWith("[") && raw.endsWith("]")) {
            return raw;
        }
        String body = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(BigDecimal::new)
                .map(BigDecimal::toPlainString)
                .reduce((left, right) -> left + "," + right)
                .orElse("0");
        return "[" + body + "]";
    }

    private record ReviewLimits(int mediaMaxCount, int titleMaxLength, int contentMinLength, int contentMaxLength) {
    }
}
