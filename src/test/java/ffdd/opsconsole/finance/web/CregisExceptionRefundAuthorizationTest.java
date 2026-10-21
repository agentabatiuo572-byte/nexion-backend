package ffdd.opsconsole.finance.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.finance.cregis.CregisExceptionRefundService;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CregisExceptionRefundAuthorizationTest {
    @Configuration @EnableMethodSecurity static class Guards {}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void externalRefundRequiresBothChannelManagementAndPayoutApproval(){
        var service=mock(CregisExceptionRefundService.class);
        try(var context=new AnnotationConfigApplicationContext()){
            context.register(Guards.class);context.registerBean(CregisExceptionRefundController.class,()->new CregisExceptionRefundController(service));context.refresh();
            var controller=context.getBean(CregisExceptionRefundController.class);
            var request=new CregisExceptionRefundController.Proposal("challenge","signature","hash","independent evidence reviewed");
            for(var grants:List.of(List.of("finance_d1_read"),List.of("finance_d1_read","finance_d1_channel_manage"),List.of("finance_d1_read","finance_d2_withdrawal_approve"))){
                authenticate(grants);assertThrows(AccessDeniedException.class,()->controller.propose("key",request));
            }
            verifyNoInteractions(service);authenticate(List.of("finance_d1_read"));controller.policy();verify(service).policy();
            authenticate(List.of("finance_d1_read","finance_d1_channel_manage","finance_d2_withdrawal_approve"));
            controller.propose("key",request);verify(service).propose("key","challenge","signature","hash",request.reason());
        }
    }
    void authenticate(List<String> grants){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("fixture","unused",grants.stream().map(SimpleGrantedAuthority::new).toList()));}
}
