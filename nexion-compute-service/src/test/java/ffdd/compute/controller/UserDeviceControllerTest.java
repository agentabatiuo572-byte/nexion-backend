package ffdd.compute.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.compute.domain.ComputeTask;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.DeviceLifecycleResponse;
import ffdd.compute.dto.DeviceStatusResponse;
import ffdd.compute.dto.DeviceQueryRequest;
import ffdd.compute.service.ComputeService;
import ffdd.compute.service.DeviceLifecycleService;
import ffdd.compute.service.DeviceStatusService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class UserDeviceControllerTest {
    private final ComputeService computeService = mock(ComputeService.class);
    private final DeviceLifecycleService lifecycleService = mock(DeviceLifecycleService.class);
    private final DeviceStatusService deviceStatusService = mock(DeviceStatusService.class);
    private final UserDeviceController controller =
            new UserDeviceController(computeService, lifecycleService, deviceStatusService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userPageForcesAuthenticatedUserIdOverRequestUserId() {
        asUser(10082L);
        when(computeService.pageDevices(any(DeviceQueryRequest.class)))
                .thenReturn(new PageResult<>(0, 1, 10, List.of()));

        DeviceQueryRequest request = new DeviceQueryRequest();
        request.setUserId(10001L);
        controller.page(request);

        ArgumentCaptor<DeviceQueryRequest> captor = ArgumentCaptor.forClass(DeviceQueryRequest.class);
        org.mockito.Mockito.verify(computeService).pageDevices(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void adminPageKeepsRequestedUserFilter() {
        asAdmin();
        when(computeService.pageDevices(any(DeviceQueryRequest.class)))
                .thenReturn(new PageResult<>(0, 1, 10, List.of()));

        DeviceQueryRequest request = new DeviceQueryRequest();
        request.setUserId(10001L);
        controller.page(request);

        ArgumentCaptor<DeviceQueryRequest> captor = ArgumentCaptor.forClass(DeviceQueryRequest.class);
        org.mockito.Mockito.verify(computeService).pageDevices(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10001L);
    }

    @Test
    void userDetailRejectsAnotherUsersDevice() {
        asUser(10082L);
        UserDevice device = device(7L, 10001L);
        when(computeService.getDevice(7L)).thenReturn(device);

        assertThatThrownBy(() -> controller.detail(7L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Device does not belong to authenticated user");
    }

    @Test
    void userDetailAllowsOwnedDevice() {
        asUser(10082L);
        UserDevice device = device(7L, 10082L);
        when(computeService.getDevice(7L)).thenReturn(device);
        when(lifecycleService.evaluate(device)).thenReturn(lifecycle());
        when(deviceStatusService.getStatus(7L)).thenReturn(runtime());

        assertThat(controller.detail(7L).getData().getUserId()).isEqualTo(10082L);
    }

    @Test
    void detailIncludesRuntimeAndCurrentTask() {
        asAdmin();
        UserDevice device = device(7L, 10082L);
        ComputeTask task = new ComputeTask();
        task.setTaskNo("TASK-100");
        task.setTaskType("IMAGE_INFERENCE");
        task.setClientName("mobile-worker");
        task.setStatus("RUNNING");
        when(computeService.getDevice(7L)).thenReturn(device);
        when(computeService.currentTaskForDevice(7L)).thenReturn(task);
        when(lifecycleService.evaluate(device)).thenReturn(lifecycle());
        when(deviceStatusService.getStatus(7L)).thenReturn(runtime());

        var response = controller.detail(7L).getData();

        assertThat(response.getRuntimeBatteryLevel()).isEqualTo(88);
        assertThat(response.getRuntimeNetworkReachable()).isTrue();
        assertThat(response.getCurrentTaskNo()).isEqualTo("TASK-100");
        assertThat(response.getCurrentTaskClientName()).isEqualTo("mobile-worker");
    }

    private void asUser(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(userId),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private void asAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "superadmin",
                null,
                List.of(new SimpleGrantedAuthority("PERM_COMPUTE_WRITE"))));
    }

    private UserDevice device(Long id, Long userId) {
        UserDevice device = new UserDevice();
        device.setId(id);
        device.setUserId(userId);
        device.setStatus("ONLINE");
        device.setDailyUsdt(new BigDecimal("1.20"));
        device.setDailyNex(new BigDecimal("3.40"));
        device.setIsDeleted(0);
        return device;
    }

    private DeviceLifecycleResponse lifecycle() {
        DeviceLifecycleResponse response = new DeviceLifecycleResponse();
        response.setMonthsOwned(0);
        response.setCurrentEfficiency(BigDecimal.ONE);
        response.setEffectiveDailyUsdt(new BigDecimal("1.20"));
        response.setEffectiveDailyNex(new BigDecimal("3.40"));
        return response;
    }

    private DeviceStatusResponse runtime() {
        DeviceStatusResponse response = new DeviceStatusResponse();
        response.setUserDeviceId(7L);
        response.setStatus("BUSY");
        response.setBatteryLevel(88);
        response.setIsCharging(true);
        response.setNetworkReachable(true);
        response.setActiveTaskNo("TASK-100");
        response.setClientName("mobile-worker");
        response.setCacheStatus("HIT");
        return response;
    }
}
