package ffdd.opsconsole.content.domain;

public record TrustSectionView(
        String key,
        String desc,
        String struct,
        String version,
        String status,
        String lastChange,
        String roleGate,
        boolean highSensitivity) {
}
