package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.onboarding.mapper.PhoneInstallationMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class PhoneInstallationServiceTest {
    private final PhoneInstallationMapper mapper = mock(PhoneInstallationMapper.class);
    private final PhoneInstallationService service = new PhoneInstallationService(mapper);

    @BeforeEach void activeUser() {
        when(mapper.lockUser(42L)).thenReturn(42L);
    }

    @Test void onboardingRequiresOnlyUserAuthenticationAndAValidInstallationId() {
        service.require(auth("42", "USER"), "phone-a");
        verifyNoInteractions(mapper);
        assertThatThrownBy(() -> service.require(null, "phone-a")).hasMessage("USER_AUTH_REQUIRED");
        assertThatThrownBy(() -> service.require(auth("42", "ADMIN"), "phone-a")).hasMessage("USER_AUTH_REQUIRED");
        assertThatThrownBy(() -> service.require(auth("0", "USER"), "phone-a")).hasMessage("USER_AUTH_REQUIRED");
        assertThatThrownBy(() -> service.require(auth("not-a-user", "USER"), "phone-a")).hasMessage("USER_AUTH_REQUIRED");
        var unauthenticated = new UsernamePasswordAuthenticationToken("42", "");
        unauthenticated.setDetails(Map.of("subjectType", "USER"));
        assertThatThrownBy(() -> service.require(unauthenticated, "phone-a")).hasMessage("USER_AUTH_REQUIRED");
        verifyNoInteractions(mapper);
    }

    @Test void malformedInstallationIdsAreRejectedBeforeAnyRead() {
        for (String invalid : new String[] {null, "", "with space", "bad/id", "x".repeat(129)}) {
            assertThatThrownBy(() -> service.require(auth("42", "USER"), invalid))
                    .isInstanceOf(BizException.class).hasMessage("ONBOARDING_DEVICE_INVALID");
            assertThatThrownBy(() -> service.requireRuntime(auth("42", "USER"), invalid))
                    .hasMessage("ONBOARDING_DEVICE_INVALID");
        }
        verifyNoInteractions(mapper);
    }

    @Test void phoneClaimRequiresTheSuppliedInstallationToMatchBothBindings() {
        when(mapper.deviceBinding(42, 1)).thenReturn(new PhoneInstallationMapper.DeviceBinding("MOBILE", "phone-a"));
        when(mapper.executionInstallation(42)).thenReturn("phone-a");
        service.requireForDevice(auth("42", "USER"), 1L, "phone-a");
        var order = inOrder(mapper);
        order.verify(mapper).lockUser(42L);
        order.verify(mapper).deviceBinding(42L, 1L);
        order.verify(mapper).executionInstallation(42L);

        assertThatThrownBy(() -> service.requireForDevice(auth("42", "USER"), 1L, null))
                .hasMessage("ONBOARDING_DEVICE_INVALID");
        assertThatThrownBy(() -> service.requireForDevice(auth("42", "USER"), 1L, "phone-b"))
                .hasMessage("TASK_ASSIGNMENT_PHONE_BINDING_INVALID");
        when(mapper.executionInstallation(42)).thenReturn("phone-b");
        assertThatThrownBy(() -> service.requireForDevice(auth("42", "USER"), 1L, "phone-a"))
                .hasMessage("TASK_ASSIGNMENT_PHONE_BINDING_INVALID");
    }

    @Test void unboundPhoneOrExecutionCannotClaimOrReportRuntime() {
        when(mapper.deviceBinding(42, 1)).thenReturn(new PhoneInstallationMapper.DeviceBinding("PHONE", null));
        assertThatThrownBy(() -> service.requireForDevice(auth("42", "USER"), 1L, "phone-a"))
                .hasMessage("TASK_ASSIGNMENT_PHONE_BINDING_INVALID");
        assertThatThrownBy(() -> service.requireRuntime(auth("42", "USER"), "phone-a"))
                .hasMessage("TASK_ASSIGNMENT_PHONE_BINDING_INVALID");
        when(mapper.executionInstallation(42)).thenReturn("phone-a");
        service.requireRuntime(auth("42", "USER"), "phone-a");
    }

    @Test void purchasedDevicesDoNotRequireAnInstallationHeader() {
        when(mapper.deviceBinding(42, 2)).thenReturn(new PhoneInstallationMapper.DeviceBinding("BOX", null));
        when(mapper.taskDevice(42, "purchased-task")).thenReturn(2L);
        service.requireForDevice(auth("42", "USER"), 2L, null);
        service.requireForTask(auth("42", "USER"), "purchased-task", null);
        verify(mapper, never()).executionInstallation(anyLong());
    }

    @Test void taskCompletionUsesTheOwnedDeviceAndCurrentInstallation() {
        when(mapper.taskDevice(42, "phone-task")).thenReturn(1L);
        when(mapper.deviceBinding(42, 1)).thenReturn(new PhoneInstallationMapper.DeviceBinding("PHONE", "phone-a"));
        when(mapper.executionInstallation(42)).thenReturn("phone-a");
        service.requireForTask(auth("42", "USER"), "phone-task", "phone-a");
        var order = inOrder(mapper);
        order.verify(mapper).lockUser(42L);
        order.verify(mapper).taskDevice(42L, "phone-task");
        order.verify(mapper).deviceBinding(42L, 1L);
        order.verify(mapper).executionInstallation(42L);
        assertThatThrownBy(() -> service.requireForTask(auth("42", "USER"), "phone-task", "phone-b"))
                .hasMessage("TASK_ASSIGNMENT_PHONE_BINDING_INVALID");
        assertThatThrownBy(() -> service.requireForTask(auth("42", "USER"), "phone-task", null))
                .hasMessage("ONBOARDING_DEVICE_INVALID");
    }

    @Test void anotherUserCannotReuseTheOwnedDeviceOrTask() {
        when(mapper.lockUser(43L)).thenReturn(43L);
        when(mapper.taskDevice(43, "phone-task")).thenReturn(null);
        when(mapper.deviceBinding(42, 1)).thenReturn(new PhoneInstallationMapper.DeviceBinding("MOBILE", "phone-a"));
        when(mapper.taskDevice(42, "phone-task")).thenReturn(1L);
        assertThatThrownBy(() -> service.requireForDevice(auth("43", "USER"), 1L, "phone-a"))
                .hasMessage("TASK_ASSIGNMENT_DEVICE_NOT_FOUND");
        assertThatThrownBy(() -> service.requireForTask(auth("43", "USER"), "phone-task", "phone-a"))
                .hasMessage("TASK_ASSIGNMENT_NOT_FOUND");
        verify(mapper).deviceBinding(43L, 1L);
        verify(mapper).taskDevice(43L, "phone-task");
        verify(mapper, never()).executionInstallation(anyLong());
    }

    @Test void inactiveUserCannotReachBindingReads() {
        when(mapper.lockUser(42L)).thenReturn(null);
        assertThatThrownBy(() -> service.requireForDevice(auth("42", "USER"), 1L, "phone-a"))
                .hasMessage("USER_AUTH_REQUIRED");
        verify(mapper, never()).deviceBinding(anyLong(), anyLong());
    }

    private UsernamePasswordAuthenticationToken auth(String id, String subject) {
        var auth = new UsernamePasswordAuthenticationToken(id, "", List.of());
        auth.setDetails(Map.of("subjectType", subject));
        return auth;
    }
}
