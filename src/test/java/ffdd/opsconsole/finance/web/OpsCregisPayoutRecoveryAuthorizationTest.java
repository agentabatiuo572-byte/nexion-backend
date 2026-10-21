package ffdd.opsconsole.finance.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.finance.application.CregisPayoutRecoveryService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsCregisPayoutRecoveryAuthorizationTest {
    @Configuration @EnableMethodSecurity static class Guards {}
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void methodGuardRequiresD2ReadAndApprovalForRecoveryAndOnlyReadForDetail() {
        var recovery = mock(CregisPayoutRecoveryService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Guards.class);
            context.registerBean(OpsCregisPayoutRecoveryController.class,() -> new OpsCregisPayoutRecoveryController(recovery));
            context.refresh();
            var controller = context.getBean(OpsCregisPayoutRecoveryController.class);
            var request = new OpsCregisPayoutRecoveryController.RecoveryRequest("901","provider verified original CID");
            for (var grants : List.of(List.<String>of(),List.of("finance_d2_read"),List.of("finance_d2_withdrawal_approve"))) {
                authenticate(grants);
                assertThrows(AccessDeniedException.class,() -> controller.recover("WD-test","key",request));
            }
            verifyNoInteractions(recovery);
            authenticate(List.of("finance_d2_read"));
            controller.detail("WD-test"); verify(recovery).detail("WD-test");
            authenticate(List.of("finance_d2_read","finance_d2_withdrawal_approve"));
            when(recovery.recover("WD-test","key","901",request.reason()))
                    .thenReturn(new CregisPayoutRecoveryService.Result("WD-test","901","SENT"));
            assertEquals("SENT",controller.recover("WD-test","key",request).getData().state());
        }
    }

    void authenticate(List<String> grants) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "fixture-admin","unused",grants.stream().map(SimpleGrantedAuthority::new).toList()));
    }
}
