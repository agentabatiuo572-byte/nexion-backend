package ffdd.opsconsole.user.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.user.application.NotificationTimeEvidenceService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Arrays;

class OpsUserNotificationEvidenceControllerTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean NotificationTimeEvidenceService service(){ return mock(NotificationTimeEvidenceService.class); }
        @Bean OpsUserNotificationEvidenceController controller(NotificationTimeEvidenceService service){ return new OpsUserNotificationEvidenceController(service); }
    }
    @Test void actualMethodSecurityRequiresBothAuthorities() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var controller=context.getBean(OpsUserNotificationEvidenceController.class);
            var service=context.getBean(NotificationTimeEvidenceService.class);
            for(String authority:new String[]{"user_c1hub_read","platform_a4_read","content_i3_read"}) {
                authenticate(authority);
                assertThatThrownBy(()->controller.preview("U7",99L)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            }
            verifyNoInteractions(service);
            authenticate("user_c1hub_read","platform_a4_read"); controller.preview("U7",99L);
            verify(service).preview("U7",99L);
        } finally { SecurityContextHolder.clearContext(); }
    }
    private void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1","",Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
