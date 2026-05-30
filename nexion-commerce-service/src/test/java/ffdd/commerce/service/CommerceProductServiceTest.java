package ffdd.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.commerce.client.ComputeClient;
import ffdd.commerce.domain.Product;
import ffdd.commerce.dto.ProductCreateRequest;
import ffdd.commerce.dto.ProductUpdateRequest;
import ffdd.commerce.mapper.CommerceOrderMapper;
import ffdd.commerce.mapper.ProductMapper;
import ffdd.commerce.service.impl.CommerceServiceImpl;
import ffdd.common.exception.BizException;
import ffdd.common.outbox.EventOutboxService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class CommerceProductServiceTest {
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final CommerceOrderMapper orderMapper = mock(CommerceOrderMapper.class);
    private final ComputeClient computeClient = mock(ComputeClient.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final CommerceServiceImpl service = new CommerceServiceImpl(productMapper, orderMapper, computeClient, outboxService);

    @Test
    void createProductPersistsSkuEconomics() {
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

        Product product = service.createProduct(request);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).insert(captor.capture());
        assertThat(product.getId()).isEqualTo(99L);
        assertThat(captor.getValue().getProductNo()).isEqualTo("NX-S1-OPS");
        assertThat(captor.getValue().getPriceUsdt()).isEqualByComparingTo("299.000000");
        assertThat(captor.getValue().getEstimatedDailyUsdt()).isEqualByComparingTo("38.560000");
        assertThat(captor.getValue().getCoverUrl()).isEqualTo("commerce/products/cover/cover.png");
        assertThat(captor.getValue().getDetailImageUrls()).isEqualTo("[\"commerce/products/detail/detail-1.png\"]");
        assertThat(captor.getValue().getIsDeleted()).isZero();
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
}
