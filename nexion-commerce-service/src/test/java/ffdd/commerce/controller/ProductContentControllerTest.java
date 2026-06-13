package ffdd.commerce.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.commerce.domain.ProductReview;
import ffdd.commerce.dto.ProductReviewRequest;
import ffdd.commerce.service.ProductContentService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ProductContentControllerTest {
    private final ProductContentService service = mock(ProductContentService.class);
    private final ProductContentController controller = new ProductContentController(service);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userAppReviewSubmitForcesAuthenticatedUserId() {
        asUser(10082L);
        when(service.submitAppReview(any(ProductReviewRequest.class))).thenReturn(review(10082L));

        ProductReviewRequest request = new ProductReviewRequest();
        request.setUserId(10001L);
        request.setOrderNo("ORD-1");
        request.setProductId(1L);
        request.setRating(new BigDecimal("5.00"));
        controller.submitAppReview(request);

        ArgumentCaptor<ProductReviewRequest> captor = ArgumentCaptor.forClass(ProductReviewRequest.class);
        verify(service).submitAppReview(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userAppReviewLookupForcesAuthenticatedUserId() {
        asUser(10082L);
        when(service.findAppReviewByOrder("ORD-1", 10082L)).thenReturn(review(10082L));

        ProductReview row = controller.appReviewByOrder("ORD-1").getData();

        assertThat(row.getUserId()).isEqualTo(10082L);
        verify(service).findAppReviewByOrder("ORD-1", 10082L);
    }

    private void asUser(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(userId),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private ProductReview review(Long userId) {
        ProductReview row = new ProductReview();
        row.setUserId(userId);
        row.setProductId(1L);
        row.setRating(new BigDecimal("5.00"));
        row.setStatus("PENDING_REVIEW");
        return row;
    }
}
