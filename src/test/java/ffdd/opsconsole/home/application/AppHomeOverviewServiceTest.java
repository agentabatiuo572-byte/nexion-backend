package ffdd.opsconsole.home.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.application.ComputeTaskProofVerifier;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppHomeOverviewServiceTest {
    private final AppHomeOverviewMapper mapper = org.mockito.Mockito.mock(AppHomeOverviewMapper.class);
    private final ComputeTaskProofVerifier verifier = org.mockito.Mockito.mock(ComputeTaskProofVerifier.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC);
    private final MockEnvironment environment = new MockEnvironment();
    private final AppHomeOverviewService service = new AppHomeOverviewService(mapper, verifier, clock, environment);

    @Test
    void sandboxFailsClosedWhenFactsHaveNoRunDimension() {
        when(verifier.sourceEnvironment()).thenReturn("SANDBOX");
        environment.setActiveProfiles("local-sandbox");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(true));
        var result = service.overview(42L);
        assertEquals(503, result.getCode());
        assertEquals("APP_HOME_SANDBOX_FACTS_UNAVAILABLE", result.getMessage());
        verify(mapper).userEnvironment(42L);
        verify(mapper, org.mockito.Mockito.never()).earnings(eq(42L), any(), any(), any());
    }

    @Test
    void sandboxAccountMismatchIsRejectedBeforeFactsUnavailable() {
        when(verifier.sourceEnvironment()).thenReturn("SANDBOX");
        environment.setActiveProfiles("acceptance");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));

        var result = service.overview(42L);

        assertEquals(403, result.getCode());
        assertEquals("USER_ENVIRONMENT_MISMATCH", result.getMessage());
        verify(mapper).userEnvironment(42L);
        verify(mapper, org.mockito.Mockito.never()).earnings(eq(42L), any(), any(), any());
    }

    @Test
    void isolatedAcceptanceProfileFailsClosedEvenIfProofModeDefaultsToProduction() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        environment.setActiveProfiles("test");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(true));

        var result = service.overview(42L);

        assertEquals(503, result.getCode());
        assertEquals("APP_HOME_SANDBOX_FACTS_UNAVAILABLE", result.getMessage());
        verify(mapper).userEnvironment(42L);
        verify(mapper, org.mockito.Mockito.never()).earnings(eq(42L), any(), any(), any());
    }

    @Test
    void rejectsAccountFromDifferentEnvironmentBeforeReadingFacts() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(true));

        assertEquals(403, service.overview(42L).getCode());
        verify(mapper, org.mockito.Mockito.never()).earnings(eq(42L), any(), any(), any());
    }

    @Test
    void productionAccountReadsOnlyProductionProjectionAndMayReturnEmptyFacts() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));

        var result = service.overview(42L);

        assertEquals(0, result.getCode());
        assertEquals(true, result.getData().get("serverCanonical"));
        assertEquals("PRODUCTION", result.getData().get("sourceEnvironment"));
        assertEquals("", result.getData().get("runId"));
        verify(mapper).userEnvironment(42L);
        verify(mapper, org.mockito.Mockito.times(4)).earnings(eq(42L), eq("PRODUCTION"), any(), any());
    }

    @Test
    void unknownOrMixedProfileCannotBePresentedAsProductionHomeFacts() {
        when(verifier.sourceEnvironment()).thenReturn("PRODUCTION");
        environment.setActiveProfiles("production", "test");

        var result = service.overview(42L);

        assertEquals(503, result.getCode());
        assertEquals("APP_HOME_PROFILE_INVALID", result.getMessage());
        verify(mapper, org.mockito.Mockito.never()).userEnvironment(42L);
    }
}
