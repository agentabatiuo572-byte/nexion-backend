package ffdd.opsconsole.finance.web;
import ffdd.opsconsole.finance.cregis.CregisAutoForwardService;
import ffdd.opsconsole.finance.cregis.CregisNetwork;
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
                auth(rights);assertThrows(AccessDeniedException.class,()->c.policy("BEP20","k",new CregisAutoForwardController.Policy("PROPOSE",0,true,true,"test reason checked")));
                assertThrows(AccessDeniedException.class,()->c.recover("BEP20","k",1,new CregisAutoForwardController.Recovery(0,"PAYMENT","123",false,"test reason checked")));
            }
            verifyNoInteractions(service);auth(List.of("finance_d1_read"));c.status("BEP20");c.tasks("BEP20",0,20);verify(service).status(CregisNetwork.BEP20);verify(service).tasks(CregisNetwork.BEP20,0,20);
        }
    }
    void auth(List<String> rights){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("7","unused",rights.stream().map(SimpleGrantedAuthority::new).toList()));}
}
