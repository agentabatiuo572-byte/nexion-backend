package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppProductCatalogControllerTest {
    private final AppProductCatalogService service = mock(AppProductCatalogService.class);
    private final AppProductCatalogController controller = new AppProductCatalogController(service);

    @Test
    void authenticatedUserReadsServerCanonicalCatalog() {
        when(service.catalog(42L)).thenReturn(ApiResult.ok(Map.of(
                "source", "nx_admin_device_sku",
                "products", List.of())));

        ApiResult<Map<String, Object>> result = controller.catalog(auth("42", "USER"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("source")).isEqualTo("nx_admin_device_sku");
        verify(service).catalog(42L);
    }

    @Test
    void adminSubjectCannotCrossTheAppUserBoundary() {
        ApiResult<Map<String, Object>> result = controller.catalog(auth("7", "ADMIN"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_SUBJECT_REQUIRED");
        verify(service, never()).catalog(org.mockito.ArgumentMatchers.anyLong());
    }

    private UsernamePasswordAuthenticationToken auth(String id, String subjectType) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(id, null, List.of());
        authentication.setDetails(Map.of("subjectType", subjectType));
        return authentication;
    }
}
