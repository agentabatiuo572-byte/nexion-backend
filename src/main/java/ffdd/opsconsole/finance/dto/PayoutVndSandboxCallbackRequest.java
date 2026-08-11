package ffdd.opsconsole.finance.dto;

public record PayoutVndSandboxCallbackRequest(String eventId, String orderNo, String status, String signature) { }
