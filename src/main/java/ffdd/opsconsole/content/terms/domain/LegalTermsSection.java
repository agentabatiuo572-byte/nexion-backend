package ffdd.opsconsole.content.terms.domain;

/** A structured, localized Terms section; the admin UI edits these fields, not raw JSON. */
public record LegalTermsSection(String key, String title, String body, int sortOrder) { }
