package ffdd.opsconsole.common.boundary;

public record AdminSearchHit(
        String kind,
        String id,
        String title,
        String subtitle,
        String href,
        int score) {
}
