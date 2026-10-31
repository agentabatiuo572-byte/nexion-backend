package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.mapper.TestCloudRequestMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.file.*;
import java.time.Clock;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** TEST capacity is reserved before activation; existing executors never depend on this snapshot. */
@Component
@RequiredArgsConstructor
public class TestCloudActivationAdmission {
    private final TestCloudRequestMapper mapper;
    private final Environment environment;
    private final Clock clock;
    private static final ObjectMapper JSON=new ObjectMapper();
    private boolean enabled() {
        return "true".equals(environment.getProperty(TestCloudExecutorService.PREFIX+"enabled"))
                && "TEST".equals(environment.getProperty(TestCloudExecutorService.PREFIX+"deployment-scope"))
                && Set.of(environment.getActiveProfiles()).equals(Set.of("dev"));
    }
    public void lock() {
        if(!enabled()) return;
        if(!TransactionSynchronizationManager.isActualTransactionActive() || mapper.lockResourceGuard()!=1)
            throw new BizException(503,"TEST_CLOUD_RESOURCE_ADMISSION_UNAVAILABLE");
    }
    public void requireCapacity() {
        requireCapacity(Path.of("/run/uvel-task-control/capacity.json"));
    }
    void requireCapacity(Path snapshot) {
        if(!enabled()) return;
        try {
            byte[] bytes=Files.readAllBytes(snapshot);
            if(bytes.length>4096) throw new IllegalArgumentException();
            var value=JSON.readTree(bytes); long now=clock.instant().getEpochSecond();
            long observed=value.path("observedAtEpochSeconds").asLong(-1),expires=value.path("expiresAtEpochSeconds").asLong(-1);
            int maximum=value.path("maxActiveExecutors").asInt(-1);
            if(value.path("version").asInt()!=1 || !value.path("allowNewActivations").isBoolean()
                    || !value.path("allowNewActivations").booleanValue() || observed>now || observed<now-30
                    || expires<=now || expires>observed+30 || maximum<1 || mapper.activeResourceExecutors()>=maximum)
                throw new IllegalArgumentException();
        } catch(java.io.IOException | IllegalArgumentException unavailable) {
            throw new BizException(503,"TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        }
    }
}
