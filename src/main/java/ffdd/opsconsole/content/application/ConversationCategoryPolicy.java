package ffdd.opsconsole.content.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/** One parser and one configuration key family for PC, human support and Nova entry gates. */
final class ConversationCategoryPolicy {
    private static final Set<String> ENABLED_VALUES = Set.of("on", "true", "1", "enabled");

    private ConversationCategoryPolicy() {}

    static boolean enabledValue(String value) {
        return value != null && ENABLED_VALUES.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    static boolean enabled(PlatformConfigFacade configFacade, String type) {
        if (configFacade == null || !StringUtils.hasText(type)) return false;
        String category = type.trim().toLowerCase(Locale.ROOT);
        if ("support".equals(category)) category = "advisor";
        return configFacade.activeValue("I.session.cat." + category + ".enabled")
                .map(ConversationCategoryPolicy::enabledValue)
                .orElse(true);
    }
}
