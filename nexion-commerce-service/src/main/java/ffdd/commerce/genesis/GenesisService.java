package ffdd.commerce.genesis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.commerce.client.CommerceComplianceClient;
import ffdd.commerce.client.CommerceWalletClient;
import ffdd.commerce.client.SystemConfigClient;
import ffdd.commerce.client.dto.ComplianceGateRequest;
import ffdd.commerce.client.dto.ComplianceGateResponse;
import ffdd.commerce.client.dto.PostWalletDebitRequest;
import ffdd.commerce.client.dto.WalletLedgerResponse;
import ffdd.commerce.genesis.domain.GenesisHolding;
import ffdd.commerce.genesis.domain.GenesisOrder;
import ffdd.commerce.genesis.domain.GenesisSeries;
import ffdd.commerce.genesis.dto.GenesisHoldingQueryRequest;
import ffdd.commerce.genesis.dto.GenesisOrderQueryRequest;
import ffdd.commerce.genesis.dto.GenesisPurchaseRequest;
import ffdd.commerce.genesis.dto.GenesisSeriesCreateRequest;
import ffdd.commerce.genesis.dto.GenesisSeriesUpdateRequest;
import ffdd.commerce.genesis.mapper.GenesisHoldingMapper;
import ffdd.commerce.genesis.mapper.GenesisOrderMapper;
import ffdd.commerce.genesis.mapper.GenesisSeriesMapper;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.common.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class GenesisService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ASSET_USDT = "USDT";
    private static final String BIZ_TYPE_GENESIS = "GENESIS";
    private static final String BIZ_TYPE_GENESIS_PURCHASE = "GENESIS_PURCHASE";
    private static final String SERIES_ACTIVE = "ACTIVE";
    private static final String DECISION_APPROVE = "APPROVE";
    private static final String DECISION_REVIEW = "REVIEW";
    private static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    private static final String STATUS_REVIEWING = "REVIEWING";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String HOLDING_ACTIVE = "ACTIVE";
    private static final int MAX_CLIENT_REQUEST_NO_LENGTH = 96;
    private static final int MAX_QUANTITY = 10;

    private final GenesisSeriesMapper seriesMapper;
    private final GenesisOrderMapper orderMapper;
    private final GenesisHoldingMapper holdingMapper;
    private final CommerceComplianceClient complianceClient;
    private final CommerceWalletClient walletClient;
    private final SystemConfigClient systemConfigClient;
    private final EventOutboxService outboxService;
    private final Clock clock;

    @Autowired
    public GenesisService(
            GenesisSeriesMapper seriesMapper,
            GenesisOrderMapper orderMapper,
            GenesisHoldingMapper holdingMapper,
            CommerceComplianceClient complianceClient,
            CommerceWalletClient walletClient,
            SystemConfigClient systemConfigClient,
            EventOutboxService outboxService) {
        this(seriesMapper, orderMapper, holdingMapper, complianceClient, walletClient, systemConfigClient, outboxService, Clock.systemDefaultZone());
    }

    GenesisService(
            GenesisSeriesMapper seriesMapper,
            GenesisOrderMapper orderMapper,
            GenesisHoldingMapper holdingMapper,
            CommerceComplianceClient complianceClient,
            CommerceWalletClient walletClient,
            SystemConfigClient systemConfigClient,
            EventOutboxService outboxService,
            Clock clock) {
        this.seriesMapper = seriesMapper;
        this.orderMapper = orderMapper;
        this.holdingMapper = holdingMapper;
        this.complianceClient = complianceClient;
        this.walletClient = walletClient;
        this.systemConfigClient = systemConfigClient;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    public Map<String, Object> overview(Long userId) {
        Long visibleUserId = checkedUserId(userId);
        List<GenesisSeries> series = seriesMapper.selectList(new LambdaQueryWrapper<GenesisSeries>()
                .eq(GenesisSeries::getIsDeleted, 0)
                .eq(GenesisSeries::getStatus, SERIES_ACTIVE)
                .orderByAsc(GenesisSeries::getId));
        Map<String, Object> overview = section(
                "service", "nexion-commerce-service",
                "module", "commerce.genesis",
                "series", series);
        if (visibleUserId != null) {
            overview.put("userHoldings", countHoldings(visibleUserId));
        }
        return overview;
    }

    @Transactional(rollbackFor = Exception.class)
    public GenesisOrder purchase(GenesisPurchaseRequest request) {
        validatePurchase(request);
        ensureGenesisEnabled();
        ensureAuthenticatedUserCanAct(request.getUserId());
        GenesisOrder existing = findExistingClientOrder(request.getUserId(), request.getClientRequestNo());
        if (existing != null) {
            return existing;
        }

        GenesisSeries series = requirePurchasableSeries(request.getSeriesCode(), quantity(request));
        BigDecimal unitPrice = money(series.getPriceUsdt());
        BigDecimal amount = money(unitPrice.multiply(BigDecimal.valueOf(quantity(request))));
        String orderNo = nextOrderNo();
        ComplianceGateResponse gate = checkCompliance(request.getUserId(), orderNo, amount);

        GenesisOrder order = new GenesisOrder();
        order.setOrderNo(orderNo);
        order.setClientRequestNo(normalizeClientRequestNo(request.getClientRequestNo()));
        order.setUserId(request.getUserId());
        order.setSeriesCode(series.getSeriesCode());
        order.setQuantity(quantity(request));
        order.setUnitPriceUsdt(unitPrice);
        order.setAmountUsdt(amount);
        order.setPaymentAsset(ASSET_USDT);
        order.setRiskDecisionId(gate.getDecisionId());
        order.setStatus(statusForGate(gate));
        order.setIsDeleted(0);
        if (!insertOrder(order)) {
            return order;
        }

        if (!DECISION_APPROVE.equals(gate.getDecision())) {
            return order;
        }

        claimSupply(series, order.getQuantity());
        WalletLedgerResponse ledger = debitWallet(order);
        allocateHoldings(order);
        completeOrder(order, ledger);
        publishGenesisPurchased(order);
        return order;
    }

    public PageResult<GenesisOrder> pageOrders(GenesisOrderQueryRequest request) {
        GenesisOrderQueryRequest query = request == null ? new GenesisOrderQueryRequest() : request;
        Long visibleUserId = checkedUserId(query.getUserId());
        Page<GenesisOrder> page = orderMapper.selectPage(Page.of(normalizePageNum(query.getPageNum()), normalizePageSize(query.getPageSize())),
                new LambdaQueryWrapper<GenesisOrder>()
                        .eq(GenesisOrder::getIsDeleted, 0)
                        .eq(visibleUserId != null, GenesisOrder::getUserId, visibleUserId)
                        .eq(StringUtils.hasText(query.getStatus()), GenesisOrder::getStatus, normalizeStatus(query.getStatus()))
                        .orderByDesc(GenesisOrder::getCreatedAt));
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public GenesisOrder getOrder(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BizException("Genesis order no is required");
        }
        GenesisOrder order = orderMapper.selectOne(new LambdaQueryWrapper<GenesisOrder>()
                .eq(GenesisOrder::getOrderNo, orderNo)
                .eq(GenesisOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (order == null) {
            throw new BizException("Genesis order not found");
        }
        Long visibleUserId = currentAuthenticatedRoleUserId();
        if (visibleUserId != null && !Objects.equals(visibleUserId, order.getUserId())) {
            throw new BizException("Genesis order does not belong to authenticated user");
        }
        return order;
    }

    public PageResult<GenesisHolding> pageHoldings(GenesisHoldingQueryRequest request) {
        GenesisHoldingQueryRequest query = request == null ? new GenesisHoldingQueryRequest() : request;
        Long visibleUserId = checkedUserId(query.getUserId());
        Page<GenesisHolding> page = holdingMapper.selectPage(
                Page.of(normalizePageNum(query.getPageNum()), normalizePageSize(query.getPageSize())),
                new LambdaQueryWrapper<GenesisHolding>()
                        .eq(GenesisHolding::getIsDeleted, 0)
                        .eq(visibleUserId != null, GenesisHolding::getUserId, visibleUserId)
                        .eq(StringUtils.hasText(query.getSeriesCode()), GenesisHolding::getSeriesCode, normalizeSeriesCode(query.getSeriesCode()))
                        .orderByDesc(GenesisHolding::getCreatedAt));
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public PageResult<GenesisSeries> pageSeries(Long pageNum, Long pageSize, String status) {
        Page<GenesisSeries> page = seriesMapper.selectPage(
                Page.of(normalizePageNum(pageNum), normalizePageSize(pageSize)),
                new LambdaQueryWrapper<GenesisSeries>()
                        .eq(GenesisSeries::getIsDeleted, 0)
                        .eq(StringUtils.hasText(status), GenesisSeries::getStatus, normalizeStatus(status))
                        .orderByDesc(GenesisSeries::getCreatedAt));
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public GenesisSeries createSeries(GenesisSeriesCreateRequest request) {
        if (request == null) {
            throw new BizException("Genesis series request is required");
        }
        GenesisSeries series = new GenesisSeries();
        series.setSeriesCode(normalizeSeriesCode(requireText(request.getSeriesCode(), "Genesis series code is required")));
        series.setName(requireText(request.getName(), "Genesis series name is required"));
        series.setTotalSupply(requirePositiveInt(request.getTotalSupply(), "Genesis total supply must be positive"));
        series.setSoldSupply(0);
        series.setPriceUsdt(money(request.getPriceUsdt()));
        series.setStatus(StringUtils.hasText(request.getStatus()) ? normalizeStatus(request.getStatus()) : SERIES_ACTIVE);
        series.setSaleStartAt(request.getSaleStartAt());
        series.setSaleEndAt(request.getSaleEndAt());
        series.setRoyaltyBps(request.getRoyaltyBps() == null ? 0 : request.getRoyaltyBps());
        series.setCoverUrl(normalizeMediaObjectKey(request.getCoverUrl(), "Genesis cover object key"));
        series.setMetadataJson(buildSeriesMetadataJson(
                request.getMetadataJson(),
                request.getDescription(),
                request.getDividendLabel(),
                request.getUtilityLabel(),
                request.getRarityLabel(),
                request.getTraits(),
                request.getMediaObjectKeys()));
        series.setIsDeleted(0);
        try {
            seriesMapper.insert(series);
            return series;
        } catch (DuplicateKeyException ex) {
            throw new BizException(409, "Genesis series code already exists");
        }
    }

    public GenesisSeries updateSeries(Long id, GenesisSeriesUpdateRequest request) {
        if (id == null) {
            throw new BizException("Genesis series id is required");
        }
        if (request == null) {
            throw new BizException("Genesis series request is required");
        }
        GenesisSeries series = seriesMapper.selectById(id);
        if (series == null || Integer.valueOf(1).equals(series.getIsDeleted())) {
            throw new BizException(404, "Genesis series not found");
        }
        if (request.getName() != null) {
            series.setName(requireText(request.getName(), "Genesis series name is required"));
        }
        if (request.getTotalSupply() != null) {
            int totalSupply = requirePositiveInt(request.getTotalSupply(), "Genesis total supply must be positive");
            if (series.getSoldSupply() != null && totalSupply < series.getSoldSupply()) {
                throw new BizException("Genesis total supply cannot be less than sold supply");
            }
            series.setTotalSupply(totalSupply);
        }
        if (request.getPriceUsdt() != null) {
            series.setPriceUsdt(money(request.getPriceUsdt()));
        }
        if (request.getStatus() != null) {
            series.setStatus(normalizeStatus(request.getStatus()));
        }
        if (request.getSaleStartAt() != null) {
            series.setSaleStartAt(request.getSaleStartAt());
        }
        if (request.getSaleEndAt() != null) {
            series.setSaleEndAt(request.getSaleEndAt());
        }
        if (request.getRoyaltyBps() != null) {
            series.setRoyaltyBps(request.getRoyaltyBps());
        }
        if (request.getCoverUrl() != null) {
            series.setCoverUrl(normalizeMediaObjectKey(request.getCoverUrl(), "Genesis cover object key"));
        }
        String metadataJson = buildSeriesMetadataJson(
                request.getMetadataJson(),
                request.getDescription(),
                request.getDividendLabel(),
                request.getUtilityLabel(),
                request.getRarityLabel(),
                request.getTraits(),
                request.getMediaObjectKeys());
        if (metadataJson != null || request.getMetadataJson() != null || request.getDescription() != null
                || request.getDividendLabel() != null || request.getUtilityLabel() != null
                || request.getRarityLabel() != null || request.getTraits() != null || request.getMediaObjectKeys() != null) {
            series.setMetadataJson(metadataJson);
        }
        seriesMapper.updateById(series);
        return series;
    }

    private void validatePurchase(GenesisPurchaseRequest request) {
        if (request == null) {
            throw new BizException("Genesis purchase request is required");
        }
        if (request.getUserId() == null || request.getUserId() < 1) {
            throw new BizException("User id is required");
        }
        if (!StringUtils.hasText(request.getSeriesCode())) {
            throw new BizException("Genesis series code is required");
        }
        int quantity = quantity(request);
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BizException("Genesis purchase quantity must be between 1 and " + MAX_QUANTITY);
        }
        String clientRequestNo = normalizeClientRequestNo(request.getClientRequestNo());
        if (StringUtils.hasText(clientRequestNo)
                && (!clientRequestNo.matches("[A-Za-z0-9._:-]+")
                || clientRequestNo.length() > MAX_CLIENT_REQUEST_NO_LENGTH)) {
            throw new BizException("Client request no is invalid");
        }
    }

    private void ensureGenesisEnabled() {
        try {
            ApiResult<Map<String, Object>> response = systemConfigClient.features();
            if (response == null || response.getCode() != 0 || response.getData() == null) {
                throw new BizException("Genesis feature switch unavailable");
            }
            Object enabled = response.getData().get("genesis.enabled");
            if (enabled == null) {
                enabled = response.getData().get("genesis");
            }
            if (enabled instanceof Boolean value && !value) {
                throw new BizException("Genesis is disabled");
            }
            if (enabled instanceof String text && !"true".equalsIgnoreCase(text.trim())) {
                throw new BizException("Genesis is disabled");
            }
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException("Genesis feature switch unavailable");
        }
    }

    private void ensureAuthenticatedUserCanAct(Long userId) {
        Long subjectUserId = currentAuthenticatedRoleUserId();
        if (subjectUserId != null && !Objects.equals(subjectUserId, userId)) {
            throw new BizException("Authenticated user does not match genesis purchase user");
        }
    }

    private Long checkedUserId(Long requestedUserId) {
        Long subjectUserId = currentAuthenticatedRoleUserId();
        if (subjectUserId == null) {
            return requestedUserId;
        }
        if (requestedUserId != null && !Objects.equals(subjectUserId, requestedUserId)) {
            throw new BizException("Requested user does not match authenticated user");
        }
        return subjectUserId;
    }

    private Long currentAuthenticatedRoleUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) {
            return null;
        }
        String subject = String.valueOf(authentication.getPrincipal());
        if (!StringUtils.hasText(subject)) {
            return null;
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            throw new BizException("Authenticated user id is invalid");
        }
    }

    private GenesisOrder findExistingClientOrder(Long userId, String clientRequestNo) {
        String normalized = normalizeClientRequestNo(clientRequestNo);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        return orderMapper.selectOne(new LambdaQueryWrapper<GenesisOrder>()
                .eq(GenesisOrder::getUserId, userId)
                .eq(GenesisOrder::getClientRequestNo, normalized)
                .eq(GenesisOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private GenesisSeries requirePurchasableSeries(String seriesCode, int quantity) {
        GenesisSeries series = seriesMapper.selectOne(new LambdaQueryWrapper<GenesisSeries>()
                .eq(GenesisSeries::getSeriesCode, normalizeSeriesCode(seriesCode))
                .eq(GenesisSeries::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (series == null) {
            throw new BizException("Genesis series not found");
        }
        if (!SERIES_ACTIVE.equals(series.getStatus())) {
            throw new BizException("Genesis series is not active");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (series.getSaleStartAt() != null && series.getSaleStartAt().isAfter(now)) {
            throw new BizException("Genesis sale has not started");
        }
        if (series.getSaleEndAt() != null && !series.getSaleEndAt().isAfter(now)) {
            throw new BizException("Genesis sale has ended");
        }
        if (remainingSupply(series) < quantity) {
            throw new BizException("Genesis series sold out");
        }
        return series;
    }

    private ComplianceGateResponse checkCompliance(Long userId, String orderNo, BigDecimal amount) {
        ComplianceGateRequest request = new ComplianceGateRequest();
        request.setUserId(userId);
        request.setBizType(BIZ_TYPE_GENESIS);
        request.setBizNo(orderNo);
        request.setAsset(ASSET_USDT);
        request.setAmount(amount);
        try {
            ApiResult<ComplianceGateResponse> response = complianceClient.check(request);
            if (response == null || response.getCode() != 0 || response.getData() == null) {
                throw new BizException("Compliance gate unavailable");
            }
            return response.getData();
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException("Compliance gate unavailable");
        }
    }

    private void claimSupply(GenesisSeries series, int quantity) {
        int updated = seriesMapper.update(null, new LambdaUpdateWrapper<GenesisSeries>()
                .eq(GenesisSeries::getId, series.getId())
                .eq(GenesisSeries::getIsDeleted, 0)
                .le(GenesisSeries::getSoldSupply, nullToZero(series.getTotalSupply()) - quantity)
                .setSql("sold_supply = sold_supply + " + quantity));
        if (updated == 0) {
            throw new BizException("Genesis series sold out");
        }
    }

    private WalletLedgerResponse debitWallet(GenesisOrder order) {
        PostWalletDebitRequest request = new PostWalletDebitRequest();
        request.setUserId(order.getUserId());
        request.setBizNo(order.getOrderNo());
        request.setBizType(BIZ_TYPE_GENESIS_PURCHASE);
        request.setAsset(ASSET_USDT);
        request.setAmount(order.getAmountUsdt());
        request.setRemark("Genesis purchase " + order.getOrderNo());
        try {
            ApiResult<WalletLedgerResponse> response = walletClient.postDebit(request);
            if (response == null || response.getCode() != 0 || response.getData() == null) {
                throw new BizException("Wallet debit failed");
            }
            return response.getData();
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException("Wallet debit failed");
        }
    }

    private void allocateHoldings(GenesisOrder order) {
        LocalDateTime now = LocalDateTime.now(clock);
        BigDecimal perHoldingPrice = money(order.getAmountUsdt().divide(BigDecimal.valueOf(order.getQuantity()), 6, RoundingMode.HALF_UP));
        for (int index = 1; index <= order.getQuantity(); index++) {
            GenesisHolding holding = new GenesisHolding();
            holding.setHoldingNo(order.getOrderNo() + "-" + String.format("%02d", index));
            holding.setUserId(order.getUserId());
            holding.setOrderNo(order.getOrderNo());
            holding.setSeriesCode(order.getSeriesCode());
            holding.setAcquiredPriceUsdt(perHoldingPrice);
            holding.setStatus(HOLDING_ACTIVE);
            holding.setAcquiredAt(now);
            holding.setIsDeleted(0);
            holdingMapper.insert(holding);
        }
    }

    private void completeOrder(GenesisOrder order, WalletLedgerResponse ledger) {
        LocalDateTime now = LocalDateTime.now(clock);
        GenesisOrder patch = new GenesisOrder();
        patch.setId(order.getId());
        patch.setStatus(STATUS_COMPLETED);
        patch.setWalletLedgerId(ledger.getId());
        patch.setPaidAt(now);
        patch.setCompletedAt(now);
        orderMapper.updateById(patch);
        order.setStatus(STATUS_COMPLETED);
        order.setWalletLedgerId(ledger.getId());
        order.setPaidAt(now);
        order.setCompletedAt(now);
    }

    private void publishGenesisPurchased(GenesisOrder order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("userId", order.getUserId());
        payload.put("seriesCode", order.getSeriesCode());
        payload.put("quantity", order.getQuantity());
        payload.put("amountUsdt", order.getAmountUsdt());
        payload.put("walletLedgerId", order.getWalletLedgerId());
        payload.put("completedAt", order.getCompletedAt());
        outboxService.publish("GENESIS_ORDER", order.getOrderNo(), "GenesisPurchased", payload);
    }

    private boolean insertOrder(GenesisOrder order) {
        try {
            orderMapper.insert(order);
            return true;
        } catch (DuplicateKeyException ex) {
            GenesisOrder duplicate = findExistingClientOrder(order.getUserId(), order.getClientRequestNo());
            if (duplicate == null) {
                throw ex;
            }
            copyOrder(order, duplicate);
            return false;
        }
    }

    private void copyOrder(GenesisOrder target, GenesisOrder source) {
        target.setId(source.getId());
        target.setOrderNo(source.getOrderNo());
        target.setClientRequestNo(source.getClientRequestNo());
        target.setUserId(source.getUserId());
        target.setSeriesCode(source.getSeriesCode());
        target.setQuantity(source.getQuantity());
        target.setUnitPriceUsdt(source.getUnitPriceUsdt());
        target.setAmountUsdt(source.getAmountUsdt());
        target.setPaymentAsset(source.getPaymentAsset());
        target.setStatus(source.getStatus());
        target.setRiskDecisionId(source.getRiskDecisionId());
        target.setWalletLedgerId(source.getWalletLedgerId());
        target.setFailureReason(source.getFailureReason());
        target.setPaidAt(source.getPaidAt());
        target.setCompletedAt(source.getCompletedAt());
        target.setIsDeleted(source.getIsDeleted());
    }

    private String statusForGate(ComplianceGateResponse gate) {
        if (DECISION_APPROVE.equals(gate.getDecision())) {
            return STATUS_PENDING_PAYMENT;
        }
        return DECISION_REVIEW.equals(gate.getDecision()) ? STATUS_REVIEWING : STATUS_REJECTED;
    }

    private long countHoldings(Long userId) {
        Long count = holdingMapper.selectCount(new LambdaQueryWrapper<GenesisHolding>()
                .eq(GenesisHolding::getUserId, userId)
                .eq(GenesisHolding::getIsDeleted, 0));
        return count == null ? 0 : count;
    }

    private int quantity(GenesisPurchaseRequest request) {
        return request.getQuantity() == null ? 1 : request.getQuantity();
    }

    private int remainingSupply(GenesisSeries series) {
        return Math.max(0, nullToZero(series.getTotalSupply()) - safeSold(series));
    }

    private int safeSold(GenesisSeries series) {
        return nullToZero(series.getSoldSupply());
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("Genesis price is invalid");
        }
        return value.setScale(6, RoundingMode.HALF_UP);
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

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String normalizeSeriesCode(String seriesCode) {
        return StringUtils.hasText(seriesCode) ? seriesCode.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String normalizeClientRequestNo(String clientRequestNo) {
        return StringUtils.hasText(clientRequestNo) ? clientRequestNo.trim() : null;
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

    private String buildSeriesMetadataJson(
            String fallbackJson,
            String description,
            String dividendLabel,
            String utilityLabel,
            String rarityLabel,
            List<String> traits,
            List<String> mediaObjectKeys) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putText(metadata, "description", description);
        putText(metadata, "dividendLabel", dividendLabel);
        putText(metadata, "utilityLabel", utilityLabel);
        putText(metadata, "rarityLabel", rarityLabel);
        putTextList(metadata, "traits", traits, 96, false);
        putTextList(metadata, "mediaObjectKeys", mediaObjectKeys, 255, true);
        if (metadata.isEmpty()) {
            return trimToNull(fallbackJson);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new BizException("Genesis metadata cannot be serialized");
        }
    }

    private void putText(Map<String, Object> metadata, String key, String value) {
        String text = trimToNull(value);
        if (text != null) {
            metadata.put(key, text);
        }
    }

    private void putTextList(Map<String, Object> metadata, String key, List<String> values, int maxLength, boolean mediaObjectKey) {
        if (values == null) {
            return;
        }
        List<String> normalized = values.stream()
                .map(value -> mediaObjectKey ? normalizeMediaObjectKey(value, "Genesis media object key") : normalizeBoundedText(value, key, maxLength))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!normalized.isEmpty()) {
            metadata.put(key, normalized);
        }
    }

    private String normalizeBoundedText(String value, String label, int maxLength) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        if (text.length() > maxLength || containsControlCharacters(text)) {
            throw new BizException(label + " is invalid");
        }
        return text;
    }

    private String normalizeMediaObjectKey(String value, String label) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (text.length() > 255
                || !text.startsWith("commerce/genesis/")
                || text.startsWith("/")
                || text.endsWith("/")
                || lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.contains("..")
                || text.indexOf('\\') >= 0
                || containsControlCharacters(text)) {
            throw new BizException(label + " must be a MinIO object key under commerce/genesis");
        }
        return text;
    }

    private boolean containsControlCharacters(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\t') >= 0;
    }

    private int requirePositiveInt(Integer value, String message) {
        if (value == null || value < 1) {
            throw new BizException(message);
        }
        return value;
    }

    private String nextOrderNo() {
        String date = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int suffix = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return "GEN-" + date + "-" + suffix;
    }

    private Map<String, Object> section(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }
}
