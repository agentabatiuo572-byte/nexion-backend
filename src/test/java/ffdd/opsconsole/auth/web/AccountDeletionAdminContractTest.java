package ffdd.opsconsole.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

class AccountDeletionAdminContractTest {
    @Test
    void exposesDiscoverableListAndAuditedCommandsBehindUserPermissions() {
        assertThat(Arrays.stream(OpsAccountDeletionController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .map(method -> method.getAnnotation(PreAuthorize.class).value())
                .toList()).contains("hasAuthority('user_c1_read')");
        assertThat(Arrays.stream(OpsAccountDeletionController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .map(method -> method.getAnnotation(PreAuthorize.class).value())
                .distinct().toList()).containsExactly("hasAuthority('user_c1_write')");
    }
}
