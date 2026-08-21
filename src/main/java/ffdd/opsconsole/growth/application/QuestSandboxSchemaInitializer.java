package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Fails closed before local-sandbox traffic if the isolated quest table is missing. */
@Component
@Conditional(WheelSandboxProfileCondition.class)
@RequiredArgsConstructor
public class QuestSandboxSchemaInitializer implements ApplicationRunner {
    private final AppGrowthWheelSandboxMapper mapper;

    @Override
    public void run(ApplicationArguments args) {
        if (mapper.questSandboxSchemaTableCount() != 1) {
            throw new IllegalStateException("QUEST_SANDBOX_SCHEMA_MIGRATION_REQUIRED: apply "
                    + "scripts/migrations/20260816_growth_quest_sandbox.sql before startup");
        }
    }
}
