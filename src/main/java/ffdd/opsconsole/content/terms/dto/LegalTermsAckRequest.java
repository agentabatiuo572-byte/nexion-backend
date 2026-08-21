package ffdd.opsconsole.content.terms.dto;

public record LegalTermsAckRequest(
        String locale, String jurisdiction, String version, Boolean confirmed,
        String idempotencyKey, String runId) { }
