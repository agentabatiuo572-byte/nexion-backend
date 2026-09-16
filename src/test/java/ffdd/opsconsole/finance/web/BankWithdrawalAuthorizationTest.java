package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.*;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankWithdrawalAuthorizationTest {
    @Configuration @EnableMethodSecurity static class Guards {}
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void adminReadPermissionAloneCannotRunFinancialRecovery() {
        var recovery=mock(BankPayoutRecoveryService.class);
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(Guards.class);
            context.registerBean(OpsBankWithdrawalController.class,()->new OpsBankWithdrawalController(mock(BankWithdrawalMapper.class),recovery));
            context.refresh(); var controller=context.getBean(OpsBankWithdrawalController.class);
            for(var grants:List.of(List.<String>of(),List.of("finance_d2_read"),List.of("finance_d2_read","finance_d2_withdrawal_approve"))) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("fixture-admin","unused",grants.stream().map(SimpleGrantedAuthority::new).toList()));
                assertThrows(org.springframework.security.access.AccessDeniedException.class,()->controller.requery("WD-TEST","key",new OpsBankWithdrawalController.RecoveryRequest(1L,"verify original order")));
            }
            verifyNoInteractions(recovery);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("fixture-admin","unused",
                    List.of("finance_d2_read","finance_d2_withdrawal_approve","finance_d2_withdrawal_refund").stream().map(SimpleGrantedAuthority::new).toList()));
            when(recovery.recover("WD-TEST","key",1L,"verify original order")).thenReturn("MANUAL_REVIEW");
            assertEquals("MANUAL_REVIEW",controller.requery("WD-TEST","key",new OpsBankWithdrawalController.RecoveryRequest(1L,"verify original order")).getData().get("state"));
        }
    }
    @Test void appEndpointsDeriveUserFromAuthenticatedUserSubjectOnly() {
        var service=mock(BankWithdrawalService.class); var controller=new BankWithdrawalController(service, new ffdd.opsconsole.shared.security.GatewaySecurityProperties());
        assertThrows(RuntimeException.class,()->controller.config(null));
        assertThrows(RuntimeException.class,()->controller.recovery(null));
        assertThrows(RuntimeException.class,()->controller.verify(null));
        var binding = new BankWithdrawalService.BindRequest("", "00123456789", "NGUYEN VAN A", null, null);
        assertThrows(RuntimeException.class,()->controller.bind(null, "fixture-key", binding));
        var auth=new UsernamePasswordAuthenticationToken("71","unused",List.of());
        auth.setDetails(Map.of("subjectType","ADMIN"));
        assertThrows(RuntimeException.class,()->controller.bind(auth, "fixture-key", binding));
        assertThrows(RuntimeException.class,()->controller.config(auth)); verifyNoInteractions(service);
        assertThrows(RuntimeException.class,()->controller.recovery(auth));
        assertThrows(RuntimeException.class,()->controller.verify(auth));
        auth.setDetails(Map.of("subjectType","USER")); when(service.config(71L)).thenReturn(ApiResult.ok(Map.of("enabled",false)));
        assertEquals(false,controller.config(auth).getData().get("enabled"));
        verify(service).config(71L);
        controller.bind(auth, "fixture-key", binding);
        verify(service).bind(71L, binding, "fixture-key");
    }
}
