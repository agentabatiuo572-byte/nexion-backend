package ffdd.opsconsole.finance.web;
import ffdd.opsconsole.finance.cregis.CregisAutoForwardService;
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
class CregisAutoForwardAuthorizationTest {
    @Configuration @EnableMethodSecurity static class Guards{}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void bothWriteRoutesRequireAllFinancialAuthorities(){
        var service=mock(CregisAutoForwardService.class);
        try(var context=new AnnotationConfigApplicationContext()){
            context.register(Guards.class);context.registerBean(CregisAutoForwardController.class,()->new CregisAutoForwardController(service));context.refresh();var c=context.getBean(CregisAutoForwardController.class);
            for(var rights:List.of(List.of("finance_d1_read"),List.of("finance_d1_read","finance_d1_channel_manage"),List.of("finance_d1_read","finance_d2_withdrawal_approve"))){
                auth(rights);assertThrows(AccessDeniedException.class,()->c.policy("k",new CregisAutoForwardController.Policy("PROPOSE",0,true,true,"test reason checked")));
                assertThrows(AccessDeniedException.class,()->c.recover("k",1,new CregisAutoForwardController.Recovery(0,"PAYMENT","123",false,"test reason checked")));
            }
            verifyNoInteractions(service);auth(List.of("finance_d1_read"));c.status();c.tasks(0,20);verify(service).status();verify(service).tasks(0,20);
        }
    }
    void auth(List<String> rights){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("7","unused",rights.stream().map(SimpleGrantedAuthority::new).toList()));}
}
