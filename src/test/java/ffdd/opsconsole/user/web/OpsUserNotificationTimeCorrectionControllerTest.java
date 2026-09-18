package ffdd.opsconsole.user.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.user.application.NotificationTimeCorrectionService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Arrays;

class OpsUserNotificationTimeCorrectionControllerTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean NotificationTimeCorrectionService service(){return mock(NotificationTimeCorrectionService.class);}
        @Bean OpsUserNotificationTimeCorrectionController controller(NotificationTimeCorrectionService service){return new OpsUserNotificationTimeCorrectionController(service);}
    }
    @Test void allExistingReadAndWritePermissionsAreRequiredByActualProxy() {
        String[] required={"user_c1hub_read","platform_a4_read","user_c1hub_write","platform_a4_write"};
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var controller=context.getBean(OpsUserNotificationTimeCorrectionController.class);var service=context.getBean(NotificationTimeCorrectionService.class);
            for(String missing:required) {
                authenticate(Arrays.stream(required).filter(a->!a.equals(missing)).toArray(String[]::new));
                assertThatThrownBy(()->controller.correct("U7",99L,"key",null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            }
            verifyNoInteractions(service);authenticate(required);controller.correct("U7",99L,"key",null);verify(service).correct("U7",99L,"key",null);
        } finally {SecurityContextHolder.clearContext();}
    }
    private void authenticate(String... values){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1","",Arrays.stream(values).map(SimpleGrantedAuthority::new).toList()));}
}
