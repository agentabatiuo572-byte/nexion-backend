package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskOpsRepository.MultiAccountSignalFact;
import ffdd.opsconsole.risk.domain.RiskOpsRepository.MultiAccountClusterState;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MultiAccountClusterBatchServiceTest {
    @Test
    void persistsProjectionAndPublishesDurableThresholdCrossingEvent() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.multiAccountConfigValues()).thenReturn(Map.of(
                "maxSignupPerIp24h", "1", "maxAccountsPerDevice", "1",
                "maxAccountsPerPaymentInstrument", "1", "clusterFreezeSuggestThreshold", "0.5",
                "linkWeight", "设备 1.00 · 支付 0.00 · IP 0.00"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 10, 0);
        when(repository.multiAccountSignalFacts()).thenReturn(List.of(
                fact(1, now, "shared-device"), fact(2, now.plusMinutes(1), "shared-device")));
        when(repository.activeIpWhitelistCidrs()).thenReturn(Set.of());
        MultiAccountClusterState detected = new MultiAccountClusterState(
                "K1-00000001", "detected", "device", 1.0,
                List.of("U00000001", "U00000002"), 0, "new-cluster", true);
        when(repository.multiAccountClusterState("K1-00000001"))
                .thenReturn(Optional.empty(), Optional.of(detected));

        MultiAccountClusterBatchService service = new MultiAccountClusterBatchService(
                repository, new MultiAccountClusterEngine(), outbox, audit);

        assertThat(service.rebuild()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RiskOpsRepository.MultiAccountClusterProjection>> projections = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertMultiAccountProjections(projections.capture());
        assertThat(projections.getValue().get(0).edges()).hasSize(1);
        verify(repository).retireMissingDetectedClusters(Set.of("K1-00000001"));
        verify(outbox).publish(eq("RISK_MULTI_ACCOUNT_CLUSTER"), eq("K1-00000001"),
                eq(MultiAccountClusterBatchService.EVENT_TYPE), argThat(payload ->
                        payload instanceof Map<?, ?> map
                                && "K1-00000001".equals(map.get("cluster_id"))
                                && "device".equals(map.get("dedup_layer"))
                                && Double.valueOf(0.6).equals(map.get("score"))
                                && Integer.valueOf(2).equals(map.get("linkedCount"))
                                && map.get("ts") instanceof String ts && !ts.isBlank()));
    }

    @Test
    void recordsWhitelistRemovalAsClearedBeforeRetiringOtherMissingDetections() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.multiAccountConfigValues()).thenReturn(Map.of("maxSignupPerIp24h", "1"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 10, 0);
        when(repository.multiAccountSignalFacts()).thenReturn(List.of(
                ipFact(7, now, "203.0.113.8"), ipFact(8, now.plusMinutes(1), "203.0.113.8")));
        when(repository.activeIpWhitelistCidrs()).thenReturn(Set.of("203.0.113.0/24"));
        MultiAccountClusterState detected = new MultiAccountClusterState(
                "K1-00000007", "detected", "ip", 0.03,
                List.of("U00000007", "U00000008"), 2, "existing-fingerprint");
        MultiAccountClusterState cleared = new MultiAccountClusterState(
                "K1-00000007", "cleared", "ip", 0.03,
                List.of("U00000007", "U00000008"), 3, "existing-fingerprint");
        when(repository.multiAccountClusterState("K1-00000007"))
                .thenReturn(Optional.of(detected), Optional.of(cleared));

        new MultiAccountClusterBatchService(repository, new MultiAccountClusterEngine(), outbox, audit).rebuild();

        verify(repository).clearWhitelistedDetectedClusters(Set.of("K1-00000007"));
        verify(repository).retireMissingDetectedClusters(Set.of());
        verify(audit).recordRequired(argThat(request ->
                "K1_CLUSTER_AUTO_CLEARED_BY_WHITELIST".equals(request.getAction())
                        && "K1-00000007".equals(request.getResourceId())));
    }

    @Test
    void preservesTerminalDecisionAndCreatesALineageIncidentForNewEvidence() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.multiAccountConfigValues()).thenReturn(Map.of(
                "maxAccountsPerDevice", "1", "clusterFreezeSuggestThreshold", "0.9"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 10, 0);
        List<MultiAccountSignalFact> newFacts = List.of(
                fact(1, now, "shared-device"), fact(2, now.plusMinutes(1), "shared-device"),
                fact(3, now.plusMinutes(2), "shared-device"));
        when(repository.multiAccountSignalFacts()).thenReturn(newFacts);
        when(repository.activeIpWhitelistCidrs()).thenReturn(Set.of());
        MultiAccountClusterEngine.Config config = new MultiAccountClusterEngine.Config(0.5, 0.4, 0.1, 0.9, 3, 1, 2);
        String oldFingerprint = new MultiAccountClusterEngine().project(
                newFacts.subList(0, 2), Set.of(), config).get(0).evidenceFingerprint();
        String newFingerprint = new MultiAccountClusterEngine().project(
                newFacts, Set.of(), config).get(0).evidenceFingerprint();
        String incidentId = "K1-00000001-" + newFingerprint.substring(0, 12).toUpperCase();
        MultiAccountClusterState released = new MultiAccountClusterState(
                "K1-00000001", "released", "device", 0.5,
                List.of("U00000001", "U00000002"), 7, oldFingerprint);
        MultiAccountClusterState incident = new MultiAccountClusterState(
                incidentId, "detected", "device", 0.5,
                List.of("U00000001", "U00000002", "U00000003"), 0, newFingerprint);
        when(repository.multiAccountClusterState("K1-00000001")).thenReturn(Optional.of(released));
        when(repository.multiAccountClusterState(incidentId))
                .thenReturn(Optional.empty(), Optional.of(incident));

        MultiAccountClusterBatchService service = new MultiAccountClusterBatchService(
                repository, new MultiAccountClusterEngine(), outbox, audit);
        assertThat(service.rebuild()).isEqualTo(1);

        verify(audit).recordRequired(argThat(request ->
                "K1_CLUSTER_INCIDENT_CREATED".equals(request.getAction())
                        && incidentId.equals(request.getResourceId())));
        verify(outbox).publish(eq("RISK_MULTI_ACCOUNT_CLUSTER"), eq(incidentId),
                eq(MultiAccountClusterBatchService.INCIDENT_EVENT_TYPE), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RiskOpsRepository.MultiAccountClusterProjection>> projections = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertMultiAccountProjections(projections.capture());
        assertThat(projections.getValue()).extracting(RiskOpsRepository.MultiAccountClusterProjection::clusterId)
                .containsExactly(incidentId);
    }

    @Test
    void weightChangesDoNotCreateANewIncidentWithoutNewEvidence() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.multiAccountConfigValues()).thenReturn(Map.of(
                "maxAccountsPerDevice", "1", "clusterFreezeSuggestThreshold", "0.9",
                "linkWeight", "设备 0.20 · 支付 0.70 · IP 0.10"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 10, 0);
        List<MultiAccountSignalFact> facts = List.of(
                fact(1, now, "shared-device"), fact(2, now.plusMinutes(1), "shared-device"));
        when(repository.multiAccountSignalFacts()).thenReturn(facts);
        when(repository.activeIpWhitelistCidrs()).thenReturn(Set.of());
        String fingerprint = new MultiAccountClusterEngine().project(
                facts, Set.of(), new MultiAccountClusterEngine.Config(0.5, 0.4, 0.1, 0.9, 3, 1, 2))
                .get(0).evidenceFingerprint();
        MultiAccountClusterState released = new MultiAccountClusterState(
                "K1-00000001", "released", "device", 0.5,
                List.of("U00000001", "U00000002"), 7, fingerprint);
        when(repository.multiAccountClusterState("K1-00000001")).thenReturn(Optional.of(released));

        new MultiAccountClusterBatchService(repository, new MultiAccountClusterEngine(), outbox, audit).rebuild();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RiskOpsRepository.MultiAccountClusterProjection>> projections = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertMultiAccountProjections(projections.capture());
        assertThat(projections.getValue()).extracting(RiskOpsRepository.MultiAccountClusterProjection::clusterId)
                .containsExactly("K1-00000001");
        verify(audit, never()).recordRequired(argThat(request ->
                "K1_CLUSTER_INCIDENT_CREATED".equals(request.getAction())));
        verify(outbox, never()).publish(eq("RISK_MULTI_ACCOUNT_CLUSTER"), any(),
                eq(MultiAccountClusterBatchService.INCIDENT_EVENT_TYPE), any());
    }

    @Test
    void whitelistEvidenceRemovalDoesNotCreateANewIncidentFromATerminalRoot() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.multiAccountConfigValues()).thenReturn(Map.of(
                "maxAccountsPerDevice", "1", "maxSignupPerIp24h", "1",
                "clusterFreezeSuggestThreshold", "0.9"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 10, 0);
        List<MultiAccountSignalFact> facts = List.of(
                fact(1, now, "shared-device"), fact(2, now.plusMinutes(1), "shared-device"),
                ipFact(1, now, "203.0.113.8"), ipFact(2, now.plusMinutes(1), "203.0.113.8"));
        when(repository.multiAccountSignalFacts()).thenReturn(facts);
        when(repository.activeIpWhitelistCidrs()).thenReturn(Set.of("203.0.113.0/24"));
        MultiAccountClusterEngine.Config config = new MultiAccountClusterEngine.Config(0.5, 0.4, 0.1, 0.9, 1, 1, 2);
        String fullFingerprint = new MultiAccountClusterEngine().project(facts, Set.of(), config)
                .get(0).evidenceFingerprint();
        MultiAccountClusterState released = new MultiAccountClusterState(
                "K1-00000001", "released", "device+ip", 0.6,
                List.of("U00000001", "U00000002"), 7, fullFingerprint);
        when(repository.multiAccountClusterState("K1-00000001")).thenReturn(Optional.of(released));

        assertThat(new MultiAccountClusterBatchService(
                repository, new MultiAccountClusterEngine(), outbox, audit).rebuild()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RiskOpsRepository.MultiAccountClusterProjection>> projections = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertMultiAccountProjections(projections.capture());
        assertThat(projections.getValue()).isEmpty();
        verify(audit, never()).recordRequired(argThat(request ->
                "K1_CLUSTER_INCIDENT_CREATED".equals(request.getAction())));
        verify(outbox, never()).publish(eq("RISK_MULTI_ACCOUNT_CLUSTER"), any(),
                eq(MultiAccountClusterBatchService.INCIDENT_EVENT_TYPE), any());
    }

    @Test
    void terminalRootNeverPublishesAThresholdEventAfterWeightOnlyCrossing() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.multiAccountConfigValues()).thenReturn(Map.of(
                "maxAccountsPerDevice", "1", "clusterFreezeSuggestThreshold", "0.7",
                "linkWeight", "设备 1.00 · 支付 0.00 · IP 0.00"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 10, 0);
        List<MultiAccountSignalFact> facts = List.of(
                fact(1, now, "shared-device"), fact(2, now.plusMinutes(1), "shared-device"));
        when(repository.multiAccountSignalFacts()).thenReturn(facts);
        when(repository.activeIpWhitelistCidrs()).thenReturn(Set.of());
        String fingerprint = new MultiAccountClusterEngine().project(
                facts, Set.of(), new MultiAccountClusterEngine.Config(0.5, 0.4, 0.1, 0.7, 3, 1, 2))
                .get(0).evidenceFingerprint();
        MultiAccountClusterState released = new MultiAccountClusterState(
                "K1-00000001", "released", "device", 0.2,
                List.of("U00000001", "U00000002"), 7, fingerprint, false);
        when(repository.multiAccountClusterState("K1-00000001")).thenReturn(Optional.of(released));

        new MultiAccountClusterBatchService(repository, new MultiAccountClusterEngine(), outbox, audit).rebuild();

        verify(outbox, never()).publish(eq("RISK_MULTI_ACCOUNT_CLUSTER"), eq("K1-00000001"),
                eq(MultiAccountClusterBatchService.EVENT_TYPE), any());
    }

    @Test
    void loweringThresholdPublishesTheFirstHitForAnExistingDetectedCluster() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.multiAccountConfigValues()).thenReturn(Map.of(
                "maxAccountsPerDevice", "1", "clusterFreezeSuggestThreshold", "0.2",
                "linkWeight", "设备 0.50 · 支付 0.40 · IP 0.10"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 10, 0);
        List<MultiAccountSignalFact> facts = List.of(
                fact(1, now, "shared-device"), fact(2, now.plusMinutes(1), "shared-device"));
        when(repository.multiAccountSignalFacts()).thenReturn(facts);
        when(repository.activeIpWhitelistCidrs()).thenReturn(Set.of());
        String fingerprint = new MultiAccountClusterEngine().project(
                facts, Set.of(), new MultiAccountClusterEngine.Config(0.5, 0.4, 0.1, 0.2, 3, 1, 2))
                .get(0).evidenceFingerprint();
        MultiAccountClusterState before = new MultiAccountClusterState(
                "K1-00000001", "detected", "device", 0.3,
                List.of("U00000001", "U00000002"), 4, fingerprint, false);
        MultiAccountClusterState after = new MultiAccountClusterState(
                "K1-00000001", "detected", "device", 0.3,
                List.of("U00000001", "U00000002"), 5, fingerprint, true);
        when(repository.multiAccountClusterState("K1-00000001"))
                .thenReturn(Optional.of(before), Optional.of(after));

        new MultiAccountClusterBatchService(repository, new MultiAccountClusterEngine(), outbox, audit).rebuild();

        verify(outbox).publish(eq("RISK_MULTI_ACCOUNT_CLUSTER"), eq("K1-00000001"),
                eq(MultiAccountClusterBatchService.EVENT_TYPE), argThat(payload ->
                        payload instanceof Map<?, ?> map
                                && "K1-00000001".equals(map.get("cluster_id"))
                                && "device".equals(map.get("dedup_layer"))
                                && Double.valueOf(0.3).equals(map.get("score"))
                                && Integer.valueOf(2).equals(map.get("linkedCount"))
                                && map.get("ts") instanceof String ts && !ts.isBlank()));
    }

    private MultiAccountSignalFact fact(long id, LocalDateTime joinedAt, String device) {
        return new MultiAccountSignalFact(id, "U" + String.format("%08d", id), joinedAt, null,
                false, BigDecimal.ZERO, "ACTIVE", "device", device, "设备 ***");
    }

    private MultiAccountSignalFact ipFact(long id, LocalDateTime joinedAt, String ip) {
        return new MultiAccountSignalFact(id, "U" + String.format("%08d", id), joinedAt, null,
                null, null, "ACTIVE", "ip", ip, "203.0.113.*");
    }
}
