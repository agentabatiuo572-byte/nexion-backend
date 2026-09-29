package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportMaintenance.ActivityState;
import ffdd.opsconsole.content.domain.SupportMaintenance.ActivityEvent;
import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import ffdd.opsconsole.content.mapper.SupportMaintenanceMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupportActivityProfileTest {
    private final MockEnvironment environment=new MockEnvironment();
    private final SupportAcceptanceSandboxMapper accounts=mock(SupportAcceptanceSandboxMapper.class);
    private final SupportMaintenanceMapper mapper=mock(SupportMaintenanceMapper.class);
    private final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    private final SupportMaintenanceService maintenance=mock(SupportMaintenanceService.class);
    private final SupportActivityService activity=new SupportActivityService(mapper,ownership,maintenance,
            new ProductionSupportPathGuard(environment,accounts));

    @ParameterizedTest
    @ValueSource(strings={"test","unknown","prod,dev"})
    void forbiddenProfilesRejectDirectEntryBeforeAnyCanonicalMapperCall(String profiles) {
        environment.setActiveProfiles(profiles.split(","));
        assertThatThrownBy(()->activity.interactiveLogin(1L,UUID.randomUUID().toString()))
                .hasMessageContaining("SUPPORT_PRODUCTION_PATH_FORBIDDEN");
        assertThatThrownBy(activity::checkpoint).hasMessageContaining("SUPPORT_PRODUCTION_PATH_FORBIDDEN");
        verifyNoInteractions(accounts,mapper,ownership,maintenance);
    }

    @ParameterizedTest
    @ValueSource(strings={"dev","prod"})
    void canonicalProfilesPermitCaptureAndWatermark(String profile) {
        environment.setActiveProfiles(profile);
        when(accounts.sandboxUser(1L)).thenReturn(0);
        LocalDateTime now=LocalDateTime.of(2026,9,29,0,0);
        var coverage=new SupportActivityService.Coverage(now.minusDays(1),now);
        when(mapper.captureFence()).thenReturn(coverage);
        when(mapper.activity(1L)).thenReturn(new ActivityState(1L,0,null));
        when(mapper.eventTime()).thenReturn(now);
        when(mapper.checkpointFence()).thenReturn(coverage);
        when(mapper.advanceWatermark()).thenReturn(1);
        String session=UUID.randomUUID().toString();
        var event=new ActivityEvent(1L,1L,1,"INTERACTIVE_LOGIN:"+session,now);
        when(mapper.activityEvent(event.sourceRef())).thenReturn(null,event);
        activity.interactiveLogin(1L,session);
        assertThat(activity.checkpoint()).isEqualTo(coverage);
        var order=inOrder(accounts,ownership,mapper);
        order.verify(accounts).sandboxUser(1L);
        order.verify(ownership).lockCustomer(1L);
        order.verify(mapper).captureFence();
        verify(mapper).insertActivity(1L,1,"INTERACTIVE_LOGIN:"+session,now);
        verify(mapper).advanceWatermark();
        verify(maintenance).effectiveActivity(event);
    }

    @Test
    void canonicalProfileStillRejectsSandboxIdentityBeforeActivityTables() {
        environment.setActiveProfiles("prod");
        when(accounts.sandboxUser(1L)).thenReturn(1);
        assertThatThrownBy(()->activity.interactiveLogin(1L,UUID.randomUUID().toString()))
                .hasMessageContaining("SUPPORT_PRODUCTION_PATH_FORBIDDEN");
        verifyNoInteractions(mapper,ownership,maintenance);
    }
}
