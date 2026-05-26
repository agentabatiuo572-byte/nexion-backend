package ffdd.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import ffdd.common.exception.BizException;
import ffdd.compliance.domain.ProofAsset;
import ffdd.compliance.dto.ProofAssetCreateRequest;
import ffdd.compliance.dto.ProofAssetReviewRequest;
import ffdd.compliance.mapper.ProofAssetMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProofAssetService {
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_VERIFIED = "VERIFIED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ProofAssetMapper proofAssetMapper;

    public ProofAssetService(ProofAssetMapper proofAssetMapper) {
        this.proofAssetMapper = proofAssetMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProofAsset create(ProofAssetCreateRequest request) {
        validateCreate(request);
        ProofAsset proof = new ProofAsset();
        proof.setUserId(request.getUserId());
        proof.setProofNo(normalizeProofNo(request.getProofNo(), request.getUserId()));
        proof.setProofType(normalizeRequiredToken("Proof type", request.getProofType(), 64));
        proof.setObjectKey(request.getObjectKey().trim());
        proof.setStatus(normalizeCreateStatus(request.getStatus()));
        proof.setFileName(normalizedOptionalText(request.getFileName()));
        proof.setContentType(normalizedOptionalText(request.getContentType()));
        proof.setSizeBytes(request.getSizeBytes());
        proof.setChecksum(normalizedOptionalText(request.getChecksum()));
        proof.setRelatedBizType(normalizeOptionalToken(request.getRelatedBizType()));
        proof.setRelatedBizNo(normalizedOptionalText(request.getRelatedBizNo()));
        proof.setSubmittedBy(normalizedOptionalText(request.getSubmittedBy()));
        proof.setMetadataJson(normalizedOptionalText(request.getMetadataJson()));
        proof.setIsDeleted(0);
        try {
            proofAssetMapper.insert(proof);
            return proof;
        } catch (DuplicateKeyException ex) {
            throw new BizException("Duplicate proof asset no: " + proof.getProofNo());
        }
    }

    public ProofAsset getByProofNo(String proofNo) {
        validateRequiredText("Proof no", proofNo, 96);
        return findActiveByProofNo(proofNo);
    }

    public List<ProofAsset> list(Long userId, String proofType, String status, int limit) {
        validateOptionalToken("Proof type", proofType, 64);
        String normalizedProofType = normalizeOptionalToken(proofType);
        String normalizedStatus = normalizeOptionalStatus(status);
        LambdaQueryWrapper<ProofAsset> wrapper = new LambdaQueryWrapper<ProofAsset>()
                .eq(ProofAsset::getIsDeleted, 0)
                .eq(userId != null, ProofAsset::getUserId, userId)
                .eq(StringUtils.hasText(normalizedProofType), ProofAsset::getProofType, normalizedProofType)
                .eq(StringUtils.hasText(normalizedStatus), ProofAsset::getStatus, normalizedStatus)
                .orderByDesc(ProofAsset::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit));
        return proofAssetMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProofAsset verify(String proofNo, ProofAssetReviewRequest request) {
        validateReview(request, false);
        ProofAsset proof = requireActiveByProofNo(proofNo);
        return updateReviewState(proof, STATUS_VERIFIED, request, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProofAsset reject(String proofNo, ProofAssetReviewRequest request) {
        validateReview(request, true);
        ProofAsset proof = requireActiveByProofNo(proofNo);
        return updateReviewState(proof, STATUS_REJECTED, request, request.getReason().trim());
    }

    @Transactional(rollbackFor = Exception.class)
    public ProofAsset delete(String proofNo) {
        ProofAsset proof = requireActiveByProofNo(proofNo);
        UpdateWrapper<ProofAsset> wrapper = new UpdateWrapper<ProofAsset>()
                .eq("id", proof.getId())
                .set("status", STATUS_ARCHIVED)
                .set("is_deleted", 1);
        proofAssetMapper.update(null, wrapper);
        proof.setStatus(STATUS_ARCHIVED);
        proof.setIsDeleted(1);
        return proof;
    }

    private ProofAsset updateReviewState(
            ProofAsset proof, String status, ProofAssetReviewRequest request, String rejectReason) {
        LocalDateTime reviewedAt = LocalDateTime.now();
        String reviewer = request.getReviewer().trim();
        String reviewNote = normalizedOptionalText(request.getReason());
        UpdateWrapper<ProofAsset> wrapper = new UpdateWrapper<ProofAsset>()
                .eq("id", proof.getId())
                .set("status", status)
                .set("reviewed_by", reviewer)
                .set("reviewed_at", reviewedAt)
                .set("reject_reason", rejectReason)
                .set("review_note", reviewNote);
        proofAssetMapper.update(null, wrapper);

        proof.setStatus(status);
        proof.setReviewedBy(reviewer);
        proof.setReviewedAt(reviewedAt);
        proof.setRejectReason(rejectReason);
        proof.setReviewNote(reviewNote);
        return proof;
    }

    private ProofAsset requireActiveByProofNo(String proofNo) {
        ProofAsset proof = findActiveByProofNo(proofNo);
        if (proof == null) {
            throw new BizException("Proof asset not found");
        }
        return proof;
    }

    private ProofAsset findActiveByProofNo(String proofNo) {
        validateRequiredText("Proof no", proofNo, 96);
        return proofAssetMapper.selectOne(new LambdaQueryWrapper<ProofAsset>()
                .eq(ProofAsset::getProofNo, proofNo.trim().toUpperCase(Locale.ROOT))
                .eq(ProofAsset::getIsDeleted, 0));
    }

    private void validateCreate(ProofAssetCreateRequest request) {
        if (request == null) {
            throw new BizException("Proof asset request is required");
        }
        if (request.getUserId() == null) {
            throw new BizException("User id is required");
        }
        validateOptionalToken("Proof no", request.getProofNo(), 96);
        validateRequiredText("Proof type", request.getProofType(), 64);
        validateStorageKey("Object key", request.getObjectKey(), 255);
        normalizeCreateStatus(request.getStatus());
        validateOptionalToken("File name", request.getFileName(), 255);
        validateOptionalToken("Content type", request.getContentType(), 128);
        if (request.getSizeBytes() != null && request.getSizeBytes() < 0) {
            throw new BizException("Size bytes must not be negative");
        }
        validateOptionalToken("Checksum", request.getChecksum(), 128);
        validateOptionalToken("Related biz type", request.getRelatedBizType(), 64);
        validateOptionalToken("Related biz no", request.getRelatedBizNo(), 96);
        validateOptionalToken("Submitted by", request.getSubmittedBy(), 64);
        validateMetadataJson(request.getMetadataJson());
    }

    private void validateReview(ProofAssetReviewRequest request, boolean requireReason) {
        if (request == null) {
            throw new BizException("Proof asset review request is required");
        }
        validateRequiredText("Reviewer", request.getReviewer(), 64);
        if (requireReason) {
            validateRequiredText("Review reason", request.getReason(), 255);
        } else {
            validateOptionalToken("Review reason", request.getReason(), 255);
        }
    }

    private void validateRequiredText(String fieldName, String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(fieldName + " is required");
        }
        validateOptionalToken(fieldName, value, maxLength);
    }

    private void validateOptionalToken(String fieldName, String value, int maxLength) {
        if (StringUtils.hasText(value) && value.length() > maxLength) {
            throw new BizException(fieldName + " is too long");
        }
        if (StringUtils.hasText(value) && containsControlCharacters(value)) {
            throw new BizException(fieldName + " contains invalid characters");
        }
    }

    private void validateMetadataJson(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return;
        }
        if (metadataJson.length() > 2048) {
            throw new BizException("Metadata json is too long");
        }
        String trimmed = metadataJson.trim();
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))
                && !(trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            throw new BizException("Metadata json must be an object or array string");
        }
    }

    private void validateStorageKey(String fieldName, String value, int maxLength) {
        validateRequiredText(fieldName, value, maxLength);
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.contains("..")) {
            throw new BizException(fieldName + " must be a storage object key");
        }
    }

    private String normalizeProofNo(String requestedProofNo, Long userId) {
        if (StringUtils.hasText(requestedProofNo)) {
            return requestedProofNo.trim().toUpperCase(Locale.ROOT);
        }
        return "PROOF-" + userId + "-" + System.currentTimeMillis();
    }

    private String normalizeCreateStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return STATUS_PENDING;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of(STATUS_PENDING, STATUS_VERIFIED, STATUS_REJECTED).contains(normalized)) {
            throw new BizException("Unsupported proof status: " + status);
        }
        return normalized;
    }

    private String normalizeOptionalStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of(STATUS_PENDING, STATUS_VERIFIED, STATUS_REJECTED, STATUS_ARCHIVED).contains(normalized)) {
            throw new BizException("Unsupported proof status: " + status);
        }
        return normalized;
    }

    private String normalizeRequiredToken(String fieldName, String value, int maxLength) {
        validateRequiredText(fieldName, value, maxLength);
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeOptionalToken(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String normalizedOptionalText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean containsControlCharacters(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\t') >= 0;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
