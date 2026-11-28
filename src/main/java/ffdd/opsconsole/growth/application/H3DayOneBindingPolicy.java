package ffdd.opsconsole.growth.application;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** An exclusive task and its event can only be bound to one another. */
final class H3DayOneBindingPolicy {
    private static final List<Rule> RULES = Stream.concat(
            H3DayOnePageObservationContract.rules().stream().map(rule -> new Rule(
                    rule.questCode(), rule.eventType(), "H3_DAY_ONE_PAGE_OBSERVATION_BINDING_INVALID")),
            Arrays.stream(H3DayOneBusinessFactContract.values()).map(rule -> new Rule(
                    rule.questCode(), rule.eventType(), "H3_DAY_ONE_BUSINESS_BINDING_INVALID")))
            .toList();

    private H3DayOneBindingPolicy() { }

    static boolean hasFixedQuest(String questCode) {
        return forQuestCode(questCode) != null;
    }

    static boolean permits(String producer, String eventType, String questCode, String userIdField) {
        return mismatch(producer, eventType, questCode, userIdField) == null;
    }

    static String mismatch(String producer, String eventType, String questCode, String userIdField) {
        Rule rule = RULES.stream().filter(candidate -> candidate.eventType().equalsIgnoreCase(eventType))
                .findFirst().orElseGet(() -> forQuestCode(questCode));
        if (rule == null || (rule.questCode().equalsIgnoreCase(questCode) && rule.eventType().equals(eventType)
                && H3DayOnePageObservationContract.PRODUCER.equals(producer)
                && H3DayOnePageObservationContract.USER_ID_FIELD.equals(userIdField))) {
            return null;
        }
        return rule.error();
    }

    private static Rule forQuestCode(String questCode) {
        return RULES.stream().filter(rule -> rule.questCode().equalsIgnoreCase(questCode)).findFirst().orElse(null);
    }

    private record Rule(String questCode, String eventType, String error) { }
}
