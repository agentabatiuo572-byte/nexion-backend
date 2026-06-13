package ffdd.compliance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.exception.BizException;
import ffdd.common.storage.ObjectStorageService;
import ffdd.compliance.domain.KycProfile;
import ffdd.compliance.domain.ProofAsset;
import ffdd.compliance.dto.EvidenceDownloadUrlResponse;
import ffdd.compliance.dto.EvidenceUploadPolicyRequest;
import ffdd.compliance.dto.EvidenceUploadPolicyResponse;
import ffdd.compliance.dto.KycDocumentUploadRequest;
import ffdd.compliance.dto.KycProfileSubmitRequest;
import ffdd.compliance.dto.ProofAssetCreateRequest;
import ffdd.compliance.dto.ProofAssetUploadRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ComplianceEvidenceService {
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final DateTimeFormatter KEY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "video/mp4",
            "video/webm",
            "video/quicktime",
            "application/pdf",
            "application/json",
            "text/plain");
    private static final Map<String, String> DEFAULT_EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "video/mp4", ".mp4",
            "video/webm", ".webm",
            "video/quicktime", ".mov",
            "application/pdf", ".pdf",
            "application/json", ".json",
            "text/plain", ".txt");
    private static final Map<String, Set<String>> ALLOWED_EXTENSIONS = Map.of(
            "image/jpeg", Set.of(".jpg", ".jpeg"),
            "image/png", Set.of(".png"),
            "image/webp", Set.of(".webp"),
            "video/mp4", Set.of(".mp4", ".m4v"),
            "video/webm", Set.of(".webm"),
            "video/quicktime", Set.of(".mov", ".qt"),
            "application/pdf", Set.of(".pdf"),
            "application/json", Set.of(".json"),
            "text/plain", Set.of(".txt"));
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ObjectStorageService storageService;
    private final KycProfileService kycProfileService;
    private final ProofAssetService proofAssetService;
    private final long maxUploadSizeBytes;
    private final int presignExpirySeconds;

    public ComplianceEvidenceService(
            ObjectStorageService storageService,
            KycProfileService kycProfileService,
            ProofAssetService proofAssetService,
            @Value("${nexion.compliance.evidence.max-upload-size-bytes:52428800}") long maxUploadSizeBytes,
            @Value("${nexion.compliance.evidence.presign-expiry-seconds:900}") int presignExpirySeconds) {
        this.storageService = storageService;
        this.kycProfileService = kycProfileService;
        this.proofAssetService = proofAssetService;
        this.maxUploadSizeBytes = Math.max(1, maxUploadSizeBytes);
        this.presignExpirySeconds = Math.max(60, Math.min(presignExpirySeconds, 86_400));
    }

    @Transactional(rollbackFor = Exception.class)
    public KycProfile uploadKycDocument(KycDocumentUploadRequest request, MultipartFile file) {
        validateKycRequest(request);
        FilePayload payload = readAndValidateFile(file);
        String objectKey = objectKey("compliance/kyc", request.getUserId(), request.getDocumentType(), payload.extension());
        storageService.put(objectKey, payload.contentType(), new ByteArrayInputStream(payload.bytes()), payload.sizeBytes());

        KycProfileSubmitRequest submit = new KycProfileSubmitRequest();
        submit.setUserId(request.getUserId());
        submit.setKycNo(normalizedOptionalText(request.getKycNo()));
        submit.setCountry(request.getCountry());
        submit.setApplicantName(normalizedOptionalText(request.getApplicantName()));
        submit.setDocumentType(request.getDocumentType());
        submit.setDocumentLast4(normalizedOptionalText(request.getDocumentLast4()));
        submit.setDocumentObjectKey(objectKey);
        return kycProfileService.submit(submit);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProofAsset uploadProofAsset(ProofAssetUploadRequest request, MultipartFile file) {
        validateProofRequest(request);
        FilePayload payload = readAndValidateFile(file);
        String objectKey = objectKey("compliance/proofs", request.getUserId(), request.getProofType(), payload.extension());
        storageService.put(objectKey, payload.contentType(), new ByteArrayInputStream(payload.bytes()), payload.sizeBytes());

        ProofAssetCreateRequest create = new ProofAssetCreateRequest();
        create.setUserId(request.getUserId());
        create.setProofNo(normalizedOptionalText(request.getProofNo()));
        create.setProofType(request.getProofType());
        create.setObjectKey(objectKey);
        create.setStatus(normalizedOptionalText(request.getStatus()));
        create.setFileName(payload.originalFileName());
        create.setContentType(payload.contentType());
        create.setSizeBytes(payload.sizeBytes());
        create.setChecksum("sha256:" + sha256(payload.bytes()));
        create.setRelatedBizType(normalizedOptionalText(request.getRelatedBizType()));
        create.setRelatedBizNo(normalizedOptionalText(request.getRelatedBizNo()));
        create.setSubmittedBy(normalizedOptionalText(request.getSubmittedBy()));
        create.setMetadataJson(buildProofMetadataJson(request));
        return proofAssetService.create(create);
    }

    public EvidenceUploadPolicyResponse createUploadPolicy(EvidenceUploadPolicyRequest request) {
        validateUploadPolicy(request);
        FileDescriptor descriptor = fileDescriptor(request.getFileName(), request.getContentType(), request.getSizeBytes());
        String objectKey = objectKey(
                uploadPolicyPrefix(request.getEvidenceType()),
                request.getUserId(),
                request.getEvidenceType(),
                descriptor.extension());
        String uploadUrl = storageService.presignPut(
                objectKey,
                descriptor.contentType(),
                Duration.ofSeconds(presignExpirySeconds));
        EvidenceUploadPolicyResponse response = new EvidenceUploadPolicyResponse();
        response.setObjectKey(objectKey);
        response.setUploadUrl(uploadUrl);
        response.setMethod("PUT");
        response.setContentType(descriptor.contentType());
        response.setSizeBytes(descriptor.sizeBytes());
        response.setExpiresInSeconds(presignExpirySeconds);
        response.setExpiresAt(LocalDateTime.now().plusSeconds(presignExpirySeconds));
        return response;
    }

    public EvidenceDownloadUrlResponse createDownloadUrl(String objectKey, int expiresInSeconds) {
        validateStorageKey("Object key", objectKey, 255);
        int normalizedExpiry = expiresInSeconds <= 0
                ? presignExpirySeconds
                : Math.max(60, Math.min(expiresInSeconds, 86_400));
        String downloadUrl = storageService.presignGet(objectKey.trim(), Duration.ofSeconds(normalizedExpiry));
        EvidenceDownloadUrlResponse response = new EvidenceDownloadUrlResponse();
        response.setObjectKey(objectKey.trim());
        response.setDownloadUrl(downloadUrl);
        response.setExpiresInSeconds(normalizedExpiry);
        response.setExpiresAt(LocalDateTime.now().plusSeconds(normalizedExpiry));
        return response;
    }

    private FilePayload readAndValidateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("Evidence file is required");
        }
        try {
            byte[] bytes = file.getBytes();
            FileDescriptor descriptor = fileDescriptor(file.getOriginalFilename(), file.getContentType(), (long) bytes.length);
            return new FilePayload(
                    descriptor.originalFileName(),
                    descriptor.contentType(),
                    descriptor.extension(),
                    descriptor.sizeBytes(),
                    bytes);
        } catch (IOException ex) {
            throw new BizException("Evidence file cannot be read");
        }
    }

    private FileDescriptor fileDescriptor(String fileName, String contentType, Long sizeBytes) {
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new BizException("Evidence file size must be positive");
        }
        if (sizeBytes > maxUploadSizeBytes) {
            throw new BizException("Evidence file is too large");
        }
        String normalizedContentType = normalizeContentType(contentType);
        if (!ALLOWED_CONTENT_TYPES.contains(normalizedContentType)) {
            throw new BizException("Unsupported evidence content type");
        }
        String safeFileName = safeFileName(fileName);
        return new FileDescriptor(
                safeFileName,
                normalizedContentType,
                safeExtension(safeFileName, normalizedContentType),
                sizeBytes);
    }

    private String objectKey(String prefix, Long userId, String type, String extension) {
        String normalizedType = normalizePathToken(type, "Evidence type");
        String fileId = KEY_TIME_FORMAT.format(LocalDateTime.now())
                + "-"
                + UUID.randomUUID().toString().replace("-", "");
        return prefix + "/" + userId + "/" + normalizedType + "/" + fileId + extension;
    }

    private void validateKycRequest(KycDocumentUploadRequest request) {
        if (request == null) {
            throw new BizException("KYC document upload request is required");
        }
        validateUserId(request.getUserId());
        validateOptionalToken("KYC no", request.getKycNo(), 96);
        validateRequiredText("Country", request.getCountry(), 64);
        validateOptionalText("Applicant name", request.getApplicantName(), 128);
        validateRequiredText("Document type", request.getDocumentType(), 64);
        validateOptionalToken("Document last4", request.getDocumentLast4(), 16);
    }

    private void validateProofRequest(ProofAssetUploadRequest request) {
        if (request == null) {
            throw new BizException("Proof asset upload request is required");
        }
        validateUserId(request.getUserId());
        validateOptionalToken("Proof no", request.getProofNo(), 96);
        validateRequiredText("Proof type", request.getProofType(), 64);
        validateOptionalToken("Status", request.getStatus(), 32);
        validateOptionalToken("Related biz type", request.getRelatedBizType(), 64);
        validateOptionalToken("Related biz no", request.getRelatedBizNo(), 96);
        validateOptionalToken("Submitted by", request.getSubmittedBy(), 64);
        validateOptionalToken("Metadata variant", request.getMetadataVariant(), 32);
        validateOptionalToken("Referral code", request.getReferralCode(), 64);
        validateOptionalToken("Receipt no", request.getReceiptNo(), 96);
        validateOptionalText("Metadata json", request.getMetadataJson(), 2048);
    }

    private String buildProofMetadataJson(ProofAssetUploadRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putText(metadata, "variant", request.getMetadataVariant());
        putNumber(metadata, "totalEarnings", request.getTotalEarnings());
        putNumber(metadata, "currentStreak", request.getCurrentStreak());
        putNumber(metadata, "longestStreak", request.getLongestStreak());
        putNumber(metadata, "teamMembers", request.getTeamMembers());
        putText(metadata, "referralCode", request.getReferralCode());
        putText(metadata, "receiptNo", request.getReceiptNo());
        if (metadata.isEmpty()) {
            return normalizedOptionalText(request.getMetadataJson());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new BizException("Proof metadata cannot be serialized");
        }
    }

    private void putText(Map<String, Object> metadata, String key, String value) {
        String text = normalizedOptionalText(value);
        if (text != null) {
            metadata.put(key, text);
        }
    }

    private void putNumber(Map<String, Object> metadata, String key, Number value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private void validateUploadPolicy(EvidenceUploadPolicyRequest request) {
        if (request == null) {
            throw new BizException("Evidence upload policy request is required");
        }
        validateUserId(request.getUserId());
        validateRequiredText("Evidence type", request.getEvidenceType(), 64);
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new BizException("User id is required");
        }
    }

    private void validateRequiredText(String fieldName, String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(fieldName + " is required");
        }
        validateOptionalToken(fieldName, value, maxLength);
    }

    private void validateOptionalToken(String fieldName, String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength || !SAFE_TOKEN.matcher(trimmed).matches()) {
            throw new BizException(fieldName + " contains invalid characters");
        }
    }

    private void validateOptionalText(String fieldName, String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (value.length() > maxLength || containsControlCharacters(value)) {
            throw new BizException(fieldName + " contains invalid characters");
        }
    }

    private void validateStorageKey(String fieldName, String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(fieldName + " is required");
        }
        if (value.length() > maxLength || containsControlCharacters(value)) {
            throw new BizException(fieldName + " contains invalid characters");
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("http://")
                || trimmed.startsWith("https://")
                || trimmed.startsWith("/")
                || trimmed.endsWith("/")
                || trimmed.contains("..")
                || trimmed.indexOf('\\') >= 0) {
            throw new BizException(fieldName + " must be a storage object key");
        }
    }

    private String uploadPolicyPrefix(String evidenceType) {
        String normalizedType = normalizePathToken(evidenceType, "Evidence type");
        return normalizedType.startsWith("proof") ? "compliance/proofs" : "compliance/kyc";
    }

    private String normalizePathToken(String value, String fieldName) {
        validateRequiredText(fieldName, value, 64);
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            throw new BizException("Evidence content type is required");
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 128 || containsControlCharacters(normalized)) {
            throw new BizException("Evidence content type contains invalid characters");
        }
        return normalized;
    }

    private String safeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "evidence";
        }
        String normalized = fileName.trim()
                .replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        if (!StringUtils.hasText(normalized) || normalized.length() > 255 || containsControlCharacters(normalized)) {
            throw new BizException("Evidence file name contains invalid characters");
        }
        return normalized;
    }

    private String safeExtension(String fileName, String contentType) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
            if (extension.length() <= 16
                    && extension.matches("\\.[a-z0-9]+")
                    && ALLOWED_EXTENSIONS.getOrDefault(contentType, Set.of()).contains(extension)) {
                return extension;
            }
            throw new BizException("Unsupported evidence file extension");
        }
        return DEFAULT_EXTENSIONS.getOrDefault(contentType, ".bin");
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(500, "SHA-256 digest unavailable");
        }
    }

    private String normalizedOptionalText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean containsControlCharacters(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\t') >= 0;
    }

    private record FileDescriptor(String originalFileName, String contentType, String extension, long sizeBytes) {
    }

    private record FilePayload(String originalFileName, String contentType, String extension, long sizeBytes, byte[] bytes) {
    }
}
