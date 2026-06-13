package ffdd.commerce.service;

import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.Product;
import ffdd.commerce.dto.OrderCreateRequest;
import ffdd.commerce.dto.OrderPayRequest;
import ffdd.commerce.dto.OrderQueryRequest;
import ffdd.commerce.dto.ProductCreateRequest;
import ffdd.commerce.dto.ProductQueryRequest;
import ffdd.commerce.dto.ProductUpdateRequest;
import ffdd.commerce.dto.StoreProductResponse;
import ffdd.common.api.PageResult;
import java.util.List;

public interface CommerceService {
    PageResult<Product> pageProducts(ProductQueryRequest request);

    Product getProduct(Long id);

    Product createProduct(ProductCreateRequest request);

    Product updateProduct(Long id, ProductUpdateRequest request);

    void deleteProduct(Long id);

    List<Product> listFeaturedProductCandidates(String currentPhase);

    Product featureProduct(Long id, String currentPhase);

    Product syncGenerationPhase(String currentPhase);

    List<StoreProductResponse> listStoreProducts();

    CommerceOrder createOrder(OrderCreateRequest request);

    PageResult<CommerceOrder> pageOrders(OrderQueryRequest request);

    CommerceOrder getOrder(String orderNo);

    CommerceOrder activatePaidOrder(String orderNo);

    CommerceOrder markPaid(String orderNo, OrderPayRequest request);
}
