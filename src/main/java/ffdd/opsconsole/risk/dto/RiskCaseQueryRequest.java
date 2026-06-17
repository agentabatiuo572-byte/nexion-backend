package ffdd.opsconsole.risk.dto;

public record RiskCaseQueryRequest(
        Long userId,
        String status,
        String decision,
        Integer limit) {
}
