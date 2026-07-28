package ffdd.opsconsole.growth.dto;

public record ReferralSettlementRunRequest(
        Integer limit,
        String reason,
        String operator,
        Long expectedH8Version,
        Integer expectedRhythmMonth,
        String rewardSnapshotHash) {
}
