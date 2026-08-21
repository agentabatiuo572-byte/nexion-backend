package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Refuses isolated-profile startup until all physically isolated wheel tables exist. */
@Component
@Conditional(WheelSandboxProfileCondition.class)
@RequiredArgsConstructor
public class WheelSandboxSchemaInitializer implements ApplicationRunner {
    private final AppGrowthWheelSandboxMapper mapper;

    @Override
    public void run(ApplicationArguments args) {
        if (mapper.sandboxSchemaTableCount() != 7) {
            throw new IllegalStateException("WHEEL_SANDBOX_SCHEMA_MIGRATION_REQUIRED: apply "
                    + "scripts/migrations/20260815_h4_wheel_local_sandbox.sql before startup");
        }
    }
}
