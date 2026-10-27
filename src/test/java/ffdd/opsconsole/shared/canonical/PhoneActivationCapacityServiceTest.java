package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PhoneActivationCapacityServiceTest {
    private final CanonicalStateMapper mapper = mock(CanonicalStateMapper.class);
    private final PhoneActivationCapacityService service = new PhoneActivationCapacityService(mapper);

    @Test void occupiedBoundPhoneReusesItsSlotEvenAtCapacity() {
        when(mapper.lockUser(1L)).thenReturn(new CanonicalStateMapper.UserLock(1L, false));
        when(mapper.lockOccupiedOnboardingPhone(1L, 11L)).thenReturn(11L);
        service.requireCapacity(1L, 11L);
        verify(mapper, never()).lockActiveDeviceCount(anyLong(), anyBoolean());
        verify(mapper, never()).lockReservedDeviceOrderCount(anyLong(), anyBoolean());
    }

    @Test void deactivatedOldPhoneCannotDisplaceReservedOrderOrTrial() {
        when(mapper.lockUser(1L)).thenReturn(new CanonicalStateMapper.UserLock(1L, false));
        when(mapper.lockOccupiedOnboardingPhone(1L, 11L)).thenReturn(null);
        when(mapper.lockActiveDeviceCount(1L, false)).thenReturn(2);
        when(mapper.lockReservedDeviceOrderCount(1L, false)).thenReturn(1);
        when(mapper.lockDeviceSlotCap()).thenReturn(3);
        assertThatThrownBy(() -> service.requireCapacity(1L, 11L))
                .isInstanceOf(BizException.class).hasMessage("CAPACITY_REPLACEMENT_REQUIRED");
    }

    @Test void firstBindingUsesCanonicalCapacityAndAccountEnvironment() {
        when(mapper.lockUser(2L)).thenReturn(new CanonicalStateMapper.UserLock(2L, true));
        when(mapper.lockActiveDeviceCount(2L, true)).thenReturn(1);
        when(mapper.lockReservedDeviceOrderCount(2L, true)).thenReturn(1);
        when(mapper.lockDeviceSlotCap()).thenReturn(3);
        service.requireCapacity(2L, null);
        verify(mapper, never()).lockOccupiedOnboardingPhone(anyLong(), anyLong());
        verify(mapper, never()).activeDeviceCount(anyLong());
        when(mapper.lockReservedDeviceOrderCount(2L, true)).thenReturn(2);
        assertThatThrownBy(() -> service.requireCapacity(2L, null)).isInstanceOf(BizException.class);
    }

    @Test void inactiveAccountCannotAcquireCapacity() {
        assertThatThrownBy(() -> service.requireCapacity(1L, null)).isInstanceOf(BizException.class);
        verify(mapper, never()).lockDeviceSlotCap();
    }

    @Test void trialReservationCannotBeDisplacedByANewPhone() {
        when(mapper.lockUser(1L)).thenReturn(new CanonicalStateMapper.UserLock(1L, false));
        when(mapper.lockActiveDeviceCount(1L, false)).thenReturn(5);
        when(mapper.lockReservedTrialSlotCount(1L)).thenReturn(1);
        when(mapper.lockDeviceSlotCap()).thenReturn(6);
        assertThatThrownBy(() -> service.requireCapacity(1L, null))
                .isInstanceOf(BizException.class).hasMessage("CAPACITY_REPLACEMENT_REQUIRED");
    }
}
