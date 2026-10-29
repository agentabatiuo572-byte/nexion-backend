package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.List;

/** Company profile and the current file of each trust document slot. */
public record TrustDocumentsView(Profile profile, List<Document> documents) {
    public record Profile(String legalName, String jurisdiction, String registeredAddress,
            String nyDosId, String nyFilingNumber, String authorizedCapitalUsd) {}

    /**
     * {@code source} is DEFAULT or UPLOADED; {@code updatedBy}/{@code updatedAt} are only filled for the console.
     * Page images live at {@code /api/content/trust/documents/{slot}/pages/{1..pageCount}?v=} plus the first 12 hex
     * characters of {@code sha256}. {@code fileUrl} is null for the white paper, which is not offered as a download.
     */
    public record Document(String slot, String source, String version, String language, String referenceNo,
            String verifyUrl, String fileName, long sizeBytes, int pageCount, String sha256, String fileUrl,
            String updatedBy, LocalDateTime updatedAt) {}
}
