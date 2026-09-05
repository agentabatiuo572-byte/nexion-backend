package ffdd.opsconsole.growth.dto;

/** PC-owned canonical-event to active H3 mission binding. Expected fields provide CAS. */
public record GrowthQuestEventBindingRequest(
        String producer, String eventType, String questCode, String userIdField, Boolean enabled,
        String expectedProducer, String expectedEventType, String expectedQuestCode,
        String expectedUserIdField, Boolean expectedEnabled, String reason, String operator) {
}
