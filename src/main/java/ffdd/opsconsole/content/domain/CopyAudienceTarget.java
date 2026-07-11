package ffdd.opsconsole.content.domain;

import java.util.List;

/** Structured, immutable audience snapshot attached to a copy version. */
public record CopyAudienceTarget(
        String mode,
        List<String> locales,
        List<String> tiers,
        Integer registrationDaysMin,
        Integer registrationDaysMax) {
}
