package ffdd.opsconsole.content.domain;

public record TrustDisclosureStats(
        int managedSections,
        int jurisdictions,
        long staleAckUsers,
        long weeklyGateBlocked,
        double sfcReackPct) {
}
