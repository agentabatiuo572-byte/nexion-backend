package ffdd.opsconsole.content.dto;

/** Multipart form fields sent with an uploaded trust document. */
public record TrustDocumentUploadRequest(String version, String language, String referenceNo, String verifyUrl,
        String reason, String expectedSha256) {
}
