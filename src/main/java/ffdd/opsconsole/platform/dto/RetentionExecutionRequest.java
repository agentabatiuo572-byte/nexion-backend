package ffdd.opsconsole.platform.dto;

/** A2-governed, explicitly explained request to run a bounded retention worker now. */
public record RetentionExecutionRequest(String reason) {}
