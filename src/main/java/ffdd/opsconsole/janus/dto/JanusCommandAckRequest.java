package ffdd.opsconsole.janus.dto;

public record JanusCommandAckRequest(
        String deviceId,
        Long revision,
        Boolean success,
        String appliedStatus,
        String message) {
}
