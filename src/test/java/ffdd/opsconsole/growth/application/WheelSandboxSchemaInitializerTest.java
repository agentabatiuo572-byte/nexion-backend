package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper;
import org.junit.jupiter.api.Test;

class WheelSandboxSchemaInitializerTest {
    @Test
    void refusesIncompleteSchemaEvenWhenSomeTablesExist() {
        AppGrowthWheelSandboxMapper mapper = mock(AppGrowthWheelSandboxMapper.class);
        when(mapper.sandboxSchemaTableCount()).thenReturn(6);

        assertThatThrownBy(() -> new WheelSandboxSchemaInitializer(mapper).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WHEEL_SANDBOX_SCHEMA_MIGRATION_REQUIRED");
    }
}
