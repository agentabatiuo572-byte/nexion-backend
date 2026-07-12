package ffdd.opsconsole.content.domain;

public record DisclosureChapterView(
        String jurisdiction,
        String version,
        String no,
        String zh,
        String vi,
        String en,
        String zhBody,
        String viBody,
        String enBody) {
}
