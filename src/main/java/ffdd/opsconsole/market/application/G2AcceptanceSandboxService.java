package ffdd.opsconsole.market.application;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile({"dev", "test"})
@RequiredArgsConstructor
public class G2AcceptanceSandboxService {
    private final G2AcceptanceSandboxProfileGuard guard;
    private final G2AcceptanceSandboxRepository repository;

    public Map<String, Object> overview() {
        guard.requireAvailable();
        return repository.latest();
    }

    public Map<String, Object> generate() {
        guard.requireAvailable();
        return repository.generate();
    }

    public Map<String, Object> process(String batchNo, String idempotencyKey) {
        guard.requireAvailable();
        return repository.process(batchNo, idempotencyKey);
    }

    public void cleanup(String batchNo) {
        guard.requireAvailable();
        repository.cleanup(batchNo);
    }
}
