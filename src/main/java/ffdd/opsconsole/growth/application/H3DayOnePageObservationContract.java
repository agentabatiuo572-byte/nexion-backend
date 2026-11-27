package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper.CanonicalQuestEventBinding;
import java.util.List;
import java.util.Locale;

/** Fixed server contract for Day One page-read facts; it has no reward fields. */
public final class H3DayOnePageObservationContract {
    public static final String PRODUCER = "SYSTEM";
    public static final String USER_ID_FIELD = "user_id";
    private static final List<Rule> RULES = List.of(
            new Rule("earn", "visit_earn", "H3_DAY_ONE_EARN_PAGE_VIEWED"),
            new Rule("store", "visit_store", "H3_DAY_ONE_STORE_PAGE_VIEWED"),
            new Rule("s1-roi", "view_product_roi", "H3_DAY_ONE_S1_ROI_VIEWED"));

    private H3DayOnePageObservationContract() { }

    public static Rule forSurface(String rawSurface) {
        String surface = rawSurface == null ? "" : rawSurface.trim().toLowerCase(Locale.ROOT);
        return RULES.stream().filter(rule -> rule.surface().equals(surface)).findFirst().orElse(null);
    }

    public static Rule forEventType(String eventType) {
        return RULES.stream().filter(rule -> rule.eventType().equals(eventType)).findFirst().orElse(null);
    }

    static List<Rule> rules() {
        return RULES;
    }

    public static boolean matches(CanonicalQuestEventBinding binding, String eventType) {
        if (binding == null) return false;
        return eventType != null && eventType.equals(binding.eventType())
                && matches(binding.producer(), eventType, binding.questCode(), binding.userIdField());
    }

    public static boolean matches(String producer, String eventType, String questCode, String userIdField) {
        Rule rule = forEventType(eventType);
        return rule != null && PRODUCER.equals(producer) && rule.questCode().equalsIgnoreCase(questCode)
                && USER_ID_FIELD.equals(userIdField);
    }

    public record Rule(String surface, String questCode, String eventType) { }
}
