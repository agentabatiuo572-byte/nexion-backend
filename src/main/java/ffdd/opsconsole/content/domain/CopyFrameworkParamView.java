package ffdd.opsconsole.content.domain;

public record CopyFrameworkParamView(
        String key,
        String name,
        String current,
        String description) {
}
