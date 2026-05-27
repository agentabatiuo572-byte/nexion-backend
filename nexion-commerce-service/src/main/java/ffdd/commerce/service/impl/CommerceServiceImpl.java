package ffdd.commerce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.commerce.client.ComputeClient;
import ffdd.commerce.client.dto.ComputeDeviceActivateRequest;
import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.Product;
import ffdd.commerce.dto.OrderCreateRequest;
import ffdd.commerce.dto.OrderPayRequest;
import ffdd.commerce.dto.OrderQueryRequest;
import ffdd.commerce.dto.ProductCreateRequest;
import ffdd.commerce.dto.ProductQueryRequest;
import ffdd.commerce.dto.ProductUpdateRequest;
import ffdd.commerce.mapper.CommerceOrderMapper;
import ffdd.commerce.mapper.ProductMapper;
import ffdd.commerce.service.CommerceService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.common.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DuplicateKeyException;
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
    private static final String ACTIVATION_WAITING_PAYMENT = "WAITING_PAYMENT";
    private static final String ACTIVATION_PENDING = "PENDING";
    private static final String ACTIVATION_ACTIVATED = "ACTIVATED";
    private static final String ACTIVATION_FAILED = "FAILED";

    private final ProductMapper productMapper;
    private final CommerceOrderMapper orderMapper;
    private final ComputeClient computeClient;
    private final EventOutboxService outboxService;

    public CommerceServiceImpl(
            ProductMapper productMapper,
            CommerceOrderMapper orderMapper,
            ComputeClient computeClient,
            EventOutboxService outboxService) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.computeClient = computeClient;
        this.outboxService = outboxService;
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
        product.setCoverUrl(trimToNull(request.getCoverUrl()));
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
            product.setCoverUrl(trimToNull(request.getCoverUrl()));
        }
        productMapper.updateById(product);
        return product;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceOrder createOrder(OrderCreateRequest request) {
        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        if (quantity < 1) {
            throw new BizException("Quantity must be greater than zero");
        }

        Product product = getProduct(request.getProductId());
        if (!PRODUCT_ON_SALE.equals(product.getStatus())) {
            throw new BizException("Product is not on sale");
        }
        if (product.getStock() == null || product.getStock() < quantity) {
            throw new BizException("Insufficient stock");
        }

        int stockUpdated = productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getId, product.getId())
                .eq(Product::getIsDeleted, 0)
                .ge(Product::getStock, quantity)
                .setSql("stock = stock - " + quantity));
        if (stockUpdated == 0) {
            throw new BizException("Insufficient stock");
        }

        CommerceOrder order = new CommerceOrder();
        order.setUserId(request.getUserId());
        order.setProductId(product.getId());
        order.setQuantity(quantity);
        order.setOrderNo(nextOrderNo());
        order.setAmountUsdt(product.getPriceUsdt().multiply(BigDecimal.valueOf(quantity)));
        order.setPaymentStatus(PAYMENT_PENDING);
        order.setOrderStatus(ORDER_CREATED);
        order.setActivationStatus(ACTIVATION_WAITING_PAYMENT);
        order.setIsDeleted(0);
        orderMapper.insert(order);
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
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    public CommerceOrder getOrder(String orderNo) {
        CommerceOrder order = orderMapper.selectOne(new LambdaQueryWrapper<CommerceOrder>()
                .eq(CommerceOrder::getOrderNo, orderNo)
                .eq(CommerceOrder::getIsDeleted, 0));
        if (order == null) {
            throw new BizException("Order not found");
        }
        return order;
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

        Product product = getProduct(order.getProductId());
        CommerceOrder pendingPatch = new CommerceOrder();
        pendingPatch.setId(order.getId());
        pendingPatch.setActivationStatus(ACTIVATION_PENDING);
        orderMapper.updateById(pendingPatch);
        order.setActivationStatus(ACTIVATION_PENDING);

        try {
            ComputeDeviceActivateRequest request = buildActivateRequest(order, product);
            ApiResult<?> response = computeClient.activateDevices(request);
            if (response == null || response.getCode() != 0) {
                markActivationFailed(order);
                return order;
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

    private BigDecimal requirePositiveMoney(BigDecimal value, String message) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(message);
        }
        return value;
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private ComputeDeviceActivateRequest buildActivateRequest(CommerceOrder order, Product product) {
        ComputeDeviceActivateRequest request = new ComputeDeviceActivateRequest();
        request.setUserId(order.getUserId());
        request.setSourceOrderNo(order.getOrderNo());
        request.setProductId(product.getId());
        request.setProductTier(product.getTier());
        request.setProductName(product.getName());
        request.setDeviceType(product.getProductType());
        request.setHashrate(product.getHashrate());
        request.setDailyUsdt(product.getEstimatedDailyUsdt());
        request.setDailyNex(product.getDailyNex());
        request.setQuantity(order.getQuantity());
        return request;
    }

    private void publishOrderPaidEvent(CommerceOrder order) {
        Product product = getProduct(order.getProductId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("userId", order.getUserId());
        payload.put("productId", order.getProductId());
        payload.put("productTier", product.getTier());
        payload.put("productName", product.getName());
        payload.put("deviceType", product.getProductType());
        payload.put("hashrate", product.getHashrate());
        payload.put("dailyUsdt", product.getEstimatedDailyUsdt());
        payload.put("dailyNex", product.getDailyNex());
        payload.put("quantity", order.getQuantity());
        payload.put("amountUsdt", order.getAmountUsdt());
        payload.put("paymentNo", order.getPaymentNo());
        payload.put("paidAt", order.getPaidAt());
        outboxService.publish("ORDER", order.getOrderNo(), "OrderPaid", payload);
    }

    private void markActivationFailed(CommerceOrder order) {
        CommerceOrder patch = new CommerceOrder();
        patch.setId(order.getId());
        patch.setActivationStatus(ACTIVATION_FAILED);
        orderMapper.updateById(patch);
        order.setActivationStatus(ACTIVATION_FAILED);
    }
}
