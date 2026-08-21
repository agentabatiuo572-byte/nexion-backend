package ffdd.opsconsole.risk.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.mapper.B5RiskRadarMapper;
import ffdd.opsconsole.risk.mapper.B5RiskRadarMapper.AlertDeliveryRecord;
import java.time.LocalDateTime;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class B5RiskAlertDeliveryServiceTest {
    private final B5RiskRadarMapper mapper = mock(B5RiskRadarMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final B5RiskAlertDeliveryFinalizer finalizer = mock(B5RiskAlertDeliveryFinalizer.class);
    private final B5RiskAlertDeliveryService service = service("");

    @Test
    void sandboxEmailPersistsMockReceiptAndDoesNotEnterRetryLoop() {
        B5RiskAlertDeliveryService testProfileService = service("test");
        when(config.activeValue("risk.alert-subscription.subscriber")).thenReturn(Optional.of("risk-admin"));
        when(config.activeValue("risk.alert-subscription.channels")).thenReturn(Optional.of("email"));
        when(config.activeValue("risk.alert-subscription.email-mode")).thenReturn(Optional.of("sandbox"));
        when(mapper.undeliveredSignalNos("risk-admin", "email", 500)).thenReturn(List.of());
        when(mapper.dueAlertDeliveries(100)).thenReturn(List.of(new AlertDeliveryRecord(
                7L, "SIG-7", "risk-admin", "email", "PENDING", 0, LocalDateTime.now())));
        when(finalizer.claim(7L)).thenReturn(true);

        testProfileService.scanAndDispatch();

        verify(finalizer).complete(org.mockito.ArgumentMatchers.argThat(row -> row.id() == 7L), eq("mock"), org.mockito.ArgumentMatchers.startsWith("sandbox:"));
    }

    @Test
    void productionProfileCannotTurnSandboxConfigIntoDeliveredEmail() {
        B5RiskAlertDeliveryService productionProfileService = service("prod");
        when(config.activeValue("risk.alert-subscription.subscriber")).thenReturn(Optional.of("risk-admin"));
        when(config.activeValue("risk.alert-subscription.channels")).thenReturn(Optional.of("email"));
        when(config.activeValue("risk.alert-subscription.email-mode")).thenReturn(Optional.of("sandbox"));

        assertThatThrownBy(productionProfileService::scanAndDispatch)
                .hasMessage("B5_EMAIL_PROVIDER_UNAVAILABLE");
        verify(finalizer, never()).complete(any(), any(), any());
    }

    @Test
    void signalsMissedDuringAnOutageAreBackfilledWithoutATimeWindow() {
        when(config.activeValue("risk.alert-subscription.subscriber")).thenReturn(Optional.of("risk-admin"));
        when(config.activeValue("risk.alert-subscription.channels")).thenReturn(Optional.of("inApp"));
        when(mapper.undeliveredSignalNos("risk-admin", "inApp", 500)).thenReturn(List.of("SIG-OLD"));
        when(mapper.dueAlertDeliveries(100)).thenReturn(List.of());

        service.scanAndDispatch();

        verify(mapper).enqueueAlertDelivery("SIG-OLD", "risk-admin", "inApp");
    }

    @Test
    void terminalFailureMovesToDeadLetterAtTheRetryCap() {
        when(config.activeValue("risk.alert-subscription.subscriber")).thenReturn(Optional.of("risk-admin"));
        when(config.activeValue("risk.alert-subscription.channels")).thenReturn(Optional.of("bogus"));
        when(mapper.undeliveredSignalNos("risk-admin", "bogus", 500)).thenReturn(List.of());
        when(mapper.dueAlertDeliveries(100)).thenReturn(List.of(new AlertDeliveryRecord(
                9L, "SIG-9", "risk-admin", "bogus", "FAILED_RETRY", 4, LocalDateTime.now())));
        when(finalizer.claim(9L)).thenReturn(true);

        service.dispatchDueForTest();

        verify(finalizer).fail(org.mockito.ArgumentMatchers.argThat(row -> row.id() == 9L), eq("B5_ALERT_CHANNEL_INVALID"), eq(5));
    }

    @Test
    void webhookRejectsMetadataAndNonAllowlistedHostsBeforeDispatch() {
        assertThatThrownBy(() -> B5RiskAlertDeliveryService.requireAllowedPublicHttps(
                URI.create("https://169.254.169.254/latest/meta-data"), Set.of("169.254.169.254")))
                .hasMessage("B5_WEBHOOK_PRIVATE_ADDRESS_FORBIDDEN");
        assertThatThrownBy(() -> B5RiskAlertDeliveryService.requireAllowedPublicHttps(
                URI.create("https://evil.example/hook"), Set.of("hooks.example")))
                .hasMessage("B5_WEBHOOK_HOST_NOT_ALLOWED");
    }

    @Test
    void dnsRebindingCannotFallBackToDirectDispatchWithoutControlledEgressProxy() {
        when(config.activeValue(B5RiskAlertDeliveryService.WEBHOOK_EGRESS_PROXY_KEY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(service::webhookHttpClient)
                .hasMessage("B5_WEBHOOK_EGRESS_PROXY_UNAVAILABLE");
    }

    private B5RiskAlertDeliveryService service(String activeProfiles) {
        return new B5RiskAlertDeliveryService(mapper, config, finalizer, activeProfiles);
    }
}
