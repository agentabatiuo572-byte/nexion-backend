package ffdd.opsconsole.emergency.dto;

public record SopPlaybookRunRequest(Boolean emergency, String reason, String operator) {
}
