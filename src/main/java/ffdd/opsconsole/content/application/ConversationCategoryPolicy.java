package ffdd.opsconsole.content.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/** One parser and one configuration key family for PC, human support and Nova entry gates. */
final class ConversationCategoryPolicy {
    private static final Set<String> ENABLED_VALUES = Set.of("on", "true", "1", "enabled");

    private ConversationCategoryPolicy() {}

    static boolean enabled(PlatformConfigFacade configFacade, String type) {
        if (configFacade == null || !StringUtils.hasText(type)) return false;
        return configFacade.activeValue("I.session.cat." + type.trim().toLowerCase(Locale.ROOT) + ".enabled")
                .map(String::trim)
                .map(value -> ENABLED_VALUES.contains(value.toLowerCase(Locale.ROOT)))
                .orElse(true);
    }
}
