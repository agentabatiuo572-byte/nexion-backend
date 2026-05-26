package ffdd.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.compliance.domain.KycProfile;
import ffdd.compliance.dto.KycExpiryResult;
import ffdd.compliance.dto.KycProfileReviewRequest;
import ffdd.compliance.dto.KycProfileSubmitRequest;
import ffdd.compliance.mapper.KycProfileMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class KycProfileServiceTest {
    private final KycProfileMapper kycProfileMapper = mock(KycProfileMapper.class);
    private final KycProfileService service = new KycProfileService(kycProfileMapper);

    @Test
    void submitsNewPendingKycProfileWithSafeDocumentMetadata() {
        when(kycProfileMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            KycProfile profile = invocation.getArgument(0);
            profile.setId(501L);
            return 1;
        }).when(kycProfileMapper).insert(any(KycProfile.class));

        KycProfile result = service.submit(submitRequest());

        assertThat(result.getId()).isEqualTo(501L);
        assertThat(result.getUserId()).isEqualTo(10001L);
        assertThat(result.getKycNo()).startsWith("KYC-10001-");
        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getCountry()).isEqualTo("US");
        assertThat(result.getDocumentType()).isEqualTo("PASSPORT");
        assertThat(result.getDocumentLast4()).isEqualTo("1234");
        assertThat(result.getDocumentObjectKey()).isEqualTo("kyc/10001/passport-front.jpg");
        assertThat(result.getSubmittedAt()).isNotNull();
        assertThat(result.getReviewedBy()).isNull();
        assertThat(result.getRejectReason()).isNull();
    }

    @Test
    void resubmitsExistingRejectedProfileAndClearsReviewState() {
        KycProfile existing = existingProfile("REJECTED");
        existing.setRejectReason("blurred document");
        existing.setReviewedBy("ops-1");
        existing.setReviewedAt(LocalDateTime.now().minusDays(1));
        when(kycProfileMapper.selectOne(any())).thenReturn(existing);

        KycProfile result = service.submit(submitRequest());

        assertThat(result.getId()).isEqualTo(88L);
        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getRejectReason()).isNull();
        assertThat(result.getReviewedBy()).isNull();
        assertThat(result.getReviewedAt()).isNull();
        verify(kycProfileMapper).update(any(), any());
    }

    @Test
    void approvesProfileWithReviewerAndOptionalExpiry() {
        KycProfile existing = existingProfile("PENDING");
        when(kycProfileMapper.selectOne(any())).thenReturn(existing);
        KycProfileReviewRequest request = reviewRequest("ops-1", "verified");
        request.setExpiresAt(LocalDateTime.now().plusYears(1));

        KycProfile result = service.approve(10001L, request);

        assertThat(result.getStatus()).isEqualTo("APPROVED");
        assertThat(result.getReviewedBy()).isEqualTo("ops-1");
        assertThat(result.getReviewedAt()).isNotNull();
        assertThat(result.getRiskNotes()).isEqualTo("verified");
        assertThat(result.getRejectReason()).isNull();
        assertThat(result.getExpiresAt()).isEqualTo(request.getExpiresAt());
        verify(kycProfileMapper).update(any(), any());
    }

    @Test
    void rejectsProfileWithRequiredReason() {
        KycProfile existing = existingProfile("PENDING");
        when(kycProfileMapper.selectOne(any())).thenReturn(existing);

        KycProfile result = service.reject(10001L, reviewRequest("ops-2", "document mismatch"));

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        assertThat(result.getReviewedBy()).isEqualTo("ops-2");
        assertThat(result.getRejectReason()).isEqualTo("document mismatch");
        assertThat(result.getExpiresAt()).isNull();
        verify(kycProfileMapper).update(any(), any());
    }

    @Test
    void rejectsInvalidReviewReason() {
        KycProfileReviewRequest request = reviewRequest("ops-2", " ");

        assertThatThrownBy(() -> service.reject(10001L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Review reason is required");
    }

    @Test
    void expiresApprovedProfilesPastExpiry() {
        KycProfile expired = existingProfile("APPROVED");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(kycProfileMapper.selectList(any())).thenReturn(List.of(expired));
        when(kycProfileMapper.update(any(), any())).thenReturn(1);

        KycExpiryResult result = service.expireApprovedProfiles(20, "system-kyc-expiry");

        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getExpired()).isEqualTo(1);
        assertThat(result.getSkipped()).isZero();
        assertThat(result.getUserIds()).containsExactly(10001L);
        assertThat(expired.getStatus()).isEqualTo("EXPIRED");
        assertThat(expired.getRejectReason()).isEqualTo("KYC approval expired");
    }

    private KycProfileSubmitRequest submitRequest() {
        KycProfileSubmitRequest request = new KycProfileSubmitRequest();
        request.setUserId(10001L);
        request.setCountry("US");
        request.setApplicantName("Test User");
        request.setDocumentType("PASSPORT");
        request.setDocumentLast4("1234");
        request.setDocumentObjectKey("kyc/10001/passport-front.jpg");
        return request;
    }

    private KycProfileReviewRequest reviewRequest(String reviewer, String reason) {
        KycProfileReviewRequest request = new KycProfileReviewRequest();
        request.setReviewer(reviewer);
        request.setReason(reason);
        return request;
    }

    private KycProfile existingProfile(String status) {
        KycProfile profile = new KycProfile();
        profile.setId(88L);
        profile.setUserId(10001L);
        profile.setKycNo("KYC-10001");
        profile.setStatus(status);
        profile.setCountry("US");
        profile.setDocumentObjectKey("kyc/10001/old.jpg");
        profile.setIsDeleted(0);
        return profile;
    }
}
