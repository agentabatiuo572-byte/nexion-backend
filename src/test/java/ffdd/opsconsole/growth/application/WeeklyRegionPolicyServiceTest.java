package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.growth.application.WeeklyRegionPolicyService.*;
import ffdd.opsconsole.growth.mapper.WeeklyRegionPolicyMapper;
import ffdd.opsconsole.growth.mapper.WeeklyRegionPolicyMapper.LegacyWindow;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WeeklyRegionPolicyServiceTest {
    final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    final WeeklyRegionPolicyMapper mapper = mock(WeeklyRegionPolicyMapper.class);
    final Clock clock = mock(Clock.class);
    final AtomicReference<String> stored = new AtomicReference<>();
    final WeeklyRegionPolicyService service = new WeeklyRegionPolicyService(config, mapper, clock);
    final Instant start = Instant.parse("2026-10-18T16:00:00Z");
    final Instant end = Instant.parse("2026-10-25T16:00:00Z");

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(start.plusSeconds(3600));
        when(config.activeValue(WeeklyRegionPolicyService.KEY)).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(config.activeValueForUpdate(WeeklyRegionPolicyService.KEY)).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(config.insertAdminValueIfMissing(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> stored.compareAndSet(null, call.getArgument(1)));
        doAnswer(call -> { stored.set(call.getArgument(1)); return null; }).when(config)
                .upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
        when(mapper.openLegacy(any())).thenReturn(List.of(new LegacyWindow("WEEK:2026-W43",
                LocalDateTime.of(2026,10,19,0,0), LocalDateTime.of(2026,10,26,0,0))));
    }
    @AfterEach void clear() { A2ReplayContext.exitReplay(); }

    Proposal initial() { return new Proposal(0, service.read().mappings(), end, "WEEK:2026-W43"); }

    @Test void defaultReadIsPureAndNormalizesRegistrationLegacyFormsWithoutExpandingSignup() {
        assertThat(service.overview().nextActivation()).isEqualTo(new Activation(end, "WEEK:2026-W43"));
        assertThat(service.resolve(" + 8 6 ").zoneId()).isEqualTo("Asia/Shanghai");
        assertThat(service.resolve("84").zoneId()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(service.resolve("+81").source()).isEqualTo("DEFAULT");
        for (String code : Arrays.asList(null,"XX","+000","+1","UTC","+86x"))
            assertThat(service.resolve(code).zoneId()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(ffdd.opsconsole.shared.security.SupportedUserPhonePolicy.isSupportedCountryCode("+81")).isFalse();
        verify(config, never()).insertAdminValueIfMissing(any(),any(),any(),any(),any());
        verify(config, never()).upsertAdminValue(any(),any(),any(),any(),any());
    }

    @Test void noInventedActivationWhenLegacyHeaderMissingOverlappingFutureOrExpired() {
        when(mapper.openLegacy(any())).thenReturn(List.of());
        assertThat(service.overview().nextActivation()).isNull();
        assertThatThrownBy(() -> service.candidate(initial(), service.read())).hasMessage("WEEKLY_REGION_ACTIVATION_CONFLICT");
        var old = new LegacyWindow("WEEK:2026-W43", LocalDateTime.of(2026,10,19,0,0), LocalDateTime.of(2026,10,26,0,0));
        when(mapper.openLegacy(any())).thenReturn(List.of(old,old));
        assertThat(service.overview().nextActivation()).isNull();
        when(mapper.openLegacy(any())).thenReturn(List.of(old));
        when(clock.instant()).thenReturn(start.minusNanos(1));
        assertThat(service.overview().nextActivation()).isNull();
        when(clock.instant()).thenReturn(end);
        assertThat(service.overview().nextActivation()).isNull();
    }

    @Test void firstApprovedPublicationCapturesExactBoundaryAndFutureMappingsKeepIt() {
        A2ReplayContext.enterReplay("WO-H3-1");
        State first = service.publish(initial());
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.effectiveFrom()).isEqualTo(end);
        assertThat(service.overview().nextActivation()).isNull();
        when(clock.instant()).thenReturn(end.plusSeconds(100));
        State second = service.publish(new Proposal(1, List.of(new Mapping("81", "Asia/Tokyo")), null, null));
        assertThat(second.effectiveFrom()).isEqualTo(first.effectiveFrom());
        assertThat(second.lastLegacyInstanceKey()).isEqualTo(first.lastLegacyInstanceKey());
        assertThat(service.resolve("+81").zoneId()).isEqualTo("Asia/Tokyo");
        assertThat(service.resolve("+86").zoneId()).isEqualTo(WeeklyRegionPolicyService.DEFAULT_ZONE);
        assertThatThrownBy(() -> service.publish(new Proposal(1,List.of(),null,null)))
                .hasMessage("WEEKLY_REGION_VERSION_CONFLICT");
        assertThat(service.read()).isEqualTo(second);
        assertThat(service.publish(new Proposal(2,List.of(),null,null)).mappings()).isEmpty();
        assertThat(service.configuredZones()).containsExactly(WeeklyRegionPolicyService.DEFAULT_ZONE);
    }

    @Test void boundaryCannotBeForgedRechosenAfterExpiryOrMovedAfterActivation() {
        assertThatThrownBy(() -> service.candidate(new Proposal(0,List.of(),end.plusSeconds(1),"WEEK:2026-W43"),service.read()))
                .hasMessage("WEEKLY_REGION_ACTIVATION_CONFLICT");
        A2ReplayContext.enterReplay("WO-H3-1");
        State first = service.publish(initial());
        assertThatThrownBy(() -> service.candidate(new Proposal(1,List.of(),end.plusSeconds(1),"WEEK:2026-W44"),first))
                .hasMessage("WEEKLY_REGION_POLICY_INVALID");
        stored.set(null);
        when(clock.instant()).thenReturn(end);
        assertThatThrownBy(() -> service.candidate(initial(),service.read())).hasMessage("WEEKLY_REGION_ACTIVATION_CONFLICT");
    }

    @Test void malformedAndDuplicateMappingsAndUnknownFieldsFailBeforeWrite() {
        for (List<Mapping> mappings : List.of(List.of(new Mapping("+86","UTC")),
                List.of(new Mapping("+86","+08:00")), List.of(new Mapping("+86","Etc/GMT-8")),
                List.of(new Mapping("+86","Mars/Olympus")), List.of(new Mapping("+086","Asia/Shanghai")),
                List.of(new Mapping("86","Asia/Shanghai"),new Mapping("+86","Asia/Tokyo")))) {
            assertThatThrownBy(() -> service.candidate(new Proposal(0,mappings,end,"WEEK:2026-W43"),service.read()))
                    .isInstanceOf(BizException.class).hasMessage("WEEKLY_REGION_POLICY_INVALID");
        }
        assertThatThrownBy(() -> service.proposal(Map.of("expectedRevision",0,"mappings",List.of(),"defaultZoneId","UTC")))
                .hasMessage("WEEKLY_REGION_POLICY_INVALID");
        assertThatThrownBy(() -> service.proposal(Map.of("expectedRevision",0.1,"mappings",List.of())))
                .hasMessage("WEEKLY_REGION_POLICY_INVALID");
        assertThatThrownBy(() -> service.proposal(Map.of("expectedRevision",1,"mappings",List.of(),"effectiveFrom",end.toString())))
                .hasMessage("WEEKLY_REGION_POLICY_INVALID");
        verify(config, never()).upsertAdminValue(any(),any(),any(),any(),any());
    }

    @Test void missingApprovalCorruptStoredPolicyAndFailedReadbackFailClosed() {
        assertThatThrownBy(() -> service.publish(initial())).hasMessage("A2_CONFIRMATION_REQUIRED");
        stored.set("{}");
        assertThatThrownBy(service::read).hasMessage("WEEKLY_REGION_POLICY_UNAVAILABLE");
        stored.set(null);
        doNothing().when(config).upsertAdminValue(any(),any(),any(),any(),any());
        A2ReplayContext.enterReplay("WO-H3-1");
        assertThatThrownBy(() -> service.publish(initial())).hasMessage("WEEKLY_REGION_READBACK_FAILED");
    }
}
