package ffdd.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import ffdd.commerce.client.ComputeClient;
import ffdd.commerce.domain.Product;
import ffdd.commerce.domain.TradeinApplication;
import ffdd.commerce.domain.TradeinRule;
import ffdd.commerce.dto.TradeinQuoteRequest;
import ffdd.commerce.dto.TradeinQuoteResponse;
import ffdd.commerce.dto.TradeinSubmitRequest;
import ffdd.commerce.mapper.ProductMapper;
import ffdd.commerce.mapper.TradeinApplicationMapper;
import ffdd.commerce.mapper.TradeinRuleMapper;
import ffdd.common.api.ApiResult;
import ffdd.common.exception.BizException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TradeinServiceTest {
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final TradeinApplicationMapper applicationMapper = mock(TradeinApplicationMapper.class);
    private final TradeinRuleMapper tradeinRuleMapper = mock(TradeinRuleMapper.class);
    private final ComputeClient computeClient = mock(ComputeClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final TradeinService service = new TradeinService(productMapper, applicationMapper, tradeinRuleMapper, computeClient, clock);

    @Test
    void quotesLegacyS1ToProV2WithSalvageAndDiscount() {
        when(computeClient.getDevice(100L)).thenReturn(ApiResult.ok(device(100L, 9001L, 1L, "2026-01-26T08:00:00")));
        when(computeClient.getDeviceLifecycle(100L)).thenReturn(ApiResult.ok(lifecycle(4, "0.831652")));
        when(productMapper.selectById(1L)).thenReturn(product(1L, "NX-S1", "NexionBox S1", "S1", "299.00"));
        when(tradeinRuleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule("NX-S1", "S1", "PRO_V2", "300.000000", "0.300000", 1)));
        when(productMapper.selectOne(any(Wrapper.class)))
                .thenReturn(product(4L, "NX-PRO-V2", "NexionBox Pro v2", "PRO_V2", "2639.00"));

        TradeinQuoteRequest request = new TradeinQuoteRequest();
        request.setUserId(9001L);
        request.setSourceDeviceId(100L);

        TradeinQuoteResponse response = service.quote(request);

        assertThat(response.isEligible()).isTrue();
        assertThat(response.getSourceDeviceId()).isEqualTo(100L);
        assertThat(response.getTargetProductId()).isEqualTo(4L);
        assertThat(response.getMonthsOwned()).isEqualTo(4);
        assertThat(response.getCurrentEfficiency()).isEqualByComparingTo("0.831652");
        assertThat(response.getTradeinDiscountUsdt()).isEqualByComparingTo("300.000000");
        assertThat(response.getSalvageValueUsdt()).isEqualByComparingTo("74.599184");
        assertThat(response.getNetUpgradeCostUsdt()).isEqualByComparingTo("2264.400816");
    }

    @Test
    void rejectsDeviceOwnedByAnotherUser() {
        when(computeClient.getDevice(100L)).thenReturn(ApiResult.ok(device(100L, 9002L, 1L, "2026-01-26T08:00:00")));

        TradeinQuoteRequest request = new TradeinQuoteRequest();
        request.setUserId(9001L);
        request.setSourceDeviceId(100L);

        assertThatThrownBy(() -> service.quote(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void rejectsNonLegacyProduct() {
        when(computeClient.getDevice(100L)).thenReturn(ApiResult.ok(device(100L, 9001L, 4L, "2026-01-26T08:00:00")));
        when(productMapper.selectById(4L)).thenReturn(product(4L, "NX-PRO-V2", "NexionBox Pro v2", "PRO_V2", "2639.00"));
        when(tradeinRuleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule("NX-S1", "S1", "PRO_V2", "300.000000", "0.300000", 1)));

        TradeinQuoteRequest request = new TradeinQuoteRequest();
        request.setUserId(9001L);
        request.setSourceDeviceId(100L);

        assertThatThrownBy(() -> service.quote(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("not eligible");
    }

    @Test
    void submitCreatesTradeinApplicationFromFreshQuote() {
        when(computeClient.getDevice(100L)).thenReturn(ApiResult.ok(device(100L, 9001L, 1L, "2026-01-26T08:00:00")));
        when(computeClient.getDeviceLifecycle(100L)).thenReturn(ApiResult.ok(lifecycle(4, "0.831652")));
        when(productMapper.selectById(1L)).thenReturn(product(1L, "NX-S1", "NexionBox S1", "S1", "299.00"));
        when(tradeinRuleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule("NX-S1", "S1", "PRO_V2", "300.000000", "0.300000", 1)));
        when(productMapper.selectOne(any(Wrapper.class)))
                .thenReturn(product(4L, "NX-PRO-V2", "NexionBox Pro v2", "PRO_V2", "2639.00"));

        TradeinSubmitRequest request = new TradeinSubmitRequest();
        request.setUserId(9001L);
        request.setSourceDeviceId(100L);

        TradeinApplication response = service.submit(request);

        ArgumentCaptor<TradeinApplication> captor = ArgumentCaptor.forClass(TradeinApplication.class);
        verify(applicationMapper).insert(captor.capture());
        TradeinApplication inserted = captor.getValue();
        assertThat(inserted.getTradeinNo()).startsWith("TRI-");
        assertThat(inserted.getUserId()).isEqualTo(9001L);
        assertThat(inserted.getSourceProductId()).isEqualTo(1L);
        assertThat(inserted.getTargetProductId()).isEqualTo(4L);
        assertThat(inserted.getStatus()).isEqualTo("SUBMITTED");
        assertThat(inserted.getNetUpgradeCostUsdt()).isEqualByComparingTo("2264.400816");
        assertThat(response).isSameAs(inserted);
    }

    @Test
    void rejectsWhenLifecycleIsMissing() {
        when(computeClient.getDevice(100L)).thenReturn(ApiResult.ok(device(100L, 9001L, 1L, "2026-01-26T08:00:00")));
        when(computeClient.getDeviceLifecycle(100L)).thenReturn(ApiResult.ok(Map.of()));
        when(productMapper.selectById(1L)).thenReturn(product(1L, "NX-S1", "NexionBox S1", "S1", "299.00"));
        when(tradeinRuleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule("NX-S1", "S1", "PRO_V2", "300.000000", "0.300000", 1)));
        when(productMapper.selectOne(any(Wrapper.class)))
                .thenReturn(product(4L, "NX-PRO-V2", "NexionBox Pro v2", "PRO_V2", "2639.00"));

        TradeinQuoteRequest request = new TradeinQuoteRequest();
        request.setUserId(9001L);
        request.setSourceDeviceId(100L);

        assertThatThrownBy(() -> service.quote(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("lifecycle");
    }

    @Test
    void rejectsBeforeMinimumHoldingMonths() {
        when(computeClient.getDevice(100L)).thenReturn(ApiResult.ok(device(100L, 9001L, 1L, "2026-05-01T08:00:00")));
        when(computeClient.getDeviceLifecycle(100L)).thenReturn(ApiResult.ok(lifecycle(0, "1.000000")));
        when(productMapper.selectById(1L)).thenReturn(product(1L, "NX-S1", "NexionBox S1", "S1", "299.00"));
        when(tradeinRuleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule("NX-S1", "S1", "PRO_V2", "300.000000", "0.300000", 1)));
        when(productMapper.selectOne(any(Wrapper.class)))
                .thenReturn(product(4L, "NX-PRO-V2", "NexionBox Pro v2", "PRO_V2", "2639.00"));

        TradeinQuoteRequest request = new TradeinQuoteRequest();
        request.setUserId(9001L);
        request.setSourceDeviceId(100L);

        assertThatThrownBy(() -> service.quote(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("not eligible");
    }

    private Map<String, Object> device(Long deviceId, Long userId, Long productId, String activatedAt) {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("id", deviceId);
        device.put("userId", userId);
        device.put("productId", productId);
        device.put("instanceNo", "UD-" + deviceId);
        device.put("name", "Device " + deviceId);
        device.put("activatedAt", activatedAt);
        return device;
    }

    private Map<String, Object> lifecycle(int monthsOwned, String currentEfficiency) {
        Map<String, Object> lifecycle = new LinkedHashMap<>();
        lifecycle.put("monthsOwned", monthsOwned);
        lifecycle.put("currentEfficiency", currentEfficiency);
        return lifecycle;
    }

    private Product product(Long id, String productNo, String name, String tier, String price) {
        Product product = new Product();
        product.setId(id);
        product.setProductNo(productNo);
        product.setName(name);
        product.setTier(tier);
        product.setStatus("ON_SALE");
        product.setPriceUsdt(new BigDecimal(price));
        product.setIsDeleted(0);
        return product;
    }

    private TradeinRule rule(String sourceProductNo, String sourceTier, String targetTier, String discount, String salvageRate, int minHoldingMonths) {
        TradeinRule rule = new TradeinRule();
        rule.setSourceProductNo(sourceProductNo);
        rule.setSourceTier(sourceTier);
        rule.setTargetTier(targetTier);
        rule.setDiscountUsdt(new BigDecimal(discount));
        rule.setSalvageRate(new BigDecimal(salvageRate));
        rule.setMinHoldingMonths(minHoldingMonths);
        rule.setStatus(1);
        rule.setSortOrder(10);
        rule.setIsDeleted(0);
        return rule;
    }
}
