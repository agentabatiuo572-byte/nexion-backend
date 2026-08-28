package ffdd.opsconsole.finance.dto;

/**
 * Development-only D2 lifecycle command. The server owns the effective time;
 * callers may only provide the auditable business reason.
 */
public record DevelopmentD2CooldownSimulationRequest(String reason) { }
