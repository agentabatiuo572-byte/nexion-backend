package ffdd.opsconsole.platform.dto;

public record EventLifecycleTransitionRequest(
        String targetState,
        String expectedState,
        Long expectedVersion,
        String reason) {
}
