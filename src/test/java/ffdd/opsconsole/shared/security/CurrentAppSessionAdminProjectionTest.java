package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CurrentAppSessionAdminProjectionTest {
    final AuthSessionMapper sessions=mock(AuthSessionMapper.class);
    final PlatformConfigFacade config=mock(PlatformConfigFacade.class);
    final UserAccountBlocklistVerifier blocklist=mock(UserAccountBlocklistVerifier.class);
    final UserOpsMapper users=mock(UserOpsMapper.class);
    final MockEnvironment environment=new MockEnvironment();
    final CurrentAppSessionService service=new CurrentAppSessionService(sessions,config,blocklist,users,environment);
    UserEntity user;

    @BeforeEach void setup(){
        environment.setActiveProfiles("dev");
        user=new UserEntity();user.setId(41L);user.setStatus("ACTIVE");user.setIsDeleted(0);user.setSandbox(0);
        when(users.selectById(41L)).thenReturn(user);
        when(config.activeValue("auth.session.idle_ttl_days")).thenReturn(Optional.empty());
    }
    @Test void eligibleAccountUsesConfiguredIdleWindowAndReadOnlyProjection(){
        var rows=List.of(new AuthSessionMapper.SessionSummary("APP","phone-a",3L,true,1L,null));
        when(config.activeValue("auth.session.idle_ttl_days")).thenReturn(Optional.of("7"));
        when(sessions.adminSessionSummary(41L,7)).thenReturn(rows);
        assertThat(service.adminSummary(41L)).isSameAs(rows);
        verify(sessions).passwordResetRequired(41L);verify(sessions).adminSessionSummary(41L,7);
        verifyNoMoreInteractions(sessions);
    }
    @Test void ineligibleAccountsNeverReadSessionProjection(){
        when(users.selectById(41L)).thenReturn(null);assertThat(service.adminSummary(41L)).isEmpty();
        when(users.selectById(41L)).thenReturn(user);
        user.setIsDeleted(1);assertThat(service.adminSummary(41L)).isEmpty();user.setIsDeleted(0);
        user.setStatus("SUSPENDED");assertThat(service.adminSummary(41L)).isEmpty();user.setStatus("ACTIVE");
        user.setSandbox(1);assertThat(service.adminSummary(41L)).isEmpty();user.setSandbox(0);
        environment.setActiveProfiles("dev","test");assertThat(service.adminSummary(41L)).isEmpty();environment.setActiveProfiles("dev");
        when(blocklist.isBlocked(41L)).thenReturn(true);assertThat(service.adminSummary(41L)).isEmpty();
        when(blocklist.isBlocked(41L)).thenReturn(false);
        when(sessions.passwordResetRequired(41L)).thenReturn(true);assertThat(service.adminSummary(41L)).isEmpty();
        verify(sessions,never()).adminSessionSummary(anyLong(),anyInt());
    }
    @Test void invalidIdleConfigurationUsesSameThirtyDayFallback(){
        for(String value:List.of("6","91","bad")){
            when(config.activeValue("auth.session.idle_ttl_days")).thenReturn(Optional.of(value));
            assertThat(service.adminSummary(41L)).isEmpty();
        }
        verify(sessions,times(3)).adminSessionSummary(41L,30);
    }
}
