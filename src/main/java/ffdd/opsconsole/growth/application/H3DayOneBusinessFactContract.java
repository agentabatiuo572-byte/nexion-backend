package ffdd.opsconsole.growth.application;

/** Internal success facts; neither event accepts a client completion report. */
public enum H3DayOneBusinessFactContract {
    PROFILE_SAVED("setup_profile", "H3_DAY_ONE_PROFILE_SAVED"),
    CARD_BOUND("bind_bank_card", "H3_DAY_ONE_CARD_BOUND");

    private final String questCode;
    private final String eventType;

    H3DayOneBusinessFactContract(String questCode, String eventType) {
        this.questCode = questCode;
        this.eventType = eventType;
    }

    public String questCode() { return questCode; }
    public String eventType() { return eventType; }

    public static H3DayOneBusinessFactContract forEventType(String eventType) {
        for (var rule : values()) if (rule.eventType.equals(eventType)) return rule;
        return null;
    }

    public boolean matches(String producer, String questCode, String userIdField) {
        return "SYSTEM".equals(producer) && this.questCode.equals(questCode) && "user_id".equals(userIdField);
    }
}
