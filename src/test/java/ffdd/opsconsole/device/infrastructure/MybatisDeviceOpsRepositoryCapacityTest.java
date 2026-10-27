package ffdd.opsconsole.device.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.device.mapper.DeviceOpsMapper;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MybatisDeviceOpsRepositoryCapacityTest {
    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void e5UsesCurrentCanonicalOccupancyWithoutChangingThePhysicalReadModel(boolean sandbox) {
        var mapper = mock(DeviceOpsMapper.class);
        var canonical = mock(CanonicalStateMapper.class);
        when(mapper.lockUserSandboxForE5Activation(7L)).thenReturn(sandbox);
        when(canonical.lockOccupiedDeviceSlotCount(7L, sandbox)).thenReturn(6L);
        var repository = new MybatisDeviceOpsRepository(mapper, mock(OpsReadTimeSeedPolicy.class), canonical);
        assertThat(repository.countOccupiedSlotsForE5Activation(7L)).isEqualTo(6L);
        verify(mapper, never()).countActiveDevicesByUser(7L);
    }
}
