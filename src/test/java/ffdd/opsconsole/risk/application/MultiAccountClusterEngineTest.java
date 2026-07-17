package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ffdd.opsconsole.risk.domain.RiskOpsRepository.MultiAccountSignalFact;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MultiAccountClusterEngineTest {
    private final MultiAccountClusterEngine engine = new MultiAccountClusterEngine();

    @Test
    void createsDeterministicConnectedComponentsAndRealRelationshipEdges() {
        LocalDateTime joined = LocalDateTime.of(2026, 7, 16, 10, 0);
        List<MultiAccountSignalFact> facts = List.of(
                fact(1, joined, "device", "dev-a", "设备 ••••A"),
                fact(2, joined.plusMinutes(5), "device", "dev-a", "设备 ••••A"),
                fact(1, joined, "payment", "card-a", "银行卡 ••••1234"),
                fact(2, joined.plusMinutes(5), "payment", "card-a", "银行卡 ••••1234"),
                fact(2, joined.plusMinutes(5), "ip", "198.51.100.9", "198.51.100.*"),
                fact(3, joined.plusMinutes(9), "ip", "198.51.100.9", "198.51.100.*"));

        var projections = engine.project(facts, Set.of(), new MultiAccountClusterEngine.Config(0.5, 0.4, 0.1, 0.7, 1, 1, 1));

        assertThat(projections).hasSize(1);
        var cluster = projections.get(0);
        assertThat(cluster.clusterId()).isEqualTo("K1-00000001");
        assertThat(cluster.accountCount()).isEqualTo(3);
        assertThat(cluster.edges()).extracting(edge -> edge.layer())
                .containsExactlyInAnyOrder("device", "payment", "ip");
        assertThat(cluster.strength()).isBetween(0.57, 0.59);
    }

    @Test
    void excludesWhitelistedIpOnlyGroupsAndNeverCreatesSingleAccountClusters() {
        LocalDateTime joined = LocalDateTime.of(2026, 7, 16, 10, 0);
        List<MultiAccountSignalFact> facts = List.of(
                fact(7, joined, "ip", "203.0.113.7", "203.0.113.*"),
                fact(8, joined.plusMinutes(1), "ip", "203.0.113.7", "203.0.113.*"),
                fact(9, joined, "device", "single", "设备 ••••single"));

        assertThat(engine.project(facts, Set.of("203.0.113.0/24"), new MultiAccountClusterEngine.Config(0.5, 0.4, 0.1, 0.7, 1, 1, 1)))
                .isEmpty();
    }

    @Test
    void emitsLinearRealEdgesForLargeSharedGroups() {
        LocalDateTime joined = LocalDateTime.of(2026, 7, 16, 10, 0);
        List<MultiAccountSignalFact> facts = LongStream.rangeClosed(1, 200)
                .mapToObj(id -> fact(id, joined.plusSeconds(id), "device", "large-shared-device", "设备 ••••large"))
                .toList();

        var cluster = engine.project(facts, Set.of(),
                new MultiAccountClusterEngine.Config(1, 0, 0, 0.7, 3, 1, 2)).get(0);

        assertThat(cluster.affectedUserIds()).hasSize(200);
        assertThat(cluster.edges()).hasSize(199);
        assertThat(cluster.edges()).allSatisfy(edge -> assertThat(edge.layer()).isEqualTo("device"));
    }

    @Test
    void projectsTenThousandAccountsWithoutExpandingAllAccountPairs() {
        LocalDateTime joined = LocalDateTime.of(2026, 7, 16, 10, 0);
        List<MultiAccountSignalFact> facts = LongStream.rangeClosed(1, 10_000)
                .mapToObj(id -> fact(id, joined.plusSeconds(id), "device", "huge-shared-device", "设备 ••••huge"))
                .toList();

        var cluster = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> engine.project(
                facts, Set.of(), new MultiAccountClusterEngine.Config(0.5, 0.4, 0.1, 0.7, 3, 1, 2)).get(0));

        assertThat(cluster.accountCount()).isEqualTo(10_000);
        assertThat(cluster.nodes()).hasSize(10_000);
        assertThat(cluster.edges()).hasSize(9_999);
        assertThat(cluster.strength()).isEqualTo(0.5);
    }

    @Test
    void producesIdenticalProjectionWhenDatabaseFactOrderChanges() {
        LocalDateTime joined = LocalDateTime.of(2026, 7, 16, 10, 0);
        List<MultiAccountSignalFact> facts = new ArrayList<>(List.of(
                fact(1, joined, "device", "device-z", "设备 ••••Z"),
                fact(2, joined.plusMinutes(1), "device", "device-z", "设备 ••••Z"),
                fact(2, joined.plusMinutes(1), "device", "device-a", "设备 ••••A"),
                fact(3, joined.plusMinutes(2), "device", "device-a", "设备 ••••A"),
                fact(1, joined, "payment", "card-a", "银行卡 ••••1111"),
                fact(2, joined.plusMinutes(1), "payment", "card-a", "银行卡 ••••1111")));
        MultiAccountClusterEngine.Config config = new MultiAccountClusterEngine.Config(0.5, 0.4, 0.1, 0.7, 1, 1, 1);

        var forward = engine.project(facts, Set.of(), config);
        Collections.reverse(facts);
        var reversed = engine.project(facts, Set.of(), config);

        assertThat(reversed).isEqualTo(forward);
    }

    private MultiAccountSignalFact fact(long userId, LocalDateTime joinedAt, String layer, String raw, String masked) {
        return new MultiAccountSignalFact(
                userId, "U" + String.format("%08d", userId), joinedAt, null,
                false, BigDecimal.ZERO, "active", layer, raw, masked);
    }
}
