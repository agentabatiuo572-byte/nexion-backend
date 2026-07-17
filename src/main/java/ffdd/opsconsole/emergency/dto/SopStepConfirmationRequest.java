package ffdd.opsconsole.emergency.dto;

public record SopStepConfirmationRequest(
        Integer step,
        String domain,
        String ref,
        Boolean confirmed) {
}
