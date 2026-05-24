package ffdd.team.service;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.team.dto.TeamBinarySettlementResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TeamBinaryCommissionServiceTest {
    private final TestableTeamBinaryCommissionService service = new TestableTeamBinaryCommissionService();

    @Test
    void settlesDailyBinaryCommissionFromFirstTwoDirectLegs() {
        LocalDate settlementDate = LocalDate.of(2026, 5, 24);
        service.userIds = List.of(10001L);
        service.directLegs.put(10001L, List.of(20001L, 20002L));
        service.branchVolumes.put("10001:20001", new BigDecimal("1000.000000"));
        service.branchVolumes.put("10001:20002", new BigDecimal("600.000000"));

        TeamBinarySettlementResult result = service.settle(settlementDate, 50);

        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getCreated()).isEqualTo(1);
        assertThat(result.getSkipped()).isZero();
        assertThat(result.getCommissionIds()).containsExactly(9001L);
        TestableTeamBinaryCommissionService.CreatedCommission commission = service.createdCommissions.get(0);
        assertThat(commission.userId()).isEqualTo(10001L);
        assertThat(commission.orderNo()).isEqualTo("BINARY-2026-05-24");
        assertThat(commission.matchedVolume()).isEqualByComparingTo("600.000000");
        assertThat(commission.amountUsdt()).isEqualByComparingTo("60.000000");
        TestableTeamBinaryCommissionService.CreatedSettlement settlement = service.createdSettlements.get(0);
        assertThat(settlement.leftUserId()).isEqualTo(20001L);
        assertThat(settlement.rightUserId()).isEqualTo(20002L);
        assertThat(settlement.commissionEventId()).isEqualTo(9001L);
    }

    @Test
    void appliesDailyCapBeforeCreatingCommission() {
        LocalDate settlementDate = LocalDate.of(2026, 5, 24);
        service.userIds = List.of(10001L);
        service.directLegs.put(10001L, List.of(20001L, 20002L));
        service.branchVolumes.put("10001:20001", new BigDecimal("99999.000000"));
        service.branchVolumes.put("10001:20002", new BigDecimal("88888.000000"));

        service.settle(settlementDate, 50);

        assertThat(service.createdCommissions.get(0).matchedVolume()).isEqualByComparingTo("88888.000000");
        assertThat(service.createdCommissions.get(0).amountUsdt()).isEqualByComparingTo("5000.000000");
    }

    @Test
    void skipsAlreadySettledUserForSameDate() {
        service.userIds = List.of(10001L);
        service.alreadySettledUsers.add(10001L);

        TeamBinarySettlementResult result = service.settle(LocalDate.of(2026, 5, 24), 50);

        assertThat(result.getCreated()).isZero();
        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(service.createdCommissions).isEmpty();
    }

    @Test
    void skipsWhenUserDoesNotHaveTwoDirectLegs() {
        service.userIds = List.of(10001L);
        service.directLegs.put(10001L, List.of(20001L));

        TeamBinarySettlementResult result = service.settle(LocalDate.of(2026, 5, 24), 50);

        assertThat(result.getCreated()).isZero();
        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(service.createdCommissions).isEmpty();
    }

    @Test
    void skipsWhenNoMatchedVolumeExists() {
        service.userIds = List.of(10001L);
        service.directLegs.put(10001L, List.of(20001L, 20002L));
        service.branchVolumes.put("10001:20001", BigDecimal.ZERO);
        service.branchVolumes.put("10001:20002", new BigDecimal("600.000000"));

        TeamBinarySettlementResult result = service.settle(LocalDate.of(2026, 5, 24), 50);

        assertThat(result.getCreated()).isZero();
        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(service.createdCommissions).isEmpty();
    }

    @Test
    void subtractsHistoricalMatchedVolumeBeforeDailySettlement() {
        service.userIds = List.of(10001L);
        service.directLegs.put(10001L, List.of(20001L, 20002L));
        service.branchVolumes.put("10001:20001", new BigDecimal("1000.000000"));
        service.branchVolumes.put("10001:20002", new BigDecimal("900.000000"));
        service.previousMatchedVolumes.put(10001L, new BigDecimal("600.000000"));

        TeamBinarySettlementResult result = service.settle(LocalDate.of(2026, 5, 25), 50);

        assertThat(result.getCreated()).isEqualTo(1);
        assertThat(service.createdCommissions.get(0).matchedVolume()).isEqualByComparingTo("300.000000");
        assertThat(service.createdCommissions.get(0).amountUsdt()).isEqualByComparingTo("30.000000");
    }

    private static class TestableTeamBinaryCommissionService extends TeamBinaryCommissionService {
        private List<Long> userIds = List.of();
        private final Map<Long, List<Long>> directLegs = new HashMap<>();
        private final Map<String, BigDecimal> branchVolumes = new HashMap<>();
        private final Map<Long, BigDecimal> previousMatchedVolumes = new HashMap<>();
        private final List<Long> alreadySettledUsers = new ArrayList<>();
        private final List<CreatedCommission> createdCommissions = new ArrayList<>();
        private final List<CreatedSettlement> createdSettlements = new ArrayList<>();
        private long nextCommissionId = 9001L;

        TestableTeamBinaryCommissionService() {
            super(null);
        }

        @Override
        protected BinaryRule binaryRule() {
            return new BinaryRule(new BigDecimal("0.100000"), new BigDecimal("5000.000000"), 1);
        }

        @Override
        protected List<Long> eligibleUserIds(int limit) {
            return userIds.stream().limit(limit).toList();
        }

        @Override
        protected boolean hasBinarySettlement(Long userId, LocalDate settlementDate) {
            return alreadySettledUsers.contains(userId);
        }

        @Override
        protected List<Long> firstTwoDirectLegs(Long userId) {
            return directLegs.getOrDefault(userId, List.of());
        }

        @Override
        protected BigDecimal branchVolume(Long userId, Long directLegUserId) {
            return branchVolumes.getOrDefault(userId + ":" + directLegUserId, BigDecimal.ZERO);
        }

        @Override
        protected BigDecimal previousMatchedVolume(Long userId, LocalDate settlementDate) {
            return previousMatchedVolumes.getOrDefault(userId, BigDecimal.ZERO);
        }

        @Override
        protected Long createBinaryCommission(
                Long userId,
                Long leftUserId,
                Long rightUserId,
                String orderNo,
                BigDecimal leftVolume,
                BigDecimal rightVolume,
                BigDecimal matchedVolume,
                BigDecimal amountUsdt,
                BinaryRule rule) {
            long id = nextCommissionId++;
            createdCommissions.add(new CreatedCommission(userId, orderNo, matchedVolume, amountUsdt));
            return id;
        }

        @Override
        protected void createBinarySettlement(
                Long userId,
                LocalDate settlementDate,
                Long leftUserId,
                Long rightUserId,
                BigDecimal leftVolume,
                BigDecimal rightVolume,
                BigDecimal matchedVolume,
                BigDecimal amountUsdt,
                BigDecimal dailyCapUsdt,
                Long commissionEventId) {
            createdSettlements.add(new CreatedSettlement(leftUserId, rightUserId, commissionEventId));
        }

        private record CreatedCommission(
                Long userId,
                String orderNo,
                BigDecimal matchedVolume,
                BigDecimal amountUsdt) {
        }

        private record CreatedSettlement(Long leftUserId, Long rightUserId, Long commissionEventId) {
        }
    }
}
