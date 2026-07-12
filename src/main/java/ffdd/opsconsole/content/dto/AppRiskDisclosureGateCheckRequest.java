package ffdd.opsconsole.content.dto;

/** Stable client-side business operation id used to deduplicate blocked metrics. */
public record AppRiskDisclosureGateCheckRequest(String operationId) {
}
