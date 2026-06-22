package ffdd.opsconsole.content.domain;

public record I18nNamespaceView(
        String ns,
        int keys,
        int coverage,
        String variants,
        String lastChange) {
}
