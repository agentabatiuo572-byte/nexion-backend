package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.cregis.CregisFundsPreparationService;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CregisFundsPreparationAuthorizationTest {
    @Configuration @EnableMethodSecurity static class Guards { }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void preparationWritesAndPreflightRequireAllThreeAuthorities() {
        var service=mock(CregisFundsPreparationService.class);
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(Guards.class);
            context.registerBean(CregisFundsPreparationController.class,()->new CregisFundsPreparationController(service));context.refresh();
            var controller=context.getBean(CregisFundsPreparationController.class);
            var request=new CregisFundsPreparationController.Preparation("BEP20","COLLECTION","address","20","ref","evidence checked");
            for(var grants:List.of(List.of("finance_d1_read"),List.of("finance_d1_read","finance_d1_channel_manage"),List.of("finance_d1_read","finance_d2_withdrawal_approve"))) {
                auth(grants);
                assertThrows(AccessDeniedException.class,()->controller.create("key",request));
                assertThrows(AccessDeniedException.class,()->controller.preflight(request));
                assertThrows(AccessDeniedException.class,()->controller.decide("no","key",new CregisFundsPreparationController.Decision(0,"REVIEW","evidence checked")));
            }
            verifyNoInteractions(service);
            auth(List.of("finance_d1_read"));controller.status("BEP20");controller.plans("BEP20",0,20);
            verify(service).status("BEP20");verify(service).plans("BEP20",0,20);
            auth(List.of("finance_d1_read","finance_d1_channel_manage","finance_d2_withdrawal_approve"));
            controller.create("key",request);verify(service).create("BEP20","key","COLLECTION","address","20","ref","evidence checked");
        }
    }
    void auth(List<String> grants) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("7","unused",grants.stream().map(SimpleGrantedAuthority::new).toList())); }
}
