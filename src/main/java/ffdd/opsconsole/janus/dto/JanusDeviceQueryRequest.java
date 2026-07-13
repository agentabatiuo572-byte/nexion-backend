package ffdd.opsconsole.janus.dto;

public record JanusDeviceQueryRequest(
        String q,
        String status,
        String riskBand,
        String channel,
        String strategyId,
        Integer pageNum,
        Integer pageSize) {
}
