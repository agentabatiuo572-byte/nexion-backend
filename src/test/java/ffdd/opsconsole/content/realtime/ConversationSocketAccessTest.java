package ffdd.opsconsole.content.realtime;

import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.domain.*;
import ffdd.opsconsole.shared.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationSocketAccessTest {
    @Test void presenceUsesTheCurrentOwnerAndExcludesReadOnlySeats() {
        var repo=mock(ConversationRepository.class);
        var access=new ConversationSocketAccess(mock(JwtAuthenticationFilter.class),mock(AdminRbacAuthorizationFilter.class),
                mock(UserAccountBlocklistVerifier.class),mock(UserBusinessWriteGateFilter.class),repo,mock(ProductionSupportPathGuard.class));
        var c=new ContentConversationView(1L,"CV-1",12L,"support","OPEN","seat", "Agent",0,"hi",null,null,null,null,null,null,null,null,null,7L);
        when(repo.findByConversationNo("CV-1")).thenReturn(Optional.of(c));
        var scope=access.participants("CV-1").orElseThrow();
        var user=new UsernamePasswordAuthenticationToken("12",null,List.of());
        var otherUser=new UsernamePasswordAuthenticationToken("13",null,List.of());
        var readOnly=new UsernamePasswordAuthenticationToken("20",null,List.of(new SimpleGrantedAuthority("service_m3_read")));
        readOnly.setDetails(Map.of("username","seat"));
        var writer=new UsernamePasswordAuthenticationToken("20",null,List.of(new SimpleGrantedAuthority("service_m3_read"),new SimpleGrantedAuthority("service_m3_write")));
        writer.setDetails(Map.of("username","seat"));
        assertTrue(scope.canRead(user,"USER"));
        assertFalse(scope.canRead(otherUser,"USER"));
        assertTrue(scope.canRead(readOnly,"ADMIN"));
        assertTrue(access.presenceKeys(readOnly,"ADMIN").isEmpty());
        assertTrue(access.presenceKeys(writer,"ADMIN").contains(scope.peerKey("USER")));
        assertEquals(Set.of("USER:12"),access.presenceKeys(user,"USER"));
        assertEquals("USER:12",scope.peerKey("ADMIN"));
        verify(repo,times(1)).findByConversationNo("CV-1");
        assertTrue(access.participants("../other").isEmpty());
        verifyNoMoreInteractions(repo);
    }
}
