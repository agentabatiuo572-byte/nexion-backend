package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.market.mapper.NexMarketMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.team.mapper.DirectReferralMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SevenLayerActivationServiceTest {
    @Test void commandRejectsClientTimeUnknownKeysAndNonIntegerVersions() {
        var valid=Map.<String,Object>of("action","ACTIVATE","expectedSevenLayerRevision",0,"expectedPolicyVersion",4);
        assertThat(ffdd.opsconsole.team.dto.SevenLayerActivationRequest.from(valid).action()).isEqualTo("ACTIVATE");
        for(var invalid:List.of(Map.of("cutoverAt","2026-10-18T00:00:00Z"),Map.of("action","DELETE"),
                Map.of("expectedSevenLayerRevision",-1),Map.of("expectedPolicyVersion",1.2),Map.of("expectedPolicyVersion","4"),
                Map.of("expectedPolicyVersion",new java.math.BigDecimal("9223372036854775808")))) {
            var params=new HashMap<String,Object>(valid);params.putAll(invalid);
            assertThatThrownBy(()->ffdd.opsconsole.team.dto.SevenLayerActivationRequest.from(params)).hasMessage("SEVEN_LAYER_ACTIVATION_REQUEST_INVALID");
        }
    }
    @Test void normalF2CanIdentifyTheDeliveredActivationContractWithoutEnablingAnything() {
        var mapper=mock(DirectReferralMapper.class);
        var config=mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        var environment=new MockEnvironment();environment.setActiveProfiles("prod");
        var service=new DirectReferralPolicyService(mapper,config,mock(NexMarketMapper.class),mock(TreasuryCoverageFacade.class),
                mock(AuditLogService.class),new ObjectMapper().findAndRegisterModules(),Clock.fixed(Instant.parse("2026-10-18T05:00:00Z"),ZoneOffset.UTC),environment);
        var state=service.current(2);
        assertThat(state.get("sevenLayerActivationContractVersion")).isEqualTo(1);
        assertThat(state.get("sevenLayerPaused")).isEqualTo(false);
        assertThat(state.get("sevenLayerEnabled")).isEqualTo(false);
        assertThat(state.get("cutoverAt")).isNull();
        verify(config,never()).upsertAdminValue(anyString(),anyString(),anyString(),anyString(),anyString());
    }
}
