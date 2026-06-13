package ffdd.compliance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.audit.AuditLogService;
import ffdd.compliance.domain.KycProfile;
import ffdd.compliance.domain.ProofAsset;
import ffdd.compliance.dto.KycDocumentUploadRequest;
import ffdd.compliance.dto.ProofAssetUploadRequest;
import ffdd.compliance.service.ComplianceEvidenceService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ComplianceEvidenceControllerTest {
    private final ComplianceEvidenceService service = mock(ComplianceEvidenceService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ComplianceEvidenceController controller = new ComplianceEvidenceController(service, auditLogService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void appKycDocumentUploadOverridesSpoofedRequestUserId() {
        authenticateUser("10001");
        KycDocumentUploadRequest request = new KycDocumentUploadRequest();
        request.setUserId(99999L);
        request.setCountry("US");
        request.setDocumentType("PASSPORT");
        MockMultipartFile file = new MockMultipartFile("file", "passport.png", "image/png", "image".getBytes());
        KycProfile profile = new KycProfile();
        profile.setKycNo("KYC-10001");
        profile.setUserId(10001L);
        profile.setStatus("PENDING");
        when(service.uploadKycDocument(request, file)).thenReturn(profile);

        KycProfile response = controller.uploadMyKycDocument(request, file).getData();

        assertThat(request.getUserId()).isEqualTo(10001L);
        assertThat(response.getUserId()).isEqualTo(10001L);
        verify(service).uploadKycDocument(request, file);
    }

    @Test
    void appProofAssetUploadOverridesSpoofedRequestUserId() {
        authenticateUser("10001");
        ProofAssetUploadRequest request = new ProofAssetUploadRequest();
        request.setUserId(99999L);
        request.setProofType("earnings");
        request.setRelatedBizType("SHARE_POSTER");
        MockMultipartFile file = new MockMultipartFile("file", "proof.png", "image/png", "image".getBytes());
        ProofAsset proof = new ProofAsset();
        proof.setProofNo("PROOF-10001-1");
        proof.setUserId(10001L);
        proof.setProofType("EARNINGS");
        proof.setStatus("PENDING");
        when(service.uploadProofAsset(request, file)).thenReturn(proof);

        ProofAsset response = controller.uploadMyProofAsset(request, file).getData();

        assertThat(request.getUserId()).isEqualTo(10001L);
        assertThat(response.getUserId()).isEqualTo(10001L);
        verify(service).uploadProofAsset(request, file);
    }

    private void authenticateUser(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
