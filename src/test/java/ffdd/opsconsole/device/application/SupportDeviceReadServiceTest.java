package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper.Row;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class SupportDeviceReadServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 12, 0);
    private final SupportDeviceReadMapper mapper = mock(SupportDeviceReadMapper.class);
    private final SupportDeviceReadService service = new SupportDeviceReadService(mapper);

    @Test void emptyAuthorizedScopeNeverReadsAllCustomers() {
        var result = service.readCurrent(List.of());
        assertThat(result.devices()).isEmpty();
        assertThat(result.unknownHoldingDevices()).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test void malformedScopeIsRejectedBeforeReading() {
        for (var scope : Arrays.asList(null, List.of(0L), Arrays.asList(7L, null)))
            assertThatThrownBy(() -> service.readCurrent(scope)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_SUPPORT_DEVICE_SCOPE");
        verifyNoInteractions(mapper);
    }

    @Test void scopeAndDevicesAreDeduplicatedWithDeterministicIdentityOrder() {
        var first = row(1, "OWNED", "ACTIVE", "ORDER", "O1", NOW, "ONLINE", 1);
        var second = row(2, "OWNED", "ACTIVE", "ORDER", "O2", NOW, "ONLINE", 1);
        when(mapper.readCurrent(List.of(7L, 9L))).thenReturn(List.of(second, first, first));
        var result = service.readCurrent(List.of(9L, 7L, 7L));
        assertThat(result.devices()).extracting(d -> d.deviceId()).containsExactly(1L, 2L);
        assertThat(result.evaluatedDbAt()).isEqualTo(NOW);
        verify(mapper).readCurrent(List.of(7L, 9L));
        assertThatThrownBy(() -> result.devices().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void conflictingDuplicateDeviceCannotBecomeTwoDevicesOrHideChangedOwnership() {
        when(mapper.readCurrent(List.of(7L))).thenReturn(List.of(
            row(1, "OWNED", "ACTIVE", "ORDER", "O1", NOW, "ONLINE", 1),
            row(1, "OWNED", "ACTIVE", "ORDER", "DIFFERENT", NOW, "ONLINE", 1)));
        assertThatThrownBy(() -> service.readCurrent(List.of(7L))).hasMessage("CONFLICTING_SUPPORT_DEVICE_ROW");
    }

    @Test void disposalAndTransferAreExcludedButUnknownHoldingIsVisible() {
        when(mapper.readCurrent(List.of(7L))).thenReturn(List.of(
            row(1, "OWNED", "RECYCLED", "ORDER", "O1", NOW, "ONLINE", 1),
            row(2, "REFUNDED", "DEACTIVATED", "ORDER", "O2", NOW, "OFFLINE", 1),
            row(3, "TRANSFERRED", "ACTIVE", "ORDER", "O3", NOW, "ONLINE", 1),
            row(4, "UNBOUND", "ACTIVE", "ORDER", "O4", NOW, "ONLINE", 1),
            row(5, null, "ACTIVE", "ORDER", "O5", NOW, "ONLINE", 1)));
        var result = service.readCurrent(List.of(7L));
        assertThat(result.devices()).isEmpty();
        assertThat(result.unknownHoldingDevices()).extracting(d -> d.deviceId()).containsExactly(5L);
        assertThat(result.unknownHoldingDevices().get(0).connectionStatus()).isEqualTo(ConnectionStatus.UNKNOWN);
    }

    @Test void stoppedInventoryAndDeferredStopDoNotEraseStillOwnedStock() {
        var base = row(1, "OWNED", "ACTIVE", "TRIAL", "TRC1", NOW, "ONLINE", 1);
        when(mapper.readCurrent(List.of(7L))).thenReturn(List.of(
            lifecycle(base, "DEACTIVATED", base.activatedAt(), NOW.minusHours(1), 0),
            lifecycle(row(2, "OWNED", "ACTIVE", "ORDER", "O2", NOW, "ONLINE", 1), "INVENTORY", null, NOW, 0),
            lifecycle(row(3, "OWNED", "ACTIVE", "ORDER", "O3", NOW, "ONLINE", 1), "PENDING_ACTIVATION", null, null, 0),
            lifecycle(row(4, "OWNED", "ACTIVE", "ORDER", "O4", NOW, "ONLINE", 1), "RUNNING", NOW.minusDays(1), null, 1)));
        var result = service.readCurrent(List.of(7L));
        assertThat(result.devices()).extracting(d -> d.deviceId()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(result.devices()).extracting(d -> d.connectionStatus()).containsExactly(
            ConnectionStatus.NOT_APPLICABLE, ConnectionStatus.NOT_APPLICABLE,
            ConnectionStatus.NOT_APPLICABLE, ConnectionStatus.ONLINE);
        assertThat(result.devices().get(0).deactivatedAt()).isEqualTo(NOW.minusHours(1));
    }

    @Test void trialAndGiftChannelsRemainRawAndNeverClassifyPayment() {
        when(mapper.readCurrent(List.of(7L))).thenReturn(List.of(
            row(1, "OWNED", "ACTIVE", "TRIAL", "TRC-PAID", NOW, "ONLINE", 1),
            row(2, "OWNED", "ACTIVE", "TRIAL", null, NOW, "ONLINE", 1),
            row(3, "OWNED", "ACTIVE", "GIFT", null, NOW, "ONLINE", 1)));
        var devices = service.readCurrent(List.of(7L)).devices();
        assertThat(devices).extracting(d -> d.sourceChannel()).containsExactly("TRIAL", "TRIAL", "GIFT");
        assertThat(devices).extracting(d -> d.sourceOrderNo()).containsExactly("TRC-PAID", null, null);
        assertThat(devices.get(0).runtime().pausedReason()).isEqualTo("maintenance");
        assertThat(devices.get(0).runtime().activeTaskNo()).isEqualTo("TASK1");
    }

    @Test void absentAndFutureHeartbeatAreUnknownEvenWithReportedOffline() {
        var missing = row(1, "OWNED", "ACTIVE", "ORDER", "O1", null, "OFFLINE", 0);
        when(mapper.readCurrent(List.of(7L))).thenReturn(List.of(missing,
            runtime(row(2, "OWNED", "ACTIVE", "ORDER", "O2", NOW, "ONLINE", 1), null, null, null, 0),
            row(3, "OWNED", "ACTIVE", "ORDER", "O3", NOW.plusSeconds(1), "OFFLINE", 0)));
        assertThat(service.readCurrent(List.of(7L)).devices()).extracting(d -> d.connectionStatus())
            .containsOnly(ConnectionStatus.UNKNOWN);
    }

    @Test void cloudShareDoesNotInventALackOfConnectionCapability() {
        var base = row(1, "OWNED", "ACTIVE", "ORDER", "O1", NOW, "ONLINE", 1);
        var share = new Row(base.deviceId(), base.customerId(), base.sourceOrderNo(), base.sourceChannel(), "SHARE",
            base.hashrate(), base.ownershipStatus(), base.lifecycleStatus(), base.activatedAt(), base.deactivatedAt(),
            base.pendingDeactivate(), base.sourceEnvironment(), base.runId(), base.runtimeId(), base.runtimeStatus(),
            base.heartbeatAt(), base.pausedReason(), base.activeTaskNo(), base.networkReachable(), base.runtimeUpdatedAt(),
            base.evaluatedDbAt(), base.heartbeatFresh());
        when(mapper.readCurrent(List.of(7L))).thenReturn(List.of(share));
        var device = service.readCurrent(List.of(7L)).devices().get(0);
        assertThat(device.deviceType()).isEqualTo("SHARE");
        assertThat(device.connectionStatus()).isEqualTo(ConnectionStatus.ONLINE);
    }

    @Test void databaseFreshnessVerdictDistinguishesOnlineStaleAndAbnormalEvidence() {
        when(mapper.readCurrent(List.of(7L))).thenReturn(List.of(
            row(1, "OWNED", "ACTIVE", "ORDER", "O1", NOW.minusMinutes(10), "ONLINE", 1),
            row(2, "OWNED", "ACTIVE", "ORDER", "O2", NOW.minusMinutes(10).minusNanos(1000), "ONLINE", 0),
            row(3, "OWNED", "ACTIVE", "ORDER", "O3", NOW, "OFFLINE", 1),
            row(4, "OWNED", "ACTIVE", "ORDER", "O4", NOW, "ERROR", 1)));
        assertThat(service.readCurrent(List.of(7L)).devices()).extracting(d -> d.connectionStatus())
            .containsExactly(ConnectionStatus.ONLINE, ConnectionStatus.OFFLINE, ConnectionStatus.OFFLINE, ConnectionStatus.UNKNOWN);
    }

    @Test void foreignDisposedRowIsStillAHardScopeError() {
        var base = row(1, "OWNED", "RECYCLED", "ORDER", "O1", NOW, "ONLINE", 1);
        var foreign = new Row(base.deviceId(), 99L, base.sourceOrderNo(), base.sourceChannel(), base.deviceType(),
            base.hashrate(), base.ownershipStatus(), base.lifecycleStatus(), base.activatedAt(), base.deactivatedAt(),
            base.pendingDeactivate(), base.sourceEnvironment(), base.runId(), base.runtimeId(), base.runtimeStatus(),
            base.heartbeatAt(), base.pausedReason(), base.activeTaskNo(), base.networkReachable(), base.runtimeUpdatedAt(),
            base.evaluatedDbAt(), base.heartbeatFresh());
        when(mapper.readCurrent(List.of(7L))).thenReturn(List.of(foreign));
        assertThatThrownBy(() -> service.readCurrent(List.of(7L))).hasMessage("INVALID_SUPPORT_DEVICE_SCOPE");
    }

    @Test void sourceFailureIsNotAnEmptyOrUnknownSuccess() {
        var failure = new DataAccessResourceFailureException("device-source-unavailable");
        when(mapper.readCurrent(List.of(7L))).thenThrow(failure);
        assertThatThrownBy(() -> service.readCurrent(List.of(7L))).isSameAs(failure);
    }

    @Test void malformedSourceResultCannotBeAZeroCount() {
        when(mapper.readCurrent(List.of(7L))).thenReturn(null);
        assertThatThrownBy(() -> service.readCurrent(List.of(7L))).hasMessage("INVALID_SUPPORT_DEVICE_ROW");
        when(mapper.readCurrent(List.of(7L))).thenReturn(Arrays.asList((Row) null));
        assertThatThrownBy(() -> service.readCurrent(List.of(7L))).hasMessage("INVALID_SUPPORT_DEVICE_ROW");
    }

    @Test void provenEmptyResultKeepsItsDatabaseObservationTime() {
        when(mapper.readCurrent(List.of(7L))).thenReturn(List.of());
        when(mapper.currentDbTime()).thenReturn(NOW);
        var result = service.readCurrent(List.of(7L));
        assertThat(result.devices()).isEmpty();
        assertThat(result.unknownHoldingDevices()).isEmpty();
        assertThat(result.evaluatedDbAt()).isEqualTo(NOW);
    }

    private static Row row(long id, String ownership, String lifecycle, String channel, String order,
                           LocalDateTime heartbeat, String reported, int fresh) {
        return new Row(id, 7L, order, channel, "DEVICE", new BigDecimal("12.345678"), ownership, lifecycle,
            NOW.minusDays(1), null, 0, "PRODUCTION", "", id + 100, reported, heartbeat,
            "maintenance", "TASK" + id, 1, NOW, NOW, fresh);
    }

    private static Row lifecycle(Row row, String status, LocalDateTime activated, LocalDateTime deactivated, int pending) {
        return new Row(row.deviceId(), row.customerId(), row.sourceOrderNo(), row.sourceChannel(), row.deviceType(),
            row.hashrate(), row.ownershipStatus(), status, activated, deactivated, pending, row.sourceEnvironment(), row.runId(),
            row.runtimeId(), row.runtimeStatus(), row.heartbeatAt(), row.pausedReason(), row.activeTaskNo(), row.networkReachable(),
            row.runtimeUpdatedAt(), row.evaluatedDbAt(), row.heartbeatFresh());
    }

    private static Row runtime(Row row, Long runtimeId, String status, LocalDateTime heartbeat, int fresh) {
        return new Row(row.deviceId(), row.customerId(), row.sourceOrderNo(), row.sourceChannel(), row.deviceType(),
            row.hashrate(), row.ownershipStatus(), row.lifecycleStatus(), row.activatedAt(), row.deactivatedAt(),
            row.pendingDeactivate(), row.sourceEnvironment(), row.runId(), runtimeId, status, heartbeat, null, null, null,
            null, row.evaluatedDbAt(), fresh);
    }
}
