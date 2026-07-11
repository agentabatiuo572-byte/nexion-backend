package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsPlatformRbacControllerTest {
    @Test
    void a6GrantMutationRequiresDedicatedHighRiskAuthority() throws Exception {
        Method method = OpsPlatformRoleController.class.getMethod(
                "grants", Long.class, String.class,
                ffdd.opsconsole.platform.dto.PlatformRoleGrantsUpdateRequest.class);

        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('platform_a6_role_grants_update')");
    }

    @Test
    void a7AndA8EndpointsDeclareTheirOwnDomainAuthorities() {
        assertThat(OpsPlatformMenuController.class.getDeclaredMethods())
                .filteredOn(method -> method.getAnnotation(PreAuthorize.class) != null)
                .allSatisfy(method -> assertThat(method.getAnnotation(PreAuthorize.class).value())
                        .contains("platform_a7_"));
        assertThat(OpsPlatformPermissionController.class.getDeclaredMethods())
                .filteredOn(method -> method.getAnnotation(PreAuthorize.class) != null)
                .allSatisfy(method -> assertThat(method.getAnnotation(PreAuthorize.class).value())
                        .contains("platform_a8_read"));
    }
}
