package ffdd.opsconsole.developer.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

class OpsDeveloperAccessControllerSecurityTest {
    @Test
    void everyMutationHasAnIndependentPermissionAndNoOperatorBodyField() {
        Method page = Arrays.stream(OpsDeveloperAccessController.class.getDeclaredMethods())
                .filter(method -> Arrays.stream(method.getAnnotationsByType(org.springframework.web.bind.annotation.GetMapping.class)).findFirst().isPresent())
                .findFirst().orElseThrow();
        assertThat(page.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAnyAuthority('developer_access_read','ROLE_SUPER_ADMIN')");
        for (Method method : OpsDeveloperAccessController.class.getDeclaredMethods()) {
            if (Arrays.stream(method.getAnnotationsByType(PostMapping.class)).findFirst().isEmpty()) continue;
            PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);
            assertThat(authorization).as(method.getName()).isNotNull();
            assertThat(authorization.value()).as(method.getName()).contains("developer_access_");
        }
        assertThat(Arrays.stream(OpsDeveloperAccessController.ReviewRequest.class.getDeclaredFields())
                .map(field -> field.getName()).toList()).containsExactly("expectedStatus", "reason");
    }
}
