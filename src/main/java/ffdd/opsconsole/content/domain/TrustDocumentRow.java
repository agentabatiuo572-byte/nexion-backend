package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

/** An uploaded trust document; a slot without a row serves its default file. */
public record TrustDocumentRow(String slot, String version, String language, String referenceNo, String verifyUrl,
        String objectKey, String fileName, Long sizeBytes, Integer pageCount, String sha256, String updatedBy, LocalDateTime updatedAt) {}
