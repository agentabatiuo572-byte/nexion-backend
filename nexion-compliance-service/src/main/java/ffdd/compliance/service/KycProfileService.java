package ffdd.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import ffdd.common.exception.BizException;
import ffdd.compliance.domain.KycProfile;
import ffdd.compliance.dto.KycProfileReviewRequest;
import ffdd.compliance.dto.KycProfileSubmitRequest;
import ffdd.compliance.mapper.KycProfileMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KycProfileService {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final KycProfileMapper kycProfileMapper;

    public KycProfileService(KycProfileMapper kycProfileMapper) {
        this.kycProfileMapper = kycProfileMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public KycProfile submit(KycProfileSubmitRequest request) {
        validateSubmit(request);
        KycProfile existing = findActiveByUserId(request.getUserId());
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            return resubmit(existing, request, now);
        }

        KycProfile profile = new KycProfile();
        profile.setUserId(request.getUserId());
        profile.setKycNo(normalizeKycNo(request.getKycNo(), request.getUserId()));
        applySubmission(profile, request, now);
        profile.setIsDeleted(0);
        try {
            kycProfileMapper.insert(profile);
            return profile;
        } catch (DuplicateKeyException ex) {
            KycProfile duplicate = findActiveByUserId(request.getUserId());
            if (duplicate == null) {
                throw new BizException("Duplicate KYC profile exists in an invalid state");
            }
            return resubmit(duplicate, request, now);
        }
    }

    public KycProfile getByUserId(Long userId) {
        validateUserId(userId);
        return findActiveByUserId(userId);
    }

    public List<KycProfile> list(Long userId, String status, int limit) {
        String normalizedStatus = normalizeOptionalStatus(status);
        LambdaQueryWrapper<KycProfile> wrapper = new LambdaQueryWrapper<KycProfile>()
                .eq(KycProfile::getIsDeleted, 0)
                .eq(userId != null, KycProfile::getUserId, userId)
                .eq(StringUtils.hasText(normalizedStatus), KycProfile::getStatus, normalizedStatus)
                .orderByDesc(KycProfile::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit));
        return kycProfileMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public KycProfile approve(Long userId, KycProfileReviewRequest request) {
        validateUserId(userId);
        validateReview(request, false, true);
        KycProfile profile = requireActiveByUserId(userId);
        LocalDateTime reviewedAt = LocalDateTime.now();

        UpdateWrapper<KycProfile> wrapper = new UpdateWrapper<KycProfile>()
                .eq("id", profile.getId())
                .set("status", STATUS_APPROVED)
                .set("reviewed_by", request.getReviewer().trim())
                .set("reviewed_at", reviewedAt)
                .set("reject_reason", null)
                .set("expires_at", request.getExpiresAt())
                .set("risk_notes", normalizedOptionalText(request.getReason()));
        kycProfileMapper.update(null, wrapper);

        profile.setStatus(STATUS_APPROVED);
        profile.setReviewedBy(request.getReviewer().trim());
        profile.setReviewedAt(reviewedAt);
        profile.setRejectReason(null);
        profile.setExpiresAt(request.getExpiresAt());
        profile.setRiskNotes(normalizedOptionalText(request.getReason()));
        return profile;
    }

    @Transactional(rollbackFor = Exception.class)
    public KycProfile reject(Long userId, KycProfileReviewRequest request) {
        validateUserId(userId);
        validateReview(request, true, false);
        KycProfile profile = requireActiveByUserId(userId);
        LocalDateTime reviewedAt = LocalDateTime.now();
        String reason = request.getReason().trim();

        UpdateWrapper<KycProfile> wrapper = new UpdateWrapper<KycProfile>()
                .eq("id", profile.getId())
                .set("status", STATUS_REJECTED)
                .set("reviewed_by", request.getReviewer().trim())
                .set("reviewed_at", reviewedAt)
                .set("reject_reason", reason)
                .set("expires_at", null)
                .set("risk_notes", reason);
        kycProfileMapper.update(null, wrapper);

        profile.setStatus(STATUS_REJECTED);
        profile.setReviewedBy(request.getReviewer().trim());
        profile.setReviewedAt(reviewedAt);
        profile.setRejectReason(reason);
        profile.setExpiresAt(null);
        profile.setRiskNotes(reason);
        return profile;
    }

    @Transactional(rollbackFor = Exception.class)
    public KycProfile expire(Long userId, KycProfileReviewRequest request) {
        validateUserId(userId);
        validateReview(request, true, false);
        KycProfile profile = requireActiveByUserId(userId);
        LocalDateTime reviewedAt = LocalDateTime.now();
        String reason = request.getReason().trim();

        UpdateWrapper<KycProfile> wrapper = new UpdateWrapper<KycProfile>()
                .eq("id", profile.getId())
                .set("status", STATUS_EXPIRED)
                .set("reviewed_by", request.getReviewer().trim())
                .set("reviewed_at", reviewedAt)
                .set("reject_reason", reason)
                .set("expires_at", null)
                .set("risk_notes", reason);
        kycProfileMapper.update(null, wrapper);

        profile.setStatus(STATUS_EXPIRED);
        profile.setReviewedBy(request.getReviewer().trim());
        profile.setReviewedAt(reviewedAt);
        profile.setRejectReason(reason);
        profile.setExpiresAt(null);
        profile.setRiskNotes(reason);
        return profile;
    }

    private KycProfile resubmit(KycProfile profile, KycProfileSubmitRequest request, LocalDateTime submittedAt) {
        applySubmission(profile, request, submittedAt);
        UpdateWrapper<KycProfile> wrapper = new UpdateWrapper<KycProfile>()
                .eq("id", profile.getId())
                .set("kyc_no", profile.getKycNo())
                .set("status", STATUS_PENDING)
                .set("country", profile.getCountry())
                .set("applicant_name", profile.getApplicantName())
                .set("document_type", profile.getDocumentType())
                .set("document_last4", profile.getDocumentLast4())
                .set("document_object_key", profile.getDocumentObjectKey())
                .set("submitted_at", submittedAt)
                .set("reviewed_by", null)
                .set("reviewed_at", null)
                .set("reject_reason", null)
                .set("expires_at", null)
                .set("risk_notes", null)
                .set("is_deleted", 0);
        kycProfileMapper.update(null, wrapper);
        return profile;
    }

    private void applySubmission(KycProfile profile, KycProfileSubmitRequest request, LocalDateTime submittedAt) {
        profile.setStatus(STATUS_PENDING);
        profile.setCountry(request.getCountry().trim().toUpperCase(Locale.ROOT));
        profile.setApplicantName(normalizedOptionalText(request.getApplicantName()));
        profile.setDocumentType(request.getDocumentType().trim().toUpperCase(Locale.ROOT));
        profile.setDocumentLast4(normalizedOptionalText(request.getDocumentLast4()));
        profile.setDocumentObjectKey(request.getDocumentObjectKey().trim());
        profile.setSubmittedAt(submittedAt);
        profile.setReviewedBy(null);
        profile.setReviewedAt(null);
        profile.setRejectReason(null);
        profile.setExpiresAt(null);
        profile.setRiskNotes(null);
        if (!StringUtils.hasText(profile.getKycNo())) {
            profile.setKycNo(normalizeKycNo(request.getKycNo(), request.getUserId()));
        }
    }

    private KycProfile requireActiveByUserId(Long userId) {
        KycProfile profile = findActiveByUserId(userId);
        if (profile == null) {
            throw new BizException("KYC profile not found");
        }
        return profile;
    }

    private KycProfile findActiveByUserId(Long userId) {
        return kycProfileMapper.selectOne(new LambdaQueryWrapper<KycProfile>()
                .eq(KycProfile::getUserId, userId)
                .eq(KycProfile::getIsDeleted, 0));
    }

    private void validateSubmit(KycProfileSubmitRequest request) {
        if (request == null) {
            throw new BizException("KYC submit request is required");
        }
        validateUserId(request.getUserId());
        validateOptionalToken("KYC no", request.getKycNo(), 96);
        validateRequiredText("Country", request.getCountry(), 64);
        validateOptionalToken("Applicant name", request.getApplicantName(), 128);
        validateRequiredText("Document type", request.getDocumentType(), 64);
        validateOptionalToken("Document last4", request.getDocumentLast4(), 16);
        validateStorageKey("Document object key", request.getDocumentObjectKey(), 255);
    }

    private void validateReview(KycProfileReviewRequest request, boolean requireReason, boolean validateExpiry) {
        if (request == null) {
            throw new BizException("KYC review request is required");
        }
        validateRequiredText("Reviewer", request.getReviewer(), 64);
        if (requireReason) {
            validateRequiredText("Review reason", request.getReason(), 255);
        } else {
            validateOptionalToken("Review reason", request.getReason(), 255);
        }
        if (validateExpiry && request.getExpiresAt() != null && !request.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BizException("KYC expiry must be in the future");
        }
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
        if (StringUtils.hasText(value) && value.length() > maxLength) {
            throw new BizException(fieldName + " is too long");
        }
        if (StringUtils.hasText(value) && containsControlCharacters(value)) {
            throw new BizException(fieldName + " contains invalid characters");
        }
    }

    private void validateStorageKey(String fieldName, String value, int maxLength) {
        validateRequiredText(fieldName, value, maxLength);
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.contains("..")) {
            throw new BizException(fieldName + " must be a storage object key");
        }
    }

    private String normalizeKycNo(String requestedKycNo, Long userId) {
        if (StringUtils.hasText(requestedKycNo)) {
            return requestedKycNo.trim().toUpperCase(Locale.ROOT);
        }
        return "KYC-" + userId + "-" + System.currentTimeMillis();
    }

    private String normalizeOptionalStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of(STATUS_PENDING, STATUS_APPROVED, STATUS_REJECTED, STATUS_EXPIRED).contains(normalized)) {
            throw new BizException("Unsupported KYC status: " + status);
        }
        return normalized;
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
