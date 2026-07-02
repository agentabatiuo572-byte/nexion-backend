package ffdd.opsconsole.team.domain;

public record TeamFulfillmentQueueRow(
        String rankCode,
        String rewardName,
        String status,
        Long count) {
}
