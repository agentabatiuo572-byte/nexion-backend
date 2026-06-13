package ffdd.commerce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.commerce.client.ComputeClient;
import ffdd.commerce.client.SystemConfigClient;
import ffdd.commerce.client.dto.ComputeDeviceActivateRequest;
import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.CommerceOrderItem;
import ffdd.commerce.domain.Product;
import ffdd.commerce.domain.ProductReview;
import ffdd.commerce.domain.TradeinRule;
import ffdd.commerce.dto.OrderItemCreateRequest;
import ffdd.commerce.dto.OrderCreateRequest;
import ffdd.commerce.dto.OrderPayRequest;
import ffdd.commerce.dto.OrderQueryRequest;
import ffdd.commerce.dto.ProductCreateRequest;
import ffdd.commerce.dto.ProductQueryRequest;
import ffdd.commerce.dto.ProductUpdateRequest;
import ffdd.commerce.dto.StoreAiPerformance;
import ffdd.commerce.dto.StoreProductResponse;
import ffdd.commerce.mapper.CommerceOrderMapper;
import ffdd.commerce.mapper.CommerceOrderItemMapper;
import ffdd.commerce.mapper.ProductMapper;
import ffdd.commerce.mapper.ProductReviewMapper;
import ffdd.commerce.mapper.TradeinRuleMapper;
import ffdd.commerce.service.CommerceService;
import ffdd.commerce.service.ProductMediaService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.common.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CommerceServiceImpl implements CommerceService {
    private static final String PRODUCT_ON_SALE = "ON_SALE";
    private static final String PAYMENT_PENDING = "PENDING";
    private static final String PAYMENT_PAID = "PAID";
    private static final String ORDER_CREATED = "CREATED";
    private static final String ORDER_PAID = "PAID";
    private static final String ORDER_TYPE_SINGLE = "SINGLE";
    private static final String ORDER_TYPE_BUNDLE = "BUNDLE";
    private static final String REVIEW_VISIBLE = "VISIBLE";
    private static final String ACTIVATION_WAITING_PAYMENT = "WAITING_PAYMENT";
    private static final String ACTIVATION_PENDING = "PENDING";
    private static final String ACTIVATION_ACTIVATED = "ACTIVATED";
    private static final String ACTIVATION_FAILED = "FAILED";

    private final ProductMapper productMapper;
    private final CommerceOrderMapper orderMapper;
    private final CommerceOrderItemMapper orderItemMapper;
    private final ProductReviewMapper reviewMapper;
    private final TradeinRuleMapper tradeinRuleMapper;
    private final SystemConfigClient systemConfigClient;
    private final ComputeClient computeClient;
    private final EventOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final ProductMediaService productMediaService;

    @Autowired
    public CommerceServiceImpl(
            ProductMapper productMapper,
            CommerceOrderMapper orderMapper,
            CommerceOrderItemMapper orderItemMapper,
            ProductReviewMapper reviewMapper,
            TradeinRuleMapper tradeinRuleMapper,
            SystemConfigClient systemConfigClient,
            ComputeClient computeClient,
            EventOutboxService outboxService,
            ObjectMapper objectMapper,
            ProductMediaService productMediaService) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.reviewMapper = reviewMapper;
        this.tradeinRuleMapper = tradeinRuleMapper;
        this.systemConfigClient = systemConfigClient;
        this.computeClient = computeClient;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.productMediaService = productMediaService;
    }

    public CommerceServiceImpl(
            ProductMapper productMapper,
            CommerceOrderMapper orderMapper,
            TradeinRuleMapper tradeinRuleMapper,
            ComputeClient computeClient,
            EventOutboxService outboxService,
            ObjectMapper objectMapper) {
        this(productMapper, orderMapper, null, null, tradeinRuleMapper, null, computeClient, outboxService, objectMapper, null);
    }

    public CommerceServiceImpl(
            ProductMapper productMapper,
            CommerceOrderMapper orderMapper,
            ProductReviewMapper reviewMapper,
            TradeinRuleMapper tradeinRuleMapper,
            ComputeClient computeClient,
            EventOutboxService outboxService,
            ObjectMapper objectMapper) {
        this(productMapper, orderMapper, null, reviewMapper, tradeinRuleMapper, null, computeClient, outboxService, objectMapper, null);
    }

    @Override
    public PageResult<Product> pageProducts(ProductQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .eq(Product::getIsDeleted, 0)
                .like(StringUtils.hasText(request.getKeyword()), Product::getName, request.getKeyword())
                .eq(StringUtils.hasText(request.getProductType()), Product::getProductType, request.getProductType())
                .eq(StringUtils.hasText(request.getStatus()), Product::getStatus, request.getStatus())
                .eq(StringUtils.hasText(request.getUnlockPhase()), Product::getUnlockPhase, normalizeGenerationGate(request.getUnlockPhase()))
                .orderByDesc(Product::getCreatedAt);
        Page<Product> page = productMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    public Product getProduct(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null || Integer.valueOf(1).equals(product.getIsDeleted())) {
            throw new BizException("Product not found");
        }
        return product;
    }

    @Override
    public Product createProduct(ProductCreateRequest request) {
        if (request == null) {
            throw new BizException("Product request is required");
        }
        Product product = new Product();
        product.setProductNo(requireText(request.getProductNo(), "Product no is required"));
        product.setName(requireText(request.getName(), "Product name is required"));
        product.setProductType(requireText(request.getProductType(), "Product type is required"));
        product.setTier(trimToNull(request.getTier()));
        product.setStatus(requireText(request.getStatus(), "Product status is required"));
        product.setPriceUsdt(requirePositiveMoney(request.getPriceUsdt(), "Product price is required"));
        product.setHashrate(defaultDecimal(request.getHashrate()));
        product.setEstimatedDailyUsdt(defaultDecimal(request.getEstimatedDailyUsdt()));
        product.setDailyNex(defaultDecimal(request.getDailyNex()));
        product.setStock(request.getStock() == null ? 0 : request.getStock());
        product.setCoverUrl(normalizeStoreMediaObjectKey(request.getCoverUrl(), "Product cover", false));
        product.setDetailImageUrls(normalizeStoreMediaObjectKeys(request.getDetailImageUrls(), "Product detail media", false));
        applyCreateStoreFields(product, request);
        product.setIsDeleted(0);
        try {
            productMapper.insert(product);
            return product;
        } catch (DuplicateKeyException ex) {
            throw new BizException(409, "Product no already exists");
        }
    }

    @Override
    public Product updateProduct(Long id, ProductUpdateRequest request) {
        if (request == null) {
            throw new BizException("Product request is required");
        }
        Product product = getProduct(id);
        if (request.getName() != null) {
            product.setName(requireText(request.getName(), "Product name is required"));
        }
        if (request.getProductType() != null) {
            product.setProductType(requireText(request.getProductType(), "Product type is required"));
        }
        if (request.getTier() != null) {
            product.setTier(trimToNull(request.getTier()));
        }
        if (request.getStatus() != null) {
            product.setStatus(requireText(request.getStatus(), "Product status is required"));
        }
        if (request.getPriceUsdt() != null) {
            product.setPriceUsdt(requirePositiveMoney(request.getPriceUsdt(), "Product price is required"));
        }
        if (request.getHashrate() != null) {
            product.setHashrate(defaultDecimal(request.getHashrate()));
        }
        if (request.getEstimatedDailyUsdt() != null) {
            product.setEstimatedDailyUsdt(defaultDecimal(request.getEstimatedDailyUsdt()));
        }
        if (request.getDailyNex() != null) {
            product.setDailyNex(defaultDecimal(request.getDailyNex()));
        }
        if (request.getStock() != null) {
            product.setStock(request.getStock());
        }
        if (request.getCoverUrl() != null) {
            product.setCoverUrl(normalizeStoreMediaObjectKey(request.getCoverUrl(), "Product cover", true));
        }
        if (request.getDetailImageUrls() != null) {
            product.setDetailImageUrls(normalizeStoreMediaObjectKeys(request.getDetailImageUrls(), "Product detail media", true));
        }
        applyUpdateStoreFields(product, request);
        productMapper.updateById(product);
        return product;
    }

    @Override
    public void deleteProduct(Long id) {
        Product product = getProduct(id);
        product.setIsDeleted(1);
        product.setStatus("ARCHIVED");
        product.setStoreFeatured(0);
        productMapper.updateById(product);
    }

    @Override
    public List<Product> listFeaturedProductCandidates(String currentPhase) {
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getIsDeleted, 0)
                .eq(Product::getStoreVisible, 1)
                .eq(Product::getStatus, PRODUCT_ON_SALE)
                .in(Product::getUnlockPhase, phaseCodesThrough(currentPhase))
                .orderByDesc(Product::getStoreFeatured)
                .orderByAsc(Product::getUnlockPhase)
                .orderByAsc(Product::getSortOrder)
                .orderByAsc(Product::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Product featureProduct(Long id, String currentPhase) {
        Product product = getProduct(id);
        if (!PRODUCT_ON_SALE.equals(product.getStatus()) || !Integer.valueOf(1).equals(product.getStoreVisible())) {
            throw new BizException("Only visible on-sale SKU can be featured");
        }
        if (!phaseCodesThrough(currentPhase).contains(normalizeGenerationGate(product.getUnlockPhase()))) {
            throw new BizException("SKU is not available for the current generation gate");
        }
        productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getIsDeleted, 0)
                .set(Product::getStoreFeatured, 0));
        product.setStoreFeatured(1);
        productMapper.updateById(product);
        return product;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Product syncGenerationPhase(String currentPhase) {
        int currentIndex = phaseIndex(currentPhase);
        String normalizedPhase = "P" + currentIndex;
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getIsDeleted, 0)
                .orderByAsc(Product::getSortOrder)
                .orderByAsc(Product::getId));

        for (Product product : products) {
            int productPhaseIndex = phaseIndex(product.getUnlockPhase());
            if ("CLOUD_SHARE".equals(product.getProductType())) {
                product.setStoreStatus("active");
            } else if (productPhaseIndex < currentIndex) {
                product.setStoreStatus("legacy");
            } else if (productPhaseIndex == currentIndex) {
                product.setStoreStatus("active");
            }
            if (!phaseCodesThrough(normalizedPhase).contains(normalizeGenerationGate(product.getUnlockPhase()))) {
                product.setStoreFeatured(0);
            }
            productMapper.updateById(product);
        }

        Product featured = products.stream()
                .filter(product -> Integer.valueOf(1).equals(product.getStoreFeatured()))
                .filter(product -> isFeaturedEligible(product, normalizedPhase))
                .findFirst()
                .orElse(null);
        if (featured != null) {
            return featured;
        }

        Product replacement = findFeaturedReplacement(products, currentIndex);
        if (replacement == null) {
            return null;
        }
        productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getIsDeleted, 0)
                .set(Product::getStoreFeatured, 0));
        replacement.setStoreFeatured(1);
        productMapper.updateById(replacement);
        return replacement;
    }

    @Override
    public List<StoreProductResponse> listStoreProducts() {
        List<TradeinRule> tradeinRules = tradeinRuleMapper.selectList(new LambdaQueryWrapper<TradeinRule>()
                .eq(TradeinRule::getIsDeleted, 0)
                .eq(TradeinRule::getStatus, 1)
                .orderByDesc(TradeinRule::getSortOrder)
                .orderByAsc(TradeinRule::getId));
        if (tradeinRules == null) {
            tradeinRules = List.of();
        }
        List<TradeinRule> activeTradeinRules = tradeinRules;
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .eq(Product::getIsDeleted, 0)
                        .eq(Product::getStoreVisible, 1)
                        .ne(Product::getStatus, "OFF_SALE")
                        .orderByAsc(Product::getSortOrder)
                        .orderByDesc(Product::getCreatedAt))
                .stream()
                .map(product -> toStoreProduct(product, activeTradeinRules))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceOrder createOrder(OrderCreateRequest request) {
        if (request.getUserId() == null) {
            throw new BizException("User id is required");
        }
        List<OrderLineDraft> drafts = normalizeOrderLines(request);
        if (drafts.isEmpty()) {
            throw new BizException("Order items are required");
        }
        int totalQuantity = drafts.stream().map(OrderLineDraft::quantity).reduce(0, Integer::sum);
        BigDecimal subtotal = drafts.stream()
                .map(OrderLineDraft::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = calculateBundleDiscount(drafts.size(), subtotal);
        BigDecimal amount = subtotal.subtract(discount).max(BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP);

        for (OrderLineDraft draft : drafts) {
            int stockUpdated = productMapper.update(null, new LambdaUpdateWrapper<Product>()
                    .eq(Product::getId, draft.product().getId())
                    .eq(Product::getIsDeleted, 0)
                    .ge(Product::getStock, draft.quantity())
                    .setSql("stock = stock - " + draft.quantity()));
            if (stockUpdated == 0) {
                throw new BizException("Insufficient stock");
            }
        }

        CommerceOrder order = new CommerceOrder();
        Product firstProduct = drafts.get(0).product();
        order.setUserId(request.getUserId());
        order.setProductId(firstProduct.getId());
        order.setQuantity(totalQuantity);
        order.setOrderType(drafts.size() > 1 || totalQuantity > 1 ? ORDER_TYPE_BUNDLE : ORDER_TYPE_SINGLE);
        order.setItemCount(drafts.size());
        order.setSubtotalUsdt(subtotal.setScale(6, RoundingMode.HALF_UP));
        order.setDiscountUsdt(discount.setScale(6, RoundingMode.HALF_UP));
        order.setOrderNo(nextOrderNo());
        order.setAmountUsdt(amount);
        order.setPaymentStatus(PAYMENT_PENDING);
        order.setOrderStatus(ORDER_CREATED);
        order.setActivationStatus(ACTIVATION_WAITING_PAYMENT);
        order.setIsDeleted(0);
        orderMapper.insert(order);
        List<CommerceOrderItem> items = new ArrayList<>();
        int sortOrder = 0;
        for (OrderLineDraft draft : drafts) {
            CommerceOrderItem item = new CommerceOrderItem();
            item.setOrderNo(order.getOrderNo());
            item.setProductId(draft.product().getId());
            item.setProductNo(draft.product().getProductNo());
            item.setProductName(draft.product().getName());
            item.setQuantity(draft.quantity());
            item.setUnitPriceUsdt(defaultDecimal(draft.product().getPriceUsdt()).setScale(6, RoundingMode.HALF_UP));
            item.setLineAmountUsdt(draft.lineAmount().setScale(6, RoundingMode.HALF_UP));
            item.setSortOrder(sortOrder++);
            item.setIsDeleted(0);
            if (orderItemMapper != null) {
                orderItemMapper.insert(item);
            }
            items.add(item);
        }
        order.setItems(items);
        return order;
    }

    @Override
    public PageResult<CommerceOrder> pageOrders(OrderQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<CommerceOrder> wrapper = new LambdaQueryWrapper<CommerceOrder>()
                .eq(CommerceOrder::getIsDeleted, 0)
                .eq(request.getUserId() != null, CommerceOrder::getUserId, request.getUserId())
                .eq(StringUtils.hasText(request.getOrderNo()), CommerceOrder::getOrderNo, request.getOrderNo())
                .eq(StringUtils.hasText(request.getPaymentStatus()), CommerceOrder::getPaymentStatus, request.getPaymentStatus())
                .eq(StringUtils.hasText(request.getOrderStatus()), CommerceOrder::getOrderStatus, request.getOrderStatus())
                .orderByDesc(CommerceOrder::getCreatedAt);
        Page<CommerceOrder> page = orderMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        List<CommerceOrder> records = page.getRecords();
        attachOrderItems(records);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    @Override
    public CommerceOrder getOrder(String orderNo) {
        CommerceOrder order = orderMapper.selectOne(new LambdaQueryWrapper<CommerceOrder>()
                .eq(CommerceOrder::getOrderNo, orderNo)
                .eq(CommerceOrder::getIsDeleted, 0));
        if (order == null) {
            throw new BizException("Order not found");
        }
        return attachOrderItems(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceOrder markPaid(String orderNo, OrderPayRequest request) {
        CommerceOrder order = getOrder(orderNo);
        if (PAYMENT_PAID.equals(order.getPaymentStatus())) {
            return activatePaidOrder(orderNo);
        }

        CommerceOrder patch = new CommerceOrder();
        patch.setId(order.getId());
        patch.setPaymentNo(request.getPaymentNo());
        patch.setPaymentStatus(PAYMENT_PAID);
        patch.setOrderStatus(ORDER_PAID);
        patch.setActivationStatus(ACTIVATION_PENDING);
        patch.setPaidAt(LocalDateTime.now());
        orderMapper.updateById(patch);

        order.setPaymentStatus(PAYMENT_PAID);
        order.setPaymentNo(request.getPaymentNo());
        order.setOrderStatus(ORDER_PAID);
        order.setActivationStatus(ACTIVATION_PENDING);
        order.setPaidAt(patch.getPaidAt());
        publishOrderPaidEvent(order);
        return activatePaidOrder(orderNo);
    }

    @Override
    public CommerceOrder activatePaidOrder(String orderNo) {
        CommerceOrder order = getOrder(orderNo);
        if (!PAYMENT_PAID.equals(order.getPaymentStatus())) {
            throw new BizException("Order is not paid");
        }
        if (ACTIVATION_ACTIVATED.equals(order.getActivationStatus())) {
            return order;
        }

        CommerceOrder pendingPatch = new CommerceOrder();
        pendingPatch.setId(order.getId());
        pendingPatch.setActivationStatus(ACTIVATION_PENDING);
        orderMapper.updateById(pendingPatch);
        order.setActivationStatus(ACTIVATION_PENDING);

        try {
            List<CommerceOrderItem> items = order.getItems();
            if (items == null || items.isEmpty()) {
                Product product = getProduct(order.getProductId());
                ApiResult<?> response = computeClient.activateDevices(buildActivateRequest(order, product, order.getQuantity()));
                if (response == null || response.getCode() != 0) {
                    markActivationFailed(order);
                    return order;
                }
            } else {
                for (CommerceOrderItem item : items) {
                    Product product = getProduct(item.getProductId());
                    ApiResult<?> response = computeClient.activateDevices(buildActivateRequest(order, product, item.getQuantity()));
                    if (response == null || response.getCode() != 0) {
                        markActivationFailed(order);
                        return order;
                    }
                }
            }
            CommerceOrder patch = new CommerceOrder();
            patch.setId(order.getId());
            patch.setActivationStatus(ACTIVATION_ACTIVATED);
            orderMapper.updateById(patch);
            order.setActivationStatus(ACTIVATION_ACTIVATED);
            return order;
        } catch (RuntimeException ex) {
            markActivationFailed(order);
            return order;
        }
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

    private List<OrderLineDraft> normalizeOrderLines(OrderCreateRequest request) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            if (request.getItems().size() > 20) {
                throw new BizException("Order item count must be <= 20");
            }
            for (OrderItemCreateRequest item : request.getItems()) {
                if (item == null || item.getProductId() == null) {
                    throw new BizException("Order item productId is required");
                }
                int quantity = item.getQuantity() == null ? 1 : item.getQuantity();
                if (quantity < 1) {
                    throw new BizException("Quantity must be greater than zero");
                }
                quantities.merge(item.getProductId(), quantity, Integer::sum);
            }
        } else {
            if (request.getProductId() == null) {
                throw new BizException("Product id is required");
            }
            int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
            if (quantity < 1) {
                throw new BizException("Quantity must be greater than zero");
            }
            quantities.put(request.getProductId(), quantity);
        }

        List<OrderLineDraft> drafts = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            Product product = getProduct(entry.getKey());
            if (!PRODUCT_ON_SALE.equals(product.getStatus())) {
                throw new BizException("Product is not on sale");
            }
            if (product.getStock() == null || product.getStock() < entry.getValue()) {
                throw new BizException("Insufficient stock");
            }
            BigDecimal lineAmount = defaultDecimal(product.getPriceUsdt())
                    .multiply(BigDecimal.valueOf(entry.getValue()))
                    .setScale(6, RoundingMode.HALF_UP);
            drafts.add(new OrderLineDraft(product, entry.getValue(), lineAmount));
        }
        return drafts;
    }

    private BigDecimal calculateBundleDiscount(int itemCount, BigDecimal subtotal) {
        if (itemCount < 2 || subtotal == null || subtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        Map<String, Object> config = commerceConfig();
        BigDecimal pct = BigDecimal.ZERO;
        for (int tier = 1; tier <= 3; tier++) {
            int minItems = configInt(config.get("bundle.discount_tier_" + tier + "_min_items"), 0);
            BigDecimal tierPct = configDecimal(config.get("bundle.discount_tier_" + tier + "_pct"));
            if (minItems > 0 && itemCount >= minItems && tierPct.compareTo(BigDecimal.ZERO) > 0) {
                pct = tierPct;
            }
        }
        if (pct.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return subtotal.multiply(pct)
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                .setScale(6, RoundingMode.HALF_UP);
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
            // Orders still work without optional commerce config; bundle discount falls back to zero.
        }
        return Map.of();
    }

    private int configInt(Object value, int fallback) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return fallback;
        }
        try {
            return Math.max(0, new BigDecimal(String.valueOf(value).trim()).intValue());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private BigDecimal configDecimal(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private CommerceOrder attachOrderItems(CommerceOrder order) {
        if (order == null || orderItemMapper == null) {
            return order;
        }
        order.setItems(orderItemMapper.selectList(new LambdaQueryWrapper<CommerceOrderItem>()
                .eq(CommerceOrderItem::getOrderNo, order.getOrderNo())
                .eq(CommerceOrderItem::getIsDeleted, 0)
                .orderByAsc(CommerceOrderItem::getSortOrder)));
        return order;
    }

    private void attachOrderItems(List<CommerceOrder> orders) {
        if (orders == null || orders.isEmpty() || orderItemMapper == null) {
            return;
        }
        for (CommerceOrder order : orders) {
            attachOrderItems(order);
        }
    }

    private String nextOrderNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int suffix = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return "ORD-" + date + "-" + suffix;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String trimToEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private BigDecimal requirePositiveMoney(BigDecimal value, String message) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(message);
        }
        return value;
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Integer defaultInteger(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String normalizeGenerationGate(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String normalizeGenerationGate(String value, Integer generation) {
        String normalized = normalizeGenerationGate(value);
        if (normalized != null) {
            return normalized;
        }
        int index = defaultInteger(generation, 1);
        return "P" + Math.max(index, 1);
    }

    private void applyCreateStoreFields(Product product, ProductCreateRequest request) {
        product.setBadge(trimToNull(request.getBadge()));
        product.setTagline(trimToNull(request.getTagline()));
        product.setStoreStatus(trimToNull(request.getStoreStatus()));
        product.setStoreVisible(defaultInteger(request.getStoreVisible(), 1));
        product.setStoreFeatured(0);
        product.setSortOrder(defaultInteger(request.getSortOrder(), 0));
        product.setGeneration(defaultInteger(request.getGeneration(), 1));
        product.setGpuModel(trimToNull(request.getGpuModel()));
        product.setVramTotalGb(request.getVramTotalGb());
        product.setAiPerformanceJson(trimToNull(request.getAiPerformanceJson()));
        product.setDetailMetricsJson(trimToNull(request.getDetailMetricsJson()));
        product.setHardwareSpecsJson(trimToNull(request.getHardwareSpecsJson()));
        product.setReviewSummaryJson(null);
        product.setReviewsJson(null);
        product.setTrustJson(trimToNull(request.getTrustJson()));
        product.setFaqJson(trimToNull(request.getFaqJson()));
        product.setPhoneCompareJson(trimToNull(request.getPhoneCompareJson()));
        product.setShareYieldMin(request.getShareYieldMin());
        product.setShareYieldMax(request.getShareYieldMax());
        product.setSupersededByProductNo(trimToNull(request.getSupersededByProductNo()));
        product.setUnlockPhase(normalizeGenerationGate(request.getUnlockPhase(), product.getGeneration()));
        product.setSoldCount(0);
        product.setRatingValue(BigDecimal.ZERO);
        product.setReviewCount(0);
    }

    private void applyUpdateStoreFields(Product product, ProductUpdateRequest request) {
        if (request.getBadge() != null) {
            product.setBadge(trimToEmpty(request.getBadge()));
        }
        if (request.getTagline() != null) {
            product.setTagline(trimToEmpty(request.getTagline()));
        }
        if (request.getStoreStatus() != null) {
            product.setStoreStatus(trimToEmpty(request.getStoreStatus()));
        }
        if (request.getStoreVisible() != null) {
            product.setStoreVisible(request.getStoreVisible());
            if (!Integer.valueOf(1).equals(product.getStoreVisible())) {
                product.setStoreFeatured(0);
            }
        }
        if (request.getSortOrder() != null) {
            product.setSortOrder(request.getSortOrder());
        }
        if (request.getGeneration() != null) {
            product.setGeneration(request.getGeneration());
        }
        if (request.getGpuModel() != null) {
            product.setGpuModel(trimToEmpty(request.getGpuModel()));
        }
        if (request.getVramTotalGb() != null) {
            product.setVramTotalGb(request.getVramTotalGb());
        }
        if (request.getAiPerformanceJson() != null) {
            product.setAiPerformanceJson(trimToEmpty(request.getAiPerformanceJson()));
        }
        if (request.getDetailMetricsJson() != null) {
            product.setDetailMetricsJson(trimToEmpty(request.getDetailMetricsJson()));
        }
        if (request.getHardwareSpecsJson() != null) {
            product.setHardwareSpecsJson(trimToEmpty(request.getHardwareSpecsJson()));
        }
        product.setReviewSummaryJson(null);
        product.setReviewsJson(null);
        if (request.getTrustJson() != null) {
            product.setTrustJson(trimToEmpty(request.getTrustJson()));
        }
        if (request.getFaqJson() != null) {
            product.setFaqJson(trimToEmpty(request.getFaqJson()));
        }
        if (request.getPhoneCompareJson() != null) {
            product.setPhoneCompareJson(trimToEmpty(request.getPhoneCompareJson()));
        }
        if (request.getShareYieldMin() != null) {
            product.setShareYieldMin(request.getShareYieldMin());
        }
        if (request.getShareYieldMax() != null) {
            product.setShareYieldMax(request.getShareYieldMax());
        }
        if (request.getSupersededByProductNo() != null) {
            product.setSupersededByProductNo(trimToEmpty(request.getSupersededByProductNo()));
        }
        if (request.getUnlockPhase() != null) {
            product.setUnlockPhase(normalizeGenerationGate(request.getUnlockPhase(), product.getGeneration()));
        }
    }

    private StoreProductResponse toStoreProduct(Product product, List<TradeinRule> tradeinRules) {
        StoreProductStats stats = storeProductStats(product.getId());
        StoreProductResponse response = new StoreProductResponse();
        response.setId(product.getProductNo());
        response.setProductId(product.getId());
        response.setProductNo(product.getProductNo());
        response.setName(product.getName());
        response.setTier(product.getTier());
        response.setBadge(product.getBadge());
        response.setTagline(defaultText(product.getTagline(), product.getName()));
        response.setPhoto(storePhotoUrl(product.getCoverUrl()));
        response.setDetailMediaUrls(storeDetailMediaUrls(product.getDetailImageUrls()));
        response.setFeatured(Integer.valueOf(1).equals(product.getStoreFeatured()));
        response.setTierCode(defaultText(product.getTier(), product.getProductNo()));
        response.setDailyEarn(defaultDecimal(product.getEstimatedDailyUsdt()));
        response.setDailyEarnNEX(defaultDecimal(product.getDailyNex()));
        response.setAnnualROI(calculateAnnualRoi(product));
        response.setPrice(defaultDecimal(product.getPriceUsdt()));
        response.setSold(stats.sold());
        response.setStock(defaultInteger(product.getStock(), 0));
        response.setRating(stats.rating());
        response.setReviews(stats.reviews());
        response.setGpu(defaultText(product.getGpuModel(), product.getProductType()));
        response.setVram(product.getVramTotalGb() == null ? null : product.getVramTotalGb() + "GB VRAM");
        response.setAi(parseAiPerformance(product.getAiPerformanceJson()));
        response.setDetailMetrics(parseJson(product.getDetailMetricsJson()));
        response.setHardwareSpecs(parseJson(product.getHardwareSpecsJson()));
        response.setReviewSummary(parseJson(product.getReviewSummaryJson()));
        response.setReviewItems(parseJson(product.getReviewsJson()));
        response.setTrust(parseJson(product.getTrustJson()));
        response.setFaqItems(parseJson(product.getFaqJson()));
        response.setPhoneCompare(parseJson(product.getPhoneCompareJson()));
        response.setShareYieldMin(product.getShareYieldMin());
        response.setShareYieldMax(product.getShareYieldMax());
        response.setGeneration(defaultInteger(product.getGeneration(), 1));
        response.setStatus(storeStatus(product));
        response.setSupersededBy(product.getSupersededByProductNo());
        response.setTradeinCredit(findTradeinCredit(product, tradeinRules));
        response.setUnlocksAtPhase(product.getUnlockPhase());
        return response;
    }

    private StoreProductStats storeProductStats(Long productId) {
        if (productId == null) {
            return new StoreProductStats(0, BigDecimal.ZERO, 0);
        }
        int sold = 0;
        if (orderItemMapper != null) {
            List<CommerceOrderItem> paidItems = orderItemMapper.selectList(new LambdaQueryWrapper<CommerceOrderItem>()
                    .eq(CommerceOrderItem::getProductId, productId)
                    .eq(CommerceOrderItem::getIsDeleted, 0)
                    .inSql(CommerceOrderItem::getOrderNo,
                            "SELECT order_no FROM nx_order WHERE is_deleted = 0 AND payment_status = '" + PAYMENT_PAID + "'"));
            if (paidItems != null) {
                sold = paidItems.stream()
                        .map(CommerceOrderItem::getQuantity)
                        .filter(Objects::nonNull)
                        .reduce(0, Integer::sum);
            }
        }
        if (sold == 0) {
            List<CommerceOrder> paidOrders = orderMapper.selectList(new LambdaQueryWrapper<CommerceOrder>()
                    .eq(CommerceOrder::getProductId, productId)
                    .eq(CommerceOrder::getIsDeleted, 0)
                    .eq(CommerceOrder::getPaymentStatus, PAYMENT_PAID));
            if (paidOrders != null) {
                sold = paidOrders.stream()
                        .map(CommerceOrder::getQuantity)
                        .filter(Objects::nonNull)
                        .reduce(0, Integer::sum);
            }
        }

        List<ProductReview> visibleReviews = reviewMapper == null
                ? List.of()
                : reviewMapper.selectList(new LambdaQueryWrapper<ProductReview>()
                        .eq(ProductReview::getProductId, productId)
                        .eq(ProductReview::getIsDeleted, 0)
                        .eq(ProductReview::getStatus, REVIEW_VISIBLE));
        if (visibleReviews == null) {
            visibleReviews = List.of();
        }
        BigDecimal totalRating = visibleReviews.stream()
                .map(ProductReview::getRating)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averageRating = visibleReviews.isEmpty()
                ? BigDecimal.ZERO
                : totalRating.divide(BigDecimal.valueOf(visibleReviews.size()), 2, RoundingMode.HALF_UP);
        return new StoreProductStats(sold, averageRating, visibleReviews.size());
    }

    private String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private List<String> phaseCodesThrough(String currentPhase) {
        int phaseIndex = phaseIndex(currentPhase);
        return java.util.stream.IntStream.rangeClosed(1, phaseIndex)
                .mapToObj(index -> "P" + index)
                .toList();
    }

    private Product findFeaturedReplacement(List<Product> products, int currentIndex) {
        return products.stream()
                .filter(product -> isFeaturedEligible(product, "P" + currentIndex))
                .filter(product -> phaseIndex(product.getUnlockPhase()) == currentIndex)
                .findFirst()
                .orElseGet(() -> products.stream()
                        .filter(product -> isFeaturedEligible(product, "P" + currentIndex))
                        .sorted((left, right) -> Integer.compare(phaseIndex(right.getUnlockPhase()), phaseIndex(left.getUnlockPhase())))
                        .findFirst()
                        .orElse(null));
    }

    private boolean isFeaturedEligible(Product product, String currentPhase) {
        return product != null
                && PRODUCT_ON_SALE.equals(product.getStatus())
                && Integer.valueOf(1).equals(product.getStoreVisible())
                && phaseCodesThrough(currentPhase).contains(normalizeGenerationGate(product.getUnlockPhase()));
    }

    private int phaseIndex(String phase) {
        String normalized = normalizeGenerationGate(phase);
        if (normalized == null || !normalized.matches("P\\d+")) {
            return 1;
        }
        int index = Integer.parseInt(normalized.substring(1));
        return Math.max(index, 1);
    }

    private String storePhotoUrl(String coverUrl) {
        if (!StringUtils.hasText(coverUrl)) {
            return "";
        }
        String trimmed = coverUrl.trim();
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || trimmed.startsWith("/")) {
            return trimmed;
        }
        if (productMediaService == null || !isStoreMediaObjectKey(trimmed)) {
            return trimmed;
        }
        try {
            String downloadUrl = productMediaService.preview(trimmed).getDownloadUrl();
            return StringUtils.hasText(downloadUrl) ? downloadUrl : trimmed;
        } catch (RuntimeException ex) {
            return trimmed;
        }
    }

    private List<String> storeDetailMediaUrls(String detailImageUrls) {
        List<String> keys = parseMediaKeys(detailImageUrls);
        if (keys.isEmpty()) {
            return List.of();
        }
        return keys.stream().map(this::storePhotoUrl).filter(StringUtils::hasText).toList();
    }

    private List<String> parseMediaKeys(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            try {
                List<?> parsed = objectMapper.readValue(trimmed, List.class);
                return parsed.stream()
                        .map(item -> item == null ? "" : String.valueOf(item).trim())
                        .filter(StringUtils::hasText)
                        .toList();
            } catch (JsonProcessingException ex) {
                // Fall back to legacy comma-separated rows below.
            }
        }
        List<String> keys = new ArrayList<>();
        for (String item : trimmed.split(",")) {
            String key = item.trim();
            if (StringUtils.hasText(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private boolean isStoreMediaObjectKey(String value) {
        return value.startsWith("commerce/products/") || value.startsWith("commerce/genesis/");
    }

    private String normalizeStoreMediaObjectKey(String value, String fieldName, boolean blankAsEmpty) {
        if (!StringUtils.hasText(value)) {
            return blankAsEmpty ? "" : null;
        }
        String key = value.trim();
        validateStoreMediaObjectKey(key, fieldName);
        return key;
    }

    private String normalizeStoreMediaObjectKeys(String value, String fieldName, boolean blankAsEmpty) {
        if (!StringUtils.hasText(value)) {
            return blankAsEmpty ? "" : null;
        }
        List<String> keys = parseMediaKeys(value).stream()
                .distinct()
                .toList();
        if (keys.isEmpty()) {
            return blankAsEmpty ? "" : null;
        }
        keys.forEach(key -> validateStoreMediaObjectKey(key, fieldName));
        try {
            return objectMapper.writeValueAsString(keys);
        } catch (JsonProcessingException ex) {
            throw new BizException(fieldName + " object keys are invalid");
        }
    }

    private void validateStoreMediaObjectKey(String key, String fieldName) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || key.startsWith("/")) {
            throw new BizException(fieldName + " must be uploaded through MinIO and saved as an object key");
        }
        if (!isStoreMediaObjectKey(key)
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

    private Integer calculateAnnualRoi(Product product) {
        BigDecimal price = defaultDecimal(product.getPriceUsdt());
        BigDecimal daily = defaultDecimal(product.getEstimatedDailyUsdt());
        if (price.compareTo(BigDecimal.ZERO) <= 0 || daily.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return daily.multiply(BigDecimal.valueOf(36500))
                .divide(price, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    private String storeStatus(Product product) {
        if (StringUtils.hasText(product.getStoreStatus())) {
            return product.getStoreStatus().trim().toLowerCase();
        }
        if (StringUtils.hasText(product.getUnlockPhase())) {
            return "active";
        }
        if ("ARCHIVED".equals(product.getStatus()) || StringUtils.hasText(product.getSupersededByProductNo())) {
            return "legacy";
        }
        return "active";
    }

    private StoreAiPerformance parseAiPerformance(String json) {
        if (!StringUtils.hasText(json)) {
            return new StoreAiPerformance();
        }
        try {
            return objectMapper.readValue(json, StoreAiPerformance.class);
        } catch (JsonProcessingException ex) {
            return new StoreAiPerformance();
        }
    }

    private Object parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private BigDecimal findTradeinCredit(Product product, List<TradeinRule> rules) {
        return rules.stream()
                .filter(rule -> matchesTradeinRule(rule, product))
                .map(TradeinRule::getDiscountUsdt)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private boolean matchesTradeinRule(TradeinRule rule, Product product) {
        String sourceProductNo = rule.getSourceProductNo();
        String sourceTier = rule.getSourceTier();
        return (StringUtils.hasText(sourceProductNo) && sourceProductNo.equals(product.getProductNo()))
                || (StringUtils.hasText(sourceTier) && sourceTier.equals(product.getTier()));
    }

    private ComputeDeviceActivateRequest buildActivateRequest(CommerceOrder order, Product product, Integer quantity) {
        ComputeDeviceActivateRequest request = new ComputeDeviceActivateRequest();
        request.setUserId(order.getUserId());
        request.setSourceOrderNo(order.getOrderNo());
        request.setProductId(product.getId());
        request.setProductCode(product.getProductNo());
        request.setProductTier(product.getTier());
        request.setProductName(product.getName());
        request.setDeviceType(product.getProductType());
        request.setPriceUsdtSnapshot(product.getPriceUsdt());
        request.setSourceChannel("ORDER");
        request.setHashrate(product.getHashrate());
        request.setDailyUsdt(product.getEstimatedDailyUsdt());
        request.setDailyNex(product.getDailyNex());
        request.setQuantity(quantity == null ? 1 : quantity);
        return request;
    }

    private void publishOrderPaidEvent(CommerceOrder order) {
        attachOrderItems(order);
        Product product = getProduct(order.getProductId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("userId", order.getUserId());
        payload.put("orderType", order.getOrderType());
        payload.put("itemCount", order.getItemCount());
        payload.put("productId", order.getProductId());
        payload.put("productCode", product.getProductNo());
        payload.put("productTier", product.getTier());
        payload.put("productName", product.getName());
        payload.put("deviceType", product.getProductType());
        payload.put("priceUsdtSnapshot", product.getPriceUsdt());
        payload.put("sourceChannel", "ORDER");
        payload.put("hashrate", product.getHashrate());
        payload.put("dailyUsdt", product.getEstimatedDailyUsdt());
        payload.put("dailyNex", product.getDailyNex());
        payload.put("quantity", order.getQuantity());
        payload.put("subtotalUsdt", order.getSubtotalUsdt());
        payload.put("discountUsdt", order.getDiscountUsdt());
        payload.put("amountUsdt", order.getAmountUsdt());
        payload.put("paymentNo", order.getPaymentNo());
        payload.put("paidAt", order.getPaidAt());
        if (order.getItems() != null && !order.getItems().isEmpty()) {
            payload.put("items", order.getItems().stream().map(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("productId", item.getProductId());
                row.put("productNo", item.getProductNo());
                row.put("productName", item.getProductName());
                row.put("quantity", item.getQuantity());
                row.put("unitPriceUsdt", item.getUnitPriceUsdt());
                row.put("lineAmountUsdt", item.getLineAmountUsdt());
                return row;
            }).toList());
        }
        outboxService.publish("ORDER", order.getOrderNo(), "OrderPaid", payload);
    }

    private void markActivationFailed(CommerceOrder order) {
        CommerceOrder patch = new CommerceOrder();
        patch.setId(order.getId());
        patch.setActivationStatus(ACTIVATION_FAILED);
        orderMapper.updateById(patch);
        order.setActivationStatus(ACTIVATION_FAILED);
    }

    private record StoreProductStats(Integer sold, BigDecimal rating, Integer reviews) {
    }

    private record OrderLineDraft(Product product, Integer quantity, BigDecimal lineAmount) {
    }
}
