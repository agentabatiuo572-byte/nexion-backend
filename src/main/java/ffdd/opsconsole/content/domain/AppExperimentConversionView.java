package ffdd.opsconsole.content.domain;

/** A duplicate conversion is accepted but not counted again. */
public record AppExperimentConversionView(
        String experimentId,
        String conversionKey,
        boolean counted) {
}
