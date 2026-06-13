package ffdd.team.service;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.team.dto.LeadershipPoolHistoryItem;
import ffdd.team.dto.LeadershipPoolRankWeight;
import ffdd.team.dto.LeadershipPoolSnapshot;
import ffdd.team.dto.TeamCommissionSettlementResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TeamAdvancedCommissionServiceTest {
    private final TestableTeamAdvancedCommissionService service = new TestableTeamAdvancedCommissionService();

    @Test
    void settlesMonthlyPeerCommissionFromSameRankVolume() {
        service.peerCandidates = List.of(new TeamAdvancedCommissionService.PeerCandidate(
                10001L,
                "V3",
                new BigDecimal("1200.000000"),
                new BigDecimal("0.050000"),
                30));

        TeamCommissionSettlementResult result = service.settlePeer(LocalDate.of(2026, 5, 24), 50);

        assertThat(result.getCommissionType()).isEqualTo("PEER");
        assertThat(result.getPeriod()).isEqualTo("2026-05");
        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getCreated()).isEqualTo(1);
        assertThat(result.getSkipped()).isZero();
        assertThat(result.getCommissionIds()).containsExactly(9101L);
        CreatedCommission commission = service.createdCommissions.get(0);
        assertThat(commission.commissionType()).isEqualTo("PEER");
        assertThat(commission.userId()).isEqualTo(10001L);
        assertThat(commission.orderNo()).isEqualTo("PEER-2026-05");
        assertThat(commission.amountUsdt()).isEqualByComparingTo("60.000000");
        assertThat(commission.amountNex()).isEqualByComparingTo("0.000000");
    }

    @Test
    void skipsPeerCommissionWhenPeriodAlreadySettled() {
        service.peerCandidates = List.of(new TeamAdvancedCommissionService.PeerCandidate(
                10001L,
                "V3",
                new BigDecimal("1200.000000"),
                new BigDecimal("0.050000"),
                30));
        service.existingKeys.add("PEER:10001:PEER-2026-05");

        TeamCommissionSettlementResult result = service.settlePeer(LocalDate.of(2026, 5, 24), 50);

        assertThat(result.getCreated()).isZero();
        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(service.createdCommissions).isEmpty();
    }

    @Test
    void settlesCultivationCommissionFromRankPromotionLog() {
        service.cultivationCandidates = List.of(new TeamAdvancedCommissionService.CultivationCandidate(
                10001L,
                20001L,
                "V2",
                "Bob -> V2",
                "CULTIVATION-20001-V2",
                new BigDecimal("2000.000000"),
                0));

        TeamCommissionSettlementResult result = service.settleCultivation(LocalDate.of(2026, 5, 1), 50);

        assertThat(result.getCommissionType()).isEqualTo("CULTIVATION");
        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getCreated()).isEqualTo(1);
        CreatedCommission commission = service.createdCommissions.get(0);
        assertThat(commission.commissionType()).isEqualTo("CULTIVATION");
        assertThat(commission.userId()).isEqualTo(10001L);
        assertThat(commission.sourceUserId()).isEqualTo(20001L);
        assertThat(commission.orderNo()).isEqualTo("CULTIVATION-20001-V2");
        assertThat(commission.amountUsdt()).isEqualByComparingTo("0.000000");
        assertThat(commission.amountNex()).isEqualByComparingTo("2000.000000");
    }

    @Test
    void settlesLeadershipPoolByRankVotes() {
        service.leadershipRule = new TeamAdvancedCommissionService.LeadershipRule(new BigDecimal("0.050000"), 0);
        service.leadershipCandidates = List.of(
                new TeamAdvancedCommissionService.LeadershipCandidate(10001L, "V3", 1),
                new TeamAdvancedCommissionService.LeadershipCandidate(10002L, "V4", 2));

        TeamCommissionSettlementResult result = service.settleLeadership(
                LocalDate.of(2026, 5, 18),
                new BigDecimal("10000.000000"),
                50);

        assertThat(result.getCommissionType()).isEqualTo("LEADERSHIP");
        assertThat(result.getPeriod()).isEqualTo("2026-05-18");
        assertThat(result.getSourceVolumeUsdt()).isEqualByComparingTo("10000.000000");
        assertThat(result.getPoolUsdt()).isEqualByComparingTo("500.000000");
        assertThat(result.getScanned()).isEqualTo(2);
        assertThat(result.getCreated()).isEqualTo(2);
        assertThat(service.createdCommissions).extracting(CreatedCommission::amountUsdt)
                .containsExactly(new BigDecimal("166.666667"), new BigDecimal("333.333333"));
    }

    @Test
    void settlesLeadershipPoolFromCurrentWeekOrderVolumeWhenNotProvided() {
        service.leadershipRule = new TeamAdvancedCommissionService.LeadershipRule(new BigDecimal("0.050000"), 0);
        service.defaultLeadershipPlatformVolume = new BigDecimal("12000.000000");
        service.leadershipCandidates = List.of(
                new TeamAdvancedCommissionService.LeadershipCandidate(10001L, "V3", 1),
                new TeamAdvancedCommissionService.LeadershipCandidate(10002L, "V4", 2));

        TeamCommissionSettlementResult result = service.settleLeadership(
                LocalDate.of(2026, 5, 18),
                null,
                50);

        assertThat(result.getSourceVolumeUsdt()).isEqualByComparingTo("12000.000000");
        assertThat(result.getPoolUsdt()).isEqualByComparingTo("600.000000");
        assertThat(result.getCreated()).isEqualTo(2);
        assertThat(service.createdCommissions).extracting(CreatedCommission::amountUsdt)
                .containsExactly(new BigDecimal("200.000000"), new BigDecimal("400.000000"));
    }

    @Test
    void returnsLeadershipPoolSnapshotForCurrentUserVotes() {
        service.leadershipRule = new TeamAdvancedCommissionService.LeadershipRule(new BigDecimal("0.050000"), 0);
        service.leadershipCandidates = List.of(
                new TeamAdvancedCommissionService.LeadershipCandidate(10001L, "V3", 1),
                new TeamAdvancedCommissionService.LeadershipCandidate(10002L, "V4", 2));
        service.currentUserRank = "V4";
        service.currentUserVotes = 2;
        service.rankWeights = List.of(
                new LeadershipPoolRankWeight("V3", "Captain", "Captain", 1, 1, 1, new BigDecimal("33.333333"), new BigDecimal("166.666667")),
                new LeadershipPoolRankWeight("V4", "Commander", "Commander", 2, 1, 2, new BigDecimal("66.666667"), new BigDecimal("333.333333")));
        service.history = List.of(new LeadershipPoolHistoryItem(
                9108L,
                "LEADERSHIP-2026-05-18",
                new BigDecimal("333.333333"),
                "PENDING",
                LocalDateTime.of(2026, 5, 25, 0, 0),
                LocalDateTime.of(2026, 5, 18, 0, 0),
                "Pool 500.000000 split by 2 votes"));

        LeadershipPoolSnapshot snapshot = service.leadershipPoolSnapshot(10002L, new BigDecimal("10000.000000"));

        assertThat(snapshot.getUserId()).isEqualTo(10002L);
        assertThat(snapshot.getRankCode()).isEqualTo("V4");
        assertThat(snapshot.isUnlocked()).isTrue();
        assertThat(snapshot.getPoolUsdt()).isEqualByComparingTo("500.000000");
        assertThat(snapshot.getTotalVotes()).isEqualTo(3);
        assertThat(snapshot.getUserVotes()).isEqualTo(2);
        assertThat(snapshot.getEstimatedShareUsdt()).isEqualByComparingTo("333.333333");
        assertThat(snapshot.getParticipants()).hasSize(2);
        assertThat(snapshot.getRankWeights()).extracting(LeadershipPoolRankWeight::getRankCode)
                .containsExactly("V3", "V4");
        assertThat(snapshot.getHistory()).extracting(LeadershipPoolHistoryItem::getOrderNo)
                .containsExactly("LEADERSHIP-2026-05-18");
    }

    @Test
    void returnsLeadershipPoolSnapshotFromCurrentWeekOrderVolumeWhenZeroProvided() {
        service.leadershipRule = new TeamAdvancedCommissionService.LeadershipRule(new BigDecimal("0.050000"), 0);
        service.defaultLeadershipPlatformVolume = new BigDecimal("8000.000000");
        service.leadershipCandidates = List.of(new TeamAdvancedCommissionService.LeadershipCandidate(10002L, "V4", 2));
        service.currentUserRank = "V4";
        service.currentUserVotes = 2;

        LeadershipPoolSnapshot snapshot = service.leadershipPoolSnapshot(10002L, BigDecimal.ZERO);

        assertThat(snapshot.getPlatformVolumeUsdt()).isEqualByComparingTo("8000.000000");
        assertThat(snapshot.getPoolUsdt()).isEqualByComparingTo("400.000000");
        assertThat(snapshot.getEstimatedShareUsdt()).isEqualByComparingTo("400.000000");
    }

    private static class TestableTeamAdvancedCommissionService extends TeamAdvancedCommissionService {
        private List<PeerCandidate> peerCandidates = List.of();
        private List<CultivationCandidate> cultivationCandidates = List.of();
        private List<LeadershipCandidate> leadershipCandidates = List.of();
        private LeadershipRule leadershipRule = new LeadershipRule(new BigDecimal("0.050000"), 0);
        private List<LeadershipPoolRankWeight> rankWeights = List.of();
        private List<LeadershipPoolHistoryItem> history = List.of();
        private String currentUserRank = "V0";
        private int currentUserVotes;
        private BigDecimal defaultLeadershipPlatformVolume = BigDecimal.ZERO;
        private final Set<String> existingKeys = new HashSet<>();
        private final List<CreatedCommission> createdCommissions = new ArrayList<>();
        private long nextCommissionId = 9101L;

        TestableTeamAdvancedCommissionService() {
            super(null);
        }

        @Override
        protected List<PeerCandidate> peerCandidates(int limit) {
            return peerCandidates.stream().limit(limit).toList();
        }

        @Override
        protected List<CultivationCandidate> cultivationCandidates(LocalDate fromDate, int limit) {
            return cultivationCandidates.stream().limit(limit).toList();
        }

        @Override
        protected LeadershipRule leadershipRule() {
            return leadershipRule;
        }

        @Override
        protected List<LeadershipCandidate> leadershipCandidates(int limit) {
            return leadershipCandidates.stream().limit(limit).toList();
        }

        @Override
        protected int totalLeadershipVotes() {
            return leadershipCandidates.stream().mapToInt(LeadershipCandidate::votes).sum();
        }

        @Override
        protected BigDecimal weeklyPlatformVolumeUsdt(LocalDate weekStart) {
            return defaultLeadershipPlatformVolume;
        }

        @Override
        protected List<LeadershipPoolRankWeight> leadershipRankWeights(BigDecimal poolUsdt, int totalVotes) {
            return rankWeights;
        }

        @Override
        protected List<LeadershipPoolHistoryItem> leadershipHistory(Long userId, int limit) {
            return history;
        }

        @Override
        protected String userRank(Long userId) {
            return currentUserRank;
        }

        @Override
        protected int userLeadershipVotes(Long userId) {
            return currentUserVotes;
        }

        @Override
        protected boolean hasCommission(String commissionType, Long userId, String orderNo) {
            return existingKeys.contains(commissionType + ":" + userId + ":" + orderNo);
        }

        @Override
        protected Long createPeerCommission(PeerCandidate candidate, String orderNo, BigDecimal amountUsdt) {
            return addCommission(new CreatedCommission(
                    "PEER",
                    candidate.userId(),
                    null,
                    orderNo,
                    amountUsdt,
                    BigDecimal.ZERO.setScale(6)));
        }

        @Override
        protected Long createCultivationCommission(CultivationCandidate candidate) {
            return addCommission(new CreatedCommission(
                    "CULTIVATION",
                    candidate.sponsorUserId(),
                    candidate.promotedUserId(),
                    candidate.orderNo(),
                    BigDecimal.ZERO.setScale(6),
                    candidate.fixedNex()));
        }

        @Override
        protected Long createLeadershipCommission(
                LeadershipCandidate candidate,
                String orderNo,
                BigDecimal platformVolumeUsdt,
                BigDecimal poolUsdt,
                BigDecimal amountUsdt,
                int cooldownDays) {
            return addCommission(new CreatedCommission(
                    "LEADERSHIP",
                    candidate.userId(),
                    null,
                    orderNo,
                    amountUsdt,
                    BigDecimal.ZERO.setScale(6)));
        }

        private Long addCommission(CreatedCommission commission) {
            long id = nextCommissionId++;
            createdCommissions.add(commission);
            return id;
        }
    }

    private record CreatedCommission(
            String commissionType,
            Long userId,
            Long sourceUserId,
            String orderNo,
            BigDecimal amountUsdt,
            BigDecimal amountNex) {
    }
}
