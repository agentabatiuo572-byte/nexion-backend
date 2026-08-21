package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class QuestSandboxSchemaInitializerTest {
    @Mock
    private AppGrowthWheelSandboxMapper mapper;

    @InjectMocks
    private QuestSandboxSchemaInitializer initializer;

    @Test
    void startupFailsClosedWhenRequiredQuestSchemaIsIncomplete() {
        when(mapper.questSandboxSchemaTableCount()).thenReturn(0);

        assertThatThrownBy(() -> initializer.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEST_SANDBOX_SCHEMA_MIGRATION_REQUIRED");
    }

    @Test
    void startupContinuesOnlyWhenCompleteQuestSchemaIsPresent() {
        when(mapper.questSandboxSchemaTableCount()).thenReturn(1);

        assertThatCode(() -> initializer.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }
}
