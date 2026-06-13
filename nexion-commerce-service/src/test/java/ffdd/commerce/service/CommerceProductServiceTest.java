package ffdd.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.commerce.client.ComputeClient;
import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.Product;
import ffdd.commerce.domain.ProductReview;
import ffdd.commerce.dto.OrderCreateRequest;
import ffdd.commerce.dto.ProductCreateRequest;
import ffdd.commerce.dto.ProductQueryRequest;
import ffdd.commerce.dto.ProductUpdateRequest;
import ffdd.commerce.dto.StoreProductResponse;
import ffdd.commerce.mapper.CommerceOrderMapper;
import ffdd.commerce.mapper.ProductMapper;
import ffdd.commerce.mapper.ProductReviewMapper;
import ffdd.commerce.mapper.TradeinRuleMapper;
import ffdd.commerce.service.impl.CommerceServiceImpl;
import ffdd.common.exception.BizException;
import ffdd.common.outbox.EventOutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class CommerceProductServiceTest {
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final CommerceOrderMapper orderMapper = mock(CommerceOrderMapper.class);
    private final ProductReviewMapper reviewMapper = mock(ProductReviewMapper.class);
    private final TradeinRuleMapper tradeinRuleMapper = mock(TradeinRuleMapper.class);
    private final ComputeClient computeClient = mock(ComputeClient.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final CommerceServiceImpl service = new CommerceServiceImpl(productMapper, orderMapper, reviewMapper, tradeinRuleMapper, computeClient, outboxService, new ObjectMapper());

    @Test
    void createOrderRejectsMissingResolvedUserId() {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setProductId(1L);
        request.setQuantity(1);

        assertThatThrownBy(() -> service.createOrder(request))
                .isInstanceOf(BizException.class)
                .hasMessage("User id is required");

        verify(orderMapper, never()).insert(any(CommerceOrder.class));
    }

    @Test
    void pageProductsFiltersGenerationGateByExactPhaseCode() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Product.class);
        Page<Product> page = Page.of(1, 10);
        page.setRecords(List.of());
        when(productMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        ProductQueryRequest request = new ProductQueryRequest();
        request.setUnlockPhase("P3");

        service.pageProducts(request);

        ArgumentCaptor<Wrapper<Product>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(productMapper).selectPage(any(Page.class), captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("unlock_phase =");
        assertThat(sqlSegment).doesNotContain("unlock_phase IS NULL");
        assertThat(sqlSegment).doesNotContain("unlock_phase IN");
    }

    @Test
    void createProductIgnoresManuallyEnteredStoreStats() {
        doAnswer(invocation -> {
                    Product product = invocation.getArgument(0);
                    product.setId(99L);
                    return 1;
                })
                .when(productMapper)
                .insert(any(Product.class));

        ProductCreateRequest request = new ProductCreateRequest();
        request.setProductNo("NX-S1-OPS");
        request.setName("NexionBox S1 Ops");
        request.setProductType("NEXION_BOX");
        request.setTier("S1");
        request.setStatus("ON_SALE");
        request.setPriceUsdt(new BigDecimal("299.000000"));
        request.setEstimatedDailyUsdt(new BigDecimal("38.560000"));
        request.setDailyNex(new BigDecimal("720.000000"));
        request.setStock(20);
        request.setCoverUrl("commerce/products/cover/cover.png");
        request.setDetailImageUrls("[\"commerce/products/detail/detail-1.png\"]");
        request.setTagline("Personal AI box");
        request.setGpuModel("4x RTX 4090");
        request.setVramTotalGb(96);
        request.setAiPerformanceJson("{\"imageGenPerMin\":320,\"unlocks\":\"LLM pool\"}");
        request.setSoldCount(4821);
        request.setRatingValue(new BigDecimal("4.80"));
        request.setReviewCount(2847);

        Product product = service.createProduct(request);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).insert(captor.capture());
        assertThat(product.getId()).isEqualTo(99L);
        assertThat(captor.getValue().getProductNo()).isEqualTo("NX-S1-OPS");
        assertThat(captor.getValue().getPriceUsdt()).isEqualByComparingTo("299.000000");
        assertThat(captor.getValue().getEstimatedDailyUsdt()).isEqualByComparingTo("38.560000");
        assertThat(captor.getValue().getCoverUrl()).isEqualTo("commerce/products/cover/cover.png");
        assertThat(captor.getValue().getDetailImageUrls()).isEqualTo("[\"commerce/products/detail/detail-1.png\"]");
        assertThat(captor.getValue().getTagline()).isEqualTo("Personal AI box");
        assertThat(captor.getValue().getGpuModel()).isEqualTo("4x RTX 4090");
        assertThat(captor.getValue().getVramTotalGb()).isEqualTo(96);
        assertThat(captor.getValue().getUnlockPhase()).isEqualTo("P1");
        assertThat(captor.getValue().getSoldCount()).isZero();
        assertThat(captor.getValue().getRatingValue()).isZero();
        assertThat(captor.getValue().getReviewCount()).isZero();
        assertThat(captor.getValue().getIsDeleted()).isZero();
    }

    @Test
    void createProductRejectsManualCoverUrl() {
        ProductCreateRequest request = new ProductCreateRequest();
        request.setProductNo("NX-S1-OPS");
        request.setName("NexionBox S1 Ops");
        request.setProductType("NEXION_BOX");
        request.setStatus("ON_SALE");
        request.setPriceUsdt(new BigDecimal("299.000000"));
        request.setCoverUrl("https://cdn.example.com/cover.png");

        assertThatThrownBy(() -> service.createProduct(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Product cover must be uploaded");
        verify(productMapper, never()).insert(any(Product.class));
    }

    @Test
    void createProductRejectsManualDetailMediaUrl() {
        ProductCreateRequest request = new ProductCreateRequest();
        request.setProductNo("NX-S1-OPS");
        request.setName("NexionBox S1 Ops");
        request.setProductType("NEXION_BOX");
        request.setStatus("ON_SALE");
        request.setPriceUsdt(new BigDecimal("299.000000"));
        request.setDetailImageUrls("[\"commerce/products/detail/detail-1.png\",\"/img/products/manual.png\"]");

        assertThatThrownBy(() -> service.createProduct(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Product detail media must be uploaded");
        verify(productMapper, never()).insert(any(Product.class));
    }

    @Test
    void storeProductStatsComeFromPaidOrdersAndVisibleReviews() {
        Product product = new Product();
        product.setId(99L);
        product.setProductNo("NX-S1-OPS");
        product.setName("NexionBox S1 Ops");
        product.setProductType("NEXION_BOX");
        product.setStatus("ON_SALE");
        product.setStoreVisible(1);
        product.setPriceUsdt(new BigDecimal("299.000000"));
        product.setEstimatedDailyUsdt(new BigDecimal("38.560000"));
        product.setDailyNex(new BigDecimal("720.000000"));
        product.setSoldCount(9999);
        product.setRatingValue(new BigDecimal("1.00"));
        product.setReviewCount(9999);
        product.setStock(20);
        product.setIsDeleted(0);

        CommerceOrder firstOrder = new CommerceOrder();
        firstOrder.setQuantity(2);
        CommerceOrder secondOrder = new CommerceOrder();
        secondOrder.setQuantity(3);
        ProductReview fiveStar = review("5.00");
        ProductReview fourStar = review("4.00");

        when(tradeinRuleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(productMapper.selectList(any(Wrapper.class))).thenReturn(List.of(product));
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(firstOrder, secondOrder));
        when(reviewMapper.selectList(any(Wrapper.class))).thenReturn(List.of(fiveStar, fourStar));

        List<StoreProductResponse> rows = service.listStoreProducts();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSold()).isEqualTo(5);
        assertThat(rows.get(0).getRating()).isEqualByComparingTo("4.50");
        assertThat(rows.get(0).getReviews()).isEqualTo(2);
    }

    @Test
    void createProductMapsDuplicateSkuToConflict() {
        when(productMapper.insert(any(Product.class))).thenThrow(new DuplicateKeyException("duplicate"));

        ProductCreateRequest request = new ProductCreateRequest();
        request.setProductNo("NX-S1");
        request.setName("NexionBox S1");
        request.setProductType("NEXION_BOX");
        request.setStatus("ON_SALE");
        request.setPriceUsdt(new BigDecimal("299.000000"));
        request.setStock(1);

        assertThatThrownBy(() -> service.createProduct(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void updateProductChangesOnlyProvidedSkuFields() {
        Product existing = new Product();
        existing.setId(1L);
        existing.setProductNo("NX-S1");
        existing.setName("NexionBox S1");
        existing.setProductType("NEXION_BOX");
        existing.setTier("S1");
        existing.setStatus("ON_SALE");
        existing.setPriceUsdt(new BigDecimal("299.000000"));
        existing.setStock(10);
        existing.setIsDeleted(0);
        when(productMapper.selectById(1L)).thenReturn(existing);

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setPriceUsdt(new BigDecimal("399.000000"));
        request.setStock(8);
        request.setCoverUrl("");
        request.setDetailImageUrls("[\"commerce/products/detail/new.png\"]");

        Product updated = service.updateProduct(1L, request);

        verify(productMapper).updateById(existing);
        assertThat(updated.getProductNo()).isEqualTo("NX-S1");
        assertThat(updated.getPriceUsdt()).isEqualByComparingTo("399.000000");
        assertThat(updated.getStock()).isEqualTo(8);
        assertThat(updated.getCoverUrl()).isEmpty();
        assertThat(updated.getDetailImageUrls()).isEqualTo("[\"commerce/products/detail/new.png\"]");
    }

    @Test
    void updateProductIgnoresManuallyEnteredStoreStats() {
        Product existing = new Product();
        existing.setId(1L);
        existing.setProductNo("NX-S1");
        existing.setName("NexionBox S1");
        existing.setProductType("NEXION_BOX");
        existing.setStatus("ON_SALE");
        existing.setPriceUsdt(new BigDecimal("299.000000"));
        existing.setStock(10);
        existing.setSoldCount(7);
        existing.setRatingValue(new BigDecimal("4.00"));
        existing.setReviewCount(2);
        existing.setIsDeleted(0);
        when(productMapper.selectById(1L)).thenReturn(existing);

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setSoldCount(4821);
        request.setRatingValue(new BigDecimal("4.80"));
        request.setReviewCount(2847);

        Product updated = service.updateProduct(1L, request);

        verify(productMapper).updateById(existing);
        assertThat(updated.getSoldCount()).isEqualTo(7);
        assertThat(updated.getRatingValue()).isEqualByComparingTo("4.00");
        assertThat(updated.getReviewCount()).isEqualTo(2);
    }

    @Test
    void deleteProductSoftDeletesSku() {
        Product existing = new Product();
        existing.setId(1L);
        existing.setProductNo("NX-S1");
        existing.setName("NexionBox S1");
        existing.setProductType("NEXION_BOX");
        existing.setStatus("ON_SALE");
        existing.setPriceUsdt(new BigDecimal("299.000000"));
        existing.setStock(10);
        existing.setIsDeleted(0);
        when(productMapper.selectById(1L)).thenReturn(existing);

        service.deleteProduct(1L);

        verify(productMapper).updateById(existing);
        assertThat(existing.getIsDeleted()).isEqualTo(1);
        assertThat(existing.getStatus()).isEqualTo("ARCHIVED");
    }

    @Test
    void featureProductKeepsSingleVisibleFeaturedSku() {
        Product existing = new Product();
        existing.setId(1L);
        existing.setProductNo("NX-S1");
        existing.setName("NexionBox S1");
        existing.setProductType("NEXION_BOX");
        existing.setStatus("ON_SALE");
        existing.setStoreVisible(1);
        existing.setUnlockPhase("P1");
        existing.setPriceUsdt(new BigDecimal("1299.000000"));
        existing.setStock(10);
        existing.setIsDeleted(0);
        when(productMapper.selectById(1L)).thenReturn(existing);

        Product featured = service.featureProduct(1L, "P1");

        verify(productMapper).update(eq(null), any(Wrapper.class));
        verify(productMapper).updateById(existing);
        assertThat(featured.getStoreFeatured()).isEqualTo(1);
    }

    @Test
    void syncGenerationPhaseMovesOutOfRangeFeaturedBackToCurrentPhase() {
        Product p1 = new Product();
        p1.setId(1L);
        p1.setProductNo("NX-S1");
        p1.setStatus("ON_SALE");
        p1.setStoreVisible(1);
        p1.setStoreFeatured(0);
        p1.setUnlockPhase("P1");
        p1.setSortOrder(10);
        p1.setIsDeleted(0);

        Product p2 = new Product();
        p2.setId(2L);
        p2.setProductNo("NX-MINI-P2");
        p2.setStatus("ON_SALE");
        p2.setStoreVisible(1);
        p2.setStoreFeatured(1);
        p2.setUnlockPhase("P2");
        p2.setSortOrder(20);
        p2.setIsDeleted(0);

        when(productMapper.selectList(any(Wrapper.class))).thenReturn(List.of(p1, p2));

        Product featured = service.syncGenerationPhase("P1");

        assertThat(featured).isSameAs(p1);
        assertThat(p1.getStoreStatus()).isEqualTo("active");
        assertThat(p1.getStoreFeatured()).isEqualTo(1);
        assertThat(p2.getStoreFeatured()).isZero();
    }

    private ProductReview review(String rating) {
        ProductReview row = new ProductReview();
        row.setRating(new BigDecimal(rating));
        row.setStatus("VISIBLE");
        row.setIsDeleted(0);
        return row;
    }
}
