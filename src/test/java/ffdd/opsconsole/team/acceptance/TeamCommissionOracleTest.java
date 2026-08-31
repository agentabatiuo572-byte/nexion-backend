package ffdd.opsconsole.team.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TeamCommissionOracleTest {

    @Test
    void fixedTopologyContainsSeventeenRolesAndExactlySevenBuyerUplines() {
        TeamTestRunManifest manifest = TeamTestRunManifest.empty(
                "TEAM-20260829-0001", "nexion_team_acceptance_20260829_0001", "{}");

        assertThat(TeamTestRunManifest.ROLE_ORDER).hasSize(17).doesNotHaveDuplicates();
        assertThat(TeamTestRunManifest.sponsorRoles()).hasSize(17);
        assertThat(manifest.buyerUplineRoles())
                .containsExactly("A14", "A13", "A12", "A11", "A1", "A", "R");
        assertThat(TeamTestRunManifest.sponsorRoles().get("O")).isNull();
        assertThat(TeamTestRunManifest.sponsorRoles().get("Q")).isEqualTo("R");
    }

    @Test
    void canonicalNetworkRatesProduceExactL1ToL7UsdtAndNex() {
        List<BigDecimal> rates = decimals("10", "5", "3", "2", "1", "0.5", "0.5");
        List<BigDecimal> nexRates = decimals("50", "20", "10", "5", "2.5", "1", "1");
        List<TeamCommissionOracle.NetworkLevel> levels = new ArrayList<>();
        for (int layer = 1; layer <= 7; layer++) {
            levels.add(new TeamCommissionOracle.NetworkLevel(
                    1000L + layer, layer, 2, false, rates.get(layer - 1),
                    nexRates.get(layer - 1), BigDecimal.ONE));
        }

        List<TeamCommissionOracle.NetworkPayout> result = TeamCommissionOracle.network(
                new BigDecimal("1000"), levels, BigDecimal.ONE, new BigDecimal("25"), 4, 2);

        assertThat(result).extracting(TeamCommissionOracle.NetworkPayout::usdt)
                .containsExactlyElementsOf(decimals(
                        "100.000000", "50.000000", "30.000000", "20.000000",
                        "10.000000", "5.000000", "5.000000"));
        assertThat(result).extracting(TeamCommissionOracle.NetworkPayout::nex)
                .containsExactlyElementsOf(decimals(
                        "5000.000000", "1000.000000", "300.000000", "100.000000",
                        "25.000000", "5.000000", "5.000000"));
    }

    @Test
    void binaryCapAndLeadershipRemainderAreExactToSixDecimals() {
        TeamCommissionOracle.BinaryPayout binary = TeamCommissionOracle.binary(
                new BigDecimal("90000"), new BigDecimal("100000"), BigDecimal.ZERO,
                new BigDecimal("1000"), new BigDecimal("0.13"), new BigDecimal("5000"),
                1, BigDecimal.ZERO);
        assertThat(binary.consumedMatched()).isEqualByComparingTo("38461.538461");
        assertThat(binary.amountUsdt()).isEqualByComparingTo("4999.999999");
        assertThat(binary.capUsdt()).isEqualByComparingTo("5000.000000");

        LinkedHashMap<Long, Integer> votes = new LinkedHashMap<>();
        votes.put(1L, 1);
        votes.put(2L, 1);
        votes.put(3L, 1);
        var leadership = TeamCommissionOracle.leadership(new BigDecimal("100"), votes);
        assertThat(leadership).containsEntry(1L, new BigDecimal("33.333333"));
        assertThat(leadership).containsEntry(2L, new BigDecimal("33.333333"));
        assertThat(leadership).containsEntry(3L, new BigDecimal("33.333334"));
        assertThat(leadership.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100.000000");
    }

    @Test
    void rankUsesQualifiedDirectsAndMovesAtMostOneLevelPerTrigger() {
        List<TeamCommissionOracle.RankRule> rules = List.of(
                new TeamCommissionOracle.RankRule(1, new BigDecimal("500"), 3,
                        BigDecimal.ZERO, 0, 0),
                new TeamCommissionOracle.RankRule(2, BigDecimal.ZERO, 0,
                        new BigDecimal("5000"), 0, 0),
                new TeamCommissionOracle.RankRule(3, BigDecimal.ZERO, 0,
                        new BigDecimal("20000"), 2, 2));
        TeamCommissionOracle.RankSnapshot qualified = new TeamCommissionOracle.RankSnapshot(
                new BigDecimal("500"), 3, new BigDecimal("25000"), List.of(2, 2, 1, 0));
        TeamCommissionOracle.RankSnapshot memberCountOnly = new TeamCommissionOracle.RankSnapshot(
                new BigDecimal("500"), 0, new BigDecimal("25000"), List.of(2, 2, 1, 0));

        assertThat(TeamCommissionOracle.nextRank(0, qualified, rules, true)).isEqualTo(1);
        assertThat(TeamCommissionOracle.nextRank(1, qualified, rules, true)).isEqualTo(2);
        assertThat(TeamCommissionOracle.nextRank(2, qualified, rules, true)).isEqualTo(3);
        assertThat(TeamCommissionOracle.nextRank(0, memberCountOnly, rules, true)).isZero();
    }

    @Test
    void oneThousandRandomTreesConserveMoneyAndNeverDuplicateRecipients() {
        Random random = new Random(0x4E4558494F4EL);
        for (int caseNo = 0; caseNo < 1_000; caseNo++) {
            BigDecimal subtotal = BigDecimal.valueOf(1 + random.nextInt(2_000_000), 3);
            BigDecimal maximumExitPercent = BigDecimal.valueOf(1 + random.nextInt(25));
            BigDecimal promo = BigDecimal.valueOf(1 + random.nextInt(3));
            List<TeamCommissionOracle.NetworkLevel> levels = new ArrayList<>();
            for (int layer = 1; layer <= 7; layer++) {
                levels.add(new TeamCommissionOracle.NetworkLevel(
                        caseNo * 100L + layer,
                        layer,
                        random.nextInt(5),
                        random.nextInt(13) == 0,
                        BigDecimal.valueOf(1 + random.nextInt(1000), 2),
                        BigDecimal.valueOf(random.nextInt(5000), 2),
                        BigDecimal.valueOf(100 + random.nextInt(401), 2)));
            }
            List<TeamCommissionOracle.NetworkPayout> network = TeamCommissionOracle.network(
                    subtotal, levels, promo, maximumExitPercent, 4, 2);
            BigDecimal paid = network.stream().map(TeamCommissionOracle.NetworkPayout::usdt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cap = subtotal.multiply(maximumExitPercent)
                    .divide(new BigDecimal("100"), 6, RoundingMode.DOWN);

            assertThat(network).hasSizeLessThanOrEqualTo(7);
            assertThat(new HashSet<>(network.stream()
                    .map(TeamCommissionOracle.NetworkPayout::recipientUserId).toList()))
                    .hasSameSizeAs(network);
            assertThat(paid).isNotNegative().isLessThanOrEqualTo(cap);
            assertThat(network).allSatisfy(payout -> {
                assertThat(payout.usdt()).isPositive();
                assertThat(payout.nex()).isNotNegative();
                assertThat(payout.usdt().scale()).isEqualTo(6);
                assertThat(payout.nex().scale()).isEqualTo(6);
            });

            BigDecimal left = BigDecimal.valueOf(random.nextInt(10_000_000), 2);
            BigDecimal right = BigDecimal.valueOf(random.nextInt(10_000_000), 2);
            BigDecimal consumed = left.min(right)
                    .multiply(BigDecimal.valueOf(random.nextInt(101)))
                    .divide(new BigDecimal("100"), 6, RoundingMode.DOWN);
            TeamCommissionOracle.BinaryPayout binary = TeamCommissionOracle.binary(
                    left, right, consumed, new BigDecimal("1000"), new BigDecimal("0.13"),
                    new BigDecimal("5000"), 1 + random.nextInt(31), BigDecimal.ZERO);
            assertThat(binary.consumedMatched()).isNotNegative().isLessThanOrEqualTo(left.min(right));
            assertThat(binary.amountUsdt()).isNotNegative().isLessThanOrEqualTo(binary.capUsdt());

            LinkedHashMap<Long, Integer> votes = new LinkedHashMap<>();
            int voters = 1 + random.nextInt(20);
            for (int voter = 1; voter <= voters; voter++) {
                votes.put((long) voter, 1 + random.nextInt(50));
            }
            BigDecimal pool = BigDecimal.valueOf(1 + random.nextInt(50_000_000), 4);
            var shares = TeamCommissionOracle.leadership(pool, votes);
            assertThat(shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(pool.setScale(6, RoundingMode.DOWN));
            assertThat(shares.values()).allSatisfy(share -> assertThat(share).isPositive());
        }
    }

    private static List<BigDecimal> decimals(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }
}
