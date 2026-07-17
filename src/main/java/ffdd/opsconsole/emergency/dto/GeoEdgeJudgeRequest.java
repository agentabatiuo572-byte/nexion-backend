package ffdd.opsconsole.emergency.dto;

public record GeoEdgeJudgeRequest(String source, String reason, String operator, String expectedSource) {
    public GeoEdgeJudgeRequest(String source, String reason, String operator) {
        this(source, reason, operator, null);
    }
}
