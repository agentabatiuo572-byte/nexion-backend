package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.dto.DatacenterOpsRequest;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import ffdd.opsconsole.device.dto.DeviceRestoreRequest;
import ffdd.opsconsole.device.dto.E3ConfigUpdateRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsDeviceControllerTest {
    private final OpsDeviceService deviceService = mock(OpsDeviceService.class);
    private final OpsDeviceController controller = new OpsDeviceController(deviceService);

    @Test
    void devicesDelegatesQueryToService() {
        DeviceOpsQueryRequest request = new DeviceOpsQueryRequest("OFFLINE", "HK-1", "NX", 1L, 20L);
        when(deviceService.devices(request)).thenReturn(ApiResult.ok(new PageResult<>(0, 1, 20, List.of())));

        assertThat(controller.devices(request).getData().getPageNum()).isEqualTo(1);

        verify(deviceService).devices(request);
    }

    @Test
    void restoreDelegatesWithIdempotencyHeader() {
        DeviceRestoreRequest request = new DeviceRestoreRequest("mistaken recycle", "superadmin");
        when(deviceService.restoreDevice(1L, "idem-restore", request)).thenReturn(ApiResult.ok(mock(DeviceOpsView.class)));

        assertThat(controller.restoreDevice(1L, "idem-restore", request).getCode()).isZero();

        verify(deviceService).restoreDevice(1L, "idem-restore", request);
    }

    @Test
    void e3ConfigDelegatesWithIdempotencyHeader() {
        E3ConfigUpdateRequest request = new E3ConfigUpdateRequest("promoCooldownDays", "21", "holiday", "superadmin");
        when(deviceService.updateE3Config("idem-e3", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateE3Config("idem-e3", request).getData()).containsEntry("ok", true);

        verify(deviceService).updateE3Config("idem-e3", request);
    }

    @Test
    void datacenterPauseDelegatesWithIdempotencyHeader() {
        DatacenterOpsRequest request = new DatacenterOpsRequest("maintenance", "superadmin");
        when(deviceService.pauseDatacenter("HK-1", "idem-dc", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.pauseDatacenter("HK-1", "idem-dc", request).getCode()).isZero();

        verify(deviceService).pauseDatacenter("HK-1", "idem-dc", request);
    }
}
