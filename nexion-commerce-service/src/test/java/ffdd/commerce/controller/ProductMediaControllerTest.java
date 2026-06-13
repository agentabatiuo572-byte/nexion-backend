package ffdd.commerce.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.commerce.dto.ProductMediaUploadResponse;
import ffdd.commerce.service.ProductMediaService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ProductMediaControllerTest {
    private final ProductMediaService service = mock(ProductMediaService.class);
    private final ProductMediaController controller = new ProductMediaController(service);

    @Test
    void userReviewMediaUploadForcesProductReviewMediaType() {
        MockMultipartFile file = new MockMultipartFile("file", "review.png", "image/png", "image".getBytes());
        ProductMediaUploadResponse uploaded = new ProductMediaUploadResponse();
        uploaded.setObjectKey("commerce/products/product_review/review.png");
        when(service.upload("PRODUCT_REVIEW", file)).thenReturn(uploaded);

        ProductMediaUploadResponse response = controller.uploadAppProductReviewMedia(file).getData();

        assertThat(response.getObjectKey()).startsWith("commerce/products/product_review/");
        verify(service).upload("PRODUCT_REVIEW", file);
    }

    @Test
    void userAvatarUploadForcesUserAvatarMediaType() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "image".getBytes());
        ProductMediaUploadResponse uploaded = new ProductMediaUploadResponse();
        uploaded.setObjectKey("auth/users/avatar/avatar.png");
        when(service.upload("USER_AVATAR", file)).thenReturn(uploaded);

        ProductMediaUploadResponse response = controller.uploadAppUserAvatar(file).getData();

        assertThat(response.getObjectKey()).startsWith("auth/users/avatar/");
        verify(service).upload("USER_AVATAR", file);
    }
}
