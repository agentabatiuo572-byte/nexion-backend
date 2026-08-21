package ffdd.opsconsole.team.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Volatile, run-fenced Proof fixture store for local acceptance only.
 * It is deliberately not a production persistence path and is never read for
 * PRODUCTION referral projections.
 */
@Service
public class AppProofSandboxFixtureService {
    private final Map<String, Fixture> fixtures = new ConcurrentHashMap<>();

    public void put(String runId, Long userId, Fixture fixture) {
        fixtures.put(key(runId, userId), fixture);
    }

    public Fixture get(String runId, Long userId) {
        return fixtures.get(key(runId, userId));
    }

    private String key(String runId, Long userId) {
        return runId + ":" + userId;
    }

    public record Fixture(BigDecimal earningsTotalUsdt, Integer currentStreak, Integer longestStreak,
                          LocalDate lastCheckInDate, Long higherCount, Long populationCount) { }
}
