package ffdd.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import ffdd.commerce.client.SystemConfigClient;
import ffdd.commerce.domain.PriceIndex;
import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.Product;
import ffdd.commerce.domain.ProductFaq;
import ffdd.commerce.domain.ProductReview;
import ffdd.commerce.domain.ProductSpec;
import ffdd.commerce.dto.PriceIndexRequest;
import ffdd.commerce.dto.ProductFaqRequest;
import ffdd.commerce.dto.ProductReviewRequest;
import ffdd.commerce.dto.ProductSpecRequest;
import ffdd.commerce.dto.StoreProductContentResponse;
import ffdd.commerce.mapper.PriceIndexMapper;
import ffdd.commerce.mapper.CommerceOrderMapper;
import ffdd.commerce.mapper.ProductMapper;
import ffdd.commerce.mapper.ProductFaqMapper;
import ffdd.commerce.mapper.ProductReviewMapper;
import ffdd.commerce.mapper.ProductSpecMapper;
import ffdd.common.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class ProductContentServiceTest {
    private final ProductReviewMapper reviewMapper = mock(ProductReviewMapper.class);
    private final ProductFaqMapper faqMapper = mock(ProductFaqMapper.class);
    private final ProductSpecMapper specMapper = mock(ProductSpecMapper.class);
    private final PriceIndexMapper priceIndexMapper = mock(PriceIndexMapper.class);
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final CommerceOrderMapper orderMapper = mock(CommerceOrderMapper.class);
    private final SystemConfigClient systemConfigClient = mock(SystemConfigClient.class);
    private final ProductContentService service = new ProductContentService(reviewMapper, faqMapper, specMapper, priceIndexMapper, productMapper, orderMapper, systemConfigClient);

    @Test
    void appContentAggregatesVisibleReviewsFaqsAndSpecs() {
        ProductReview five = review(1L, "5.00");
        ProductReview four = review(2L, "4.00");
        when(reviewMapper.selectList(any(Wrapper.class))).thenReturn(List.of(five, four));
        when(faqMapper.selectList(any(Wrapper.class))).thenReturn(List.of(faq(1L)));
        when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(spec(1L)));

        StoreProductContentResponse response = service.appContent(1L);

        assertThat(response.getProductId()).isEqualTo(1L);
        assertThat(response.getReviewCount()).isEqualTo(2);
        assertThat(response.getAverageRating()).isEqualByComparingTo("4.50");
        assertThat(response.getReviews()).hasSize(2);
        assertThat(response.getFaqs()).hasSize(1);
        assertThat(response.getSpecs()).hasSize(1);
    }

    @Test
    void saveReviewCreatesStructuredRow() {
        ProductReviewRequest request = new ProductReviewRequest();
        request.setProductId(1L);
        request.setRating(new BigDecimal("5.00"));
        request.setTitle("Verified buyer");
        request.setContent("Stable yield");
        request.setAvatarObjectKey("auth/users/avatar/reviewer.png");
        request.setAvatarColor("#9EDC1D");
        when(reviewMapper.selectList(any(Wrapper.class))).thenReturn(List.of(review(1L, "5.00")));

        service.saveReview(null, request);

        ArgumentCaptor<ProductReview> captor = ArgumentCaptor.forClass(ProductReview.class);
        verify(reviewMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("VISIBLE");
        assertThat(captor.getValue().getContent()).isEqualTo("Stable yield");
        assertThat(captor.getValue().getAvatarObjectKey()).isEqualTo("auth/users/avatar/reviewer.png");
        assertThat(captor.getValue().getAvatarColor()).isEqualTo("#9EDC1D");
        assertThat(captor.getValue().getIsDeleted()).isZero();

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).updateById(productCaptor.capture());
        assertThat(productCaptor.getValue().getRatingValue()).isEqualByComparingTo("5.00");
        assertThat(productCaptor.getValue().getReviewCount()).isEqualTo(1);
    }

    @Test
    void saveReviewNormalizesOperatorStatusAliases() {
        ProductReviewRequest request = new ProductReviewRequest();
        request.setProductId(1L);
        request.setRating(new BigDecimal("4.00"));
        request.setTitle("Waiting review");
        request.setContent("Submitted from ops");
        request.setStatus("pending");
        when(reviewMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.saveReview(null, request);

        ArgumentCaptor<ProductReview> captor = ArgumentCaptor.forClass(ProductReview.class);
        verify(reviewMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void submitAppReviewCreatesPendingReviewForPaidOwnedOrder() {
        CommerceOrder order = order(77L, "ORD-1", 10001L, 1L, "PAID");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(reviewMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(systemConfigClient.commerce()).thenReturn(ffdd.common.api.ApiResult.ok(Map.ofEntries(
                Map.entry("review.media_max_count", 2),
                Map.entry("review.title_max_length", 16),
                Map.entry("review.content_min_length", 10),
                Map.entry("review.content_max_length", 64))));

        ProductReviewRequest request = new ProductReviewRequest();
        request.setUserId(10001L);
        request.setOrderNo("ORD-1");
        request.setProductId(1L);
        request.setRating(new BigDecimal("4.50"));
        request.setTitle("Good");
        request.setContent("Arrived and activated.");
        request.setMediaObjectKeys(List.of(
                "commerce/products/product_review/1.jpg",
                " commerce/products/product_review/2.png ",
                "commerce/products/product_review/1.jpg"));
        request.setAvatarColor("#4CC9F0");

        ProductReview row = service.submitAppReview(request);

        ArgumentCaptor<ProductReview> captor = ArgumentCaptor.forClass(ProductReview.class);
        verify(reviewMapper).insert(captor.capture());
        assertThat(row).isSameAs(captor.getValue());
        assertThat(captor.getValue().getUserId()).isEqualTo(10001L);
        assertThat(captor.getValue().getOrderId()).isEqualTo(77L);
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(captor.getValue().getMediaObjectKeys()).isEqualTo("[\"commerce/products/product_review/1.jpg\",\"commerce/products/product_review/2.png\"]");
        assertThat(captor.getValue().getAvatarColor()).isEqualTo("#4CC9F0");
    }

    @Test
    void submitAppReviewValidatesPublicReviewConfigLimits() {
        CommerceOrder order = order(77L, "ORD-1", 10001L, 1L, "PAID");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(reviewMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(systemConfigClient.commerce()).thenReturn(ffdd.common.api.ApiResult.ok(Map.ofEntries(
                Map.entry("review.media_max_count", 1),
                Map.entry("review.title_max_length", 8),
                Map.entry("review.content_min_length", 12),
                Map.entry("review.content_max_length", 24))));

        ProductReviewRequest shortContent = new ProductReviewRequest();
        shortContent.setUserId(10001L);
        shortContent.setOrderNo("ORD-1");
        shortContent.setProductId(1L);
        shortContent.setRating(new BigDecimal("4.50"));
        shortContent.setContent("too short");
        assertThatThrownBy(() -> service.submitAppReview(shortContent))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("at least 12");

        ProductReviewRequest longTitle = new ProductReviewRequest();
        longTitle.setUserId(10001L);
        longTitle.setOrderNo("ORD-1");
        longTitle.setProductId(1L);
        longTitle.setRating(new BigDecimal("4.50"));
        longTitle.setTitle("too long title");
        longTitle.setContent("valid content");
        assertThatThrownBy(() -> service.submitAppReview(longTitle))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("at most 8");

        ProductReviewRequest tooManyMedia = new ProductReviewRequest();
        tooManyMedia.setUserId(10001L);
        tooManyMedia.setOrderNo("ORD-1");
        tooManyMedia.setProductId(1L);
        tooManyMedia.setRating(new BigDecimal("4.50"));
        tooManyMedia.setContent("valid content");
        tooManyMedia.setMediaObjectKeys(List.of(
                "commerce/products/product_review/1.jpg",
                "commerce/products/product_review/2.jpg"));
        assertThatThrownBy(() -> service.submitAppReview(tooManyMedia))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Media limit is 1");
        verify(reviewMapper, never()).insert(any(ProductReview.class));
    }

    @Test
    void submitAppReviewRejectsManualMediaUrl() {
        CommerceOrder order = order(77L, "ORD-1", 10001L, 1L, "PAID");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(reviewMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        ProductReviewRequest request = new ProductReviewRequest();
        request.setUserId(10001L);
        request.setOrderNo("ORD-1");
        request.setProductId(1L);
        request.setRating(new BigDecimal("4.50"));
        request.setMediaObjectKeys(List.of("https://cdn.example.com/review.jpg"));

        assertThatThrownBy(() -> service.submitAppReview(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Review media must be uploaded");
        verify(reviewMapper, never()).insert(any(ProductReview.class));
    }

    @Test
    void saveReviewRejectsManualAvatarUrl() {
        ProductReviewRequest request = new ProductReviewRequest();
        request.setProductId(1L);
        request.setRating(new BigDecimal("5.00"));
        request.setAvatarObjectKey("https://cdn.example.com/avatar.png");

        assertThatThrownBy(() -> service.saveReview(null, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Reviewer avatar must be uploaded");
        verify(reviewMapper, never()).insert(any(ProductReview.class));
    }

    @Test
    void findAppReviewByOrderReturnsOnlyOwnedOrderReview() {
        CommerceOrder order = order(77L, "ORD-1", 10001L, 1L, "PAID");
        ProductReview review = review(77L, "5.00");
        review.setUserId(10001L);
        review.setOrderId(77L);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(reviewMapper.selectOne(any(Wrapper.class))).thenReturn(review);

        ProductReview row = service.findAppReviewByOrder("ORD-1", 10001L);

        assertThat(row).isSameAs(review);
    }

    @Test
    void submitAppReviewRejectsDuplicateOrderReview() {
        CommerceOrder order = order(77L, "ORD-1", 10001L, 1L, "PAID");
        ProductReview existing = review(77L, "5.00");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(reviewMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        ProductReviewRequest request = new ProductReviewRequest();
        request.setUserId(10001L);
        request.setOrderNo("ORD-1");
        request.setProductId(1L);
        request.setRating(new BigDecimal("4.50"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submitAppReview(request))
                .hasMessageContaining("already reviewed");
    }

    @Test
    void submitAppReviewMapsUniqueKeyConflictToDuplicateReviewError() {
        CommerceOrder order = order(77L, "ORD-1", 10001L, 1L, "PAID");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(reviewMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(reviewMapper.insert(any(ProductReview.class))).thenThrow(new DuplicateKeyException("duplicate review"));

        ProductReviewRequest request = new ProductReviewRequest();
        request.setUserId(10001L);
        request.setOrderNo("ORD-1");
        request.setProductId(1L);
        request.setRating(new BigDecimal("4.50"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submitAppReview(request))
                .hasMessageContaining("already reviewed");
    }

    @Test
    void saveFaqAndSpecCreateStructuredRows() {
        ProductFaqRequest faqRequest = new ProductFaqRequest();
        faqRequest.setProductId(1L);
        faqRequest.setQuestion("Where is it hosted?");
        faqRequest.setAnswer("Singapore datacenter.");

        ProductSpecRequest specRequest = new ProductSpecRequest();
        specRequest.setProductId(1L);
        specRequest.setSpecKey("VRAM");
        specRequest.setSpecValue("96");
        specRequest.setUnit("GB");

        service.saveFaq(null, faqRequest);
        service.saveSpec(null, specRequest);

        ArgumentCaptor<ProductFaq> faqCaptor = ArgumentCaptor.forClass(ProductFaq.class);
        ArgumentCaptor<ProductSpec> specCaptor = ArgumentCaptor.forClass(ProductSpec.class);
        verify(faqMapper).insert(faqCaptor.capture());
        verify(specMapper).insert(specCaptor.capture());
        assertThat(faqCaptor.getValue().getStatus()).isEqualTo("VISIBLE");
        assertThat(specCaptor.getValue().getSpecGroup()).isEqualTo("GENERAL");
        assertThat(specCaptor.getValue().getStatus()).isEqualTo("VISIBLE");
    }

    @Test
    void savePriceIndexDefaultsActiveAndSampleTime() {
        PriceIndexRequest request = new PriceIndexRequest();
        request.setMetricCode("IMAGE_GEN");
        request.setMetricLabel("Image Gen");
        request.setUnitLabel("per image");
        request.setPriceUsdt(new BigDecimal("0.003"));
        request.setSparkline("0.001,0.002,0.003");

        service.savePriceIndex(null, request);

        ArgumentCaptor<PriceIndex> captor = ArgumentCaptor.forClass(PriceIndex.class);
        verify(priceIndexMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().getSampledAt()).isNotNull();
        assertThat(captor.getValue().getDeltaPercent()).isEqualByComparingTo("0");
        assertThat(captor.getValue().getSparkline()).isEqualTo("[0.001,0.002,0.003]");
        verify(systemConfigClient, never()).createConfig(any(SystemConfigClient.ConfigItemSaveRequest.class));
        verify(systemConfigClient, never()).updateConfig(any(Long.class), any(SystemConfigClient.ConfigItemUpdateRequest.class));
    }

    @Test
    void saveActiveNexPriceIndexSynchronizesWalletPublicConfig() {
        when(systemConfigClient.listConfigs("wallet.exchange.nex_usdt_price", null, 20))
                .thenReturn(ffdd.common.api.ApiResult.ok(List.of(new SystemConfigClient.ConfigItemResponse(
                        156L,
                        "wallet.exchange.nex_usdt_price",
                        "0.100",
                        "NUMBER",
                        "wallet",
                        "PUBLIC",
                        "NEX price",
                        1))));
        when(systemConfigClient.listConfigs("wallet.nex_market.volume_24h_usdt", null, 20))
                .thenReturn(ffdd.common.api.ApiResult.ok(List.of()));

        PriceIndexRequest request = new PriceIndexRequest();
        request.setMetricCode("NEX_USDT");
        request.setMetricLabel("NEX / USDT");
        request.setUnitLabel("USDT");
        request.setPriceUsdt(new BigDecimal("0.171000"));
        request.setVolume24hUsdt(new BigDecimal("980000.000000"));
        request.setSparkline("0.168,0.171");

        service.savePriceIndex(null, request);

        ArgumentCaptor<SystemConfigClient.ConfigItemUpdateRequest> updateCaptor = ArgumentCaptor.forClass(SystemConfigClient.ConfigItemUpdateRequest.class);
        verify(systemConfigClient).updateConfig(org.mockito.ArgumentMatchers.eq(156L), updateCaptor.capture());
        assertThat(updateCaptor.getValue().configValue()).isEqualTo("0.171");

        ArgumentCaptor<SystemConfigClient.ConfigItemSaveRequest> createCaptor = ArgumentCaptor.forClass(SystemConfigClient.ConfigItemSaveRequest.class);
        verify(systemConfigClient).createConfig(createCaptor.capture());
        assertThat(createCaptor.getValue().configKey()).isEqualTo("wallet.nex_market.volume_24h_usdt");
        assertThat(createCaptor.getValue().configValue()).isEqualTo("980000");
        assertThat(createCaptor.getValue().configGroup()).isEqualTo("wallet");
        assertThat(createCaptor.getValue().visibility()).isEqualTo("PUBLIC");
    }

    @Test
    void saveInactiveNexPriceIndexDoesNotSynchronizeWalletPublicConfig() {
        PriceIndexRequest request = new PriceIndexRequest();
        request.setMetricCode("NEX_USDT");
        request.setMetricLabel("NEX / USDT");
        request.setUnitLabel("USDT");
        request.setPriceUsdt(new BigDecimal("0.171"));
        request.setStatus("INACTIVE");

        service.savePriceIndex(null, request);

        verify(systemConfigClient, never()).listConfigs(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(systemConfigClient, never()).createConfig(any(SystemConfigClient.ConfigItemSaveRequest.class));
        verify(systemConfigClient, never()).updateConfig(any(Long.class), any(SystemConfigClient.ConfigItemUpdateRequest.class));
    }

    @Test
    void deleteReviewSoftDeletes() {
        ProductReview row = review(10L, "5.00");
        row.setIsDeleted(0);
        when(reviewMapper.selectById(10L)).thenReturn(row);

        service.deleteReview(10L);

        verify(reviewMapper).updateById(row);
        assertThat(row.getIsDeleted()).isEqualTo(1);
    }

    private ProductReview review(Long id, String rating) {
        ProductReview row = new ProductReview();
        row.setId(id);
        row.setProductId(1L);
        row.setRating(new BigDecimal(rating));
        row.setStatus("VISIBLE");
        row.setIsDeleted(0);
        return row;
    }

    private CommerceOrder order(Long id, String orderNo, Long userId, Long productId, String paymentStatus) {
        CommerceOrder order = new CommerceOrder();
        order.setId(id);
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setProductId(productId);
        order.setPaymentStatus(paymentStatus);
        order.setIsDeleted(0);
        return order;
    }

    private ProductFaq faq(Long id) {
        ProductFaq row = new ProductFaq();
        row.setId(id);
        row.setProductId(1L);
        row.setQuestion("Q");
        row.setAnswer("A");
        row.setStatus("VISIBLE");
        row.setIsDeleted(0);
        return row;
    }

    private ProductSpec spec(Long id) {
        ProductSpec row = new ProductSpec();
        row.setId(id);
        row.setProductId(1L);
        row.setSpecKey("GPU");
        row.setSpecValue("4x RTX 4090");
        row.setStatus("VISIBLE");
        row.setIsDeleted(0);
        return row;
    }
}
