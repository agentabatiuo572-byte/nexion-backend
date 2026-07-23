package ffdd.opsconsole.platform.dto;

/** A4 parameter mutation. The actor always comes from the authenticated admin session. */
public record EventCenterMutationRequest(String value, String reason) {
}
