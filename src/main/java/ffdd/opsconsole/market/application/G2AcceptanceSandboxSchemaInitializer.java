package ffdd.opsconsole.market.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Refuses acceptance startup until the four isolated G2 fixture tables were migrated explicitly. */
@Component
@Profile({"dev", "test"})
@RequiredArgsConstructor
public class G2AcceptanceSandboxSchemaInitializer implements ApplicationRunner {
    private final G2AcceptanceSandboxProfileGuard guard;
    private final G2AcceptanceSandboxRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        if (guard.available()) repository.verifySchema();
    }
}
