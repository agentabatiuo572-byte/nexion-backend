package ffdd.opsconsole.risk.domain;

public record RiskRouteCountView(
        String routeKey,
        String label,
        Long count,
        String color
) {
}
