package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.mapper.SupportCustomerProfileMapper;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.application.SupportDeviceReadService;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper.Row;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class SupportCustomerDeviceIdcContractTest {
    private final SupportCustomerProfileMapper mapper = mock(SupportCustomerProfileMapper.class);
    private final OpsDeviceService devices = mock(OpsDeviceService.class);
    private final SupportDeviceReadMapper current = mock(SupportDeviceReadMapper.class);
    private final SupportCustomerProfileService profile = new SupportCustomerProfileService(
            mock(SupportOwnershipService.class), null, mapper, null, null, devices, null, null, null, null, null, null,
            new SupportDeviceReadService(current));
    private static final LocalDateTime NOW = LocalDateTime.of(2026,10,13,0,0);
    @BeforeEach void currentObservation() { when(current.currentDbTime()).thenReturn(NOW); }

    @Test void emptyInventoryKeepsKnownZeroWithoutCreatingAnIdleCategory() {
        when(current.readCurrent(List.of(7L))).thenReturn(List.of());
        var data = read(0);
        assertThat(data).containsEntry("total", 0L).containsEntry("onlineCount", 0L).containsEntry("hashrateTotal", "0");
        assertThat(data).containsEntry("heldCount",0L);assertThat(statuses(data)).containsEntry("onlineCount", "READY").containsEntry("heldCount","READY");
        assertNoIdle(data);
    }

    @Test void missingTelemetryKeepsUnknownWithoutCreatingAnIdleCategory() {
        when(current.readCurrent(List.of(7L))).thenReturn(List.of(
                row(1L,"OWNED","ACTIVE","PRODUCTION",null,"ONLINE"),row(2L,"OWNED","ACTIVE","PRODUCTION",null,"ONLINE"),
                row(3L,"OWNED","ACTIVE","PRODUCTION",null,"ONLINE"),row(4L,"OWNED","ACTIVE","PRODUCTION",null)));
        var data = read(4);
        assertThat(data).containsEntry("total", 4L).containsEntry("onlineCount", null).containsEntry("hashrateTotal", null);
        assertThat(data).containsEntry("heldCount",4L).containsEntry("observedOnlineCount",3L).containsEntry("telemetryUnknownCount",1L);
        assertThat(statuses(data)).containsEntry("onlineCount", "UNKNOWN");
        assertNoIdle(data);
    }

    @Test void refundedHistoryRemainsPaginatedButCurrentHoldingAndConversationAreZero() {
        when(current.readCurrent(List.of(7L))).thenReturn(List.of(row(93L,"REFUNDED","DEACTIVATED","PRODUCTION",null)));
        var historical = mock(DeviceOpsView.class);when(historical.id()).thenReturn(93L);when(historical.userId()).thenReturn(7L);
        when(historical.status()).thenReturn("DEACTIVATED");
        when(devices.devices(any())).thenReturn(ApiResult.ok(new PageResult<DeviceOpsView>(1,1,20,List.of(historical))));
        @SuppressWarnings("unchecked") var data=(Map<String,Object>)profile.devices(7L,1,20).get("data");
        assertThat(data).containsEntry("total",1L).containsEntry("heldCount",0L).containsEntry("observedHeldCount",0L).containsEntry("unknownHoldingCount",0L);
        assertThat(data).containsEntry("onlineCount",0L);assertThat(statuses(data)).containsEntry("onlineCount","READY");
        assertThat(statuses(data)).containsEntry("heldCount","READY");
        assertThat((List<?>)data.get("records")).hasSize(1);
        assertThat(((Map<?,?>)((List<?>)data.get("records")).get(0)).get("id")).isEqualTo(93L);
        assertThat(profile.conversationProfile(7L).device()).isEqualTo("0");
    }

    @Test void currentHoldingIncludesOfflineAndInventoryExcludesTestAndKeepsUncertainNull() {
        var offline=row(1L,"OWNED","OFFLINE","PRODUCTION",null,"OFFLINE");
        var inventory=row(2L,"OWNED","PENDING_ACTIVATION","PRODUCTION",null);
        var test=row(3L,"OWNED","ACTIVE","TEST","fixture","ONLINE");
        when(current.readCurrent(List.of(7L))).thenReturn(List.of(offline,inventory,test));
        var data=read(3);
        assertThat(data).containsEntry("total",3L).containsEntry("heldCount",2L).containsEntry("onlineCount",0L);
        assertThat(statuses(data)).containsEntry("heldCount","READY").containsEntry("onlineCount","READY");
        when(current.readCurrent(List.of(7L))).thenReturn(List.of(offline,inventory,test,
                row(4L,"UNKNOWN","ACTIVE","PRODUCTION",null),row(5L,"OWNED","ACTIVE",null,null)));
        data=read(5);
        assertThat(data).containsEntry("total",5L).containsEntry("heldCount",null).containsEntry("observedHeldCount",2L).containsEntry("unknownHoldingCount",2L);
        assertThat(data).containsEntry("onlineCount",null);assertThat(statuses(data)).containsEntry("heldCount","UNKNOWN").containsEntry("onlineCount","UNKNOWN");
        assertThat(profile.conversationProfile(7L).device()).isNull();
        when(current.readCurrent(List.of(7L))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("device source unavailable"));
        data=read(5);
        assertThat(data).containsEntry("total",5L).containsEntry("heldCount",null).containsEntry("observedHeldCount",null);
        assertThat(data).containsEntry("onlineCount",null);assertThat(statuses(data)).containsEntry("heldCount","ERROR").containsEntry("onlineCount","ERROR");
    }

    @Test void onlineOfflineNotApplicableAndUnknownUseTheSameProductionHoldingSnapshot() {
        var dev=row(10L,"OWNED","ACTIVE","DEV",null,"ONLINE");
        var test=row(11L,"OWNED","ACTIVE","TEST","fixture","ONLINE");
        when(current.readCurrent(List.of(7L))).thenReturn(List.of(dev,test));
        var data=read(2);
        assertThat(data).containsEntry("heldCount",0L).containsEntry("onlineCount",0L).containsEntry("total",2L);
        assertThat(statuses(data)).containsEntry("heldCount","READY").containsEntry("onlineCount","READY");
        var online=row(1L,"OWNED","ACTIVE","PRODUCTION",null,"ONLINE");
        var offline=row(2L,"OWNED","OFFLINE","PRODUCTION",null,"OFFLINE");
        var inventory=row(3L,"OWNED","PENDING_ACTIVATION","PRODUCTION",null);
        when(current.readCurrent(List.of(7L))).thenReturn(List.of(online,offline,inventory,dev,test));
        data=read(5);
        assertThat(data).containsEntry("heldCount",3L).containsEntry("onlineCount",1L).containsEntry("telemetryUnknownCount",0L);
        assertThat(statuses(data)).containsEntry("onlineCount","READY");
        when(current.readCurrent(List.of(7L))).thenReturn(List.of(online,offline,inventory,dev,test,
                row(4L,"OWNED","ACTIVE","PRODUCTION",null)));
        data=read(6);
        assertThat(data).containsEntry("heldCount",4L).containsEntry("onlineCount",null).containsEntry("observedOnlineCount",1L).containsEntry("telemetryUnknownCount",1L);
        assertThat(statuses(data)).containsEntry("heldCount","READY").containsEntry("onlineCount","UNKNOWN");
        when(current.readCurrent(List.of(7L))).thenReturn(List.of(row(12L,"OWNED","ACTIVE","PRODUCTION","fixture","ONLINE")));
        data=read(1);
        assertThat(data).containsEntry("heldCount",null).containsEntry("onlineCount",null).containsEntry("unknownHoldingCount",1L);
        assertThat(statuses(data)).containsEntry("heldCount","UNKNOWN").containsEntry("onlineCount","UNKNOWN");
    }

    private static Row row(long id,String owner,String lifecycle,String environment,String run) {
        return row(id,owner,lifecycle,environment,run,null);
    }
    private static Row row(long id,String owner,String lifecycle,String environment,String run,String runtime) {
        return new Row(id,7L,"ORDER-"+id,"ORDER","DEVICE",BigDecimal.ONE,owner,lifecycle,
                "PENDING_ACTIVATION".equals(lifecycle)?null:NOW.minusDays(1),"DEACTIVATED".equals(lifecycle)?NOW:null,0,
                environment,run,runtime==null?null:id,runtime,runtime==null?null:NOW,null,null,null,runtime==null?null:NOW,NOW,runtime==null?0:1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(long total) {
        when(devices.devices(any())).thenReturn(ApiResult.ok(new PageResult<DeviceOpsView>(total, 1, 20, List.of())));
        var result = profile.devices(7L, 1, 20);
        assertThat(result).containsEntry("status", "READY");
        return (Map<String, Object>) result.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> statuses(Map<String, Object> data) {
        return (Map<String, Object>) data.get("fieldStatuses");
    }

    private static void assertNoIdle(Map<String, Object> data) {
        assertThat(data).doesNotContainKey("idleCount");
        assertThat(statuses(data)).doesNotContainKey("idleCount");
    }
}
