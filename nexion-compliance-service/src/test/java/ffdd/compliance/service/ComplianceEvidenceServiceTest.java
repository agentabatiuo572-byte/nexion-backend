package ffdd.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.common.storage.ObjectStorageService;
import ffdd.common.storage.StoredObject;
import ffdd.compliance.domain.KycProfile;
import ffdd.compliance.domain.ProofAsset;
import ffdd.compliance.dto.EvidenceUploadPolicyRequest;
import ffdd.compliance.dto.EvidenceUploadPolicyResponse;
import ffdd.compliance.dto.KycDocumentUploadRequest;
import ffdd.compliance.dto.KycProfileSubmitRequest;
import ffdd.compliance.dto.ProofAssetCreateRequest;
import ffdd.compliance.dto.ProofAssetUploadRequest;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class ComplianceEvidenceServiceTest {
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final KycProfileService kycProfileService = mock(KycProfileService.class);
    private final ProofAssetService proofAssetService = mock(ProofAssetService.class);
    private final ComplianceEvidenceService service =
            new ComplianceEvidenceService(storageService, kycProfileService, proofAssetService, 10_000L, 900);

    @Test
    void uploadsKycDocumentAndSubmitsProfileWithStorageKeyOnly() {
        when(storageService.put(any(), any(), any(InputStream.class), anyLong()))
                .thenAnswer(invocation -> new StoredObject(
                        "nexion",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(3)));
        when(kycProfileService.submit(any())).thenAnswer(invocation -> {
            KycProfileSubmitRequest submit = invocation.getArgument(0);
            KycProfile profile = new KycProfile();
            profile.setUserId(submit.getUserId());
            profile.setCountry(submit.getCountry());
            profile.setDocumentType(submit.getDocumentType());
            profile.setDocumentObjectKey(submit.getDocumentObjectKey());
            profile.setStatus("PENDING");
            return profile;
        });

        KycProfile result = service.uploadKycDocument(kycRequest(), file("passport.png", "image/png", "image-bytes"));

        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getDocumentObjectKey()).startsWith("compliance/kyc/10001/passport/");
        assertThat(result.getDocumentObjectKey()).endsWith(".png");
        verify(kycProfileService).submit(any(KycProfileSubmitRequest.class));
    }

    @Test
    void uploadsProofAssetWithSha256Checksum() {
        when(storageService.put(any(), any(), any(InputStream.class), anyLong()))
                .thenAnswer(invocation -> new StoredObject(
                        "nexion",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(3)));
        when(proofAssetService.create(any())).thenAnswer(invocation -> {
            ProofAssetCreateRequest create = invocation.getArgument(0);
            ProofAsset proof = new ProofAsset();
            proof.setUserId(create.getUserId());
            proof.setProofType(create.getProofType().toUpperCase());
            proof.setObjectKey(create.getObjectKey());
            proof.setChecksum(create.getChecksum());
            proof.setSizeBytes(create.getSizeBytes());
            proof.setStatus("PENDING");
            return proof;
        });

        ProofAsset result = service.uploadProofAsset(
                proofRequest(), file("task-1001.json", "application/json", "{\"ok\":true}"));

        assertThat(result.getObjectKey()).startsWith("compliance/proofs/10001/compute_receipt/");
        assertThat(result.getChecksum()).startsWith("sha256:");
        assertThat(result.getSizeBytes()).isEqualTo(11L);
        ArgumentCaptor<ProofAssetCreateRequest> createCaptor = ArgumentCaptor.forClass(ProofAssetCreateRequest.class);
        verify(proofAssetService).create(createCaptor.capture());
        assertThat(createCaptor.getValue().getMetadataJson())
                .contains("\"variant\":\"earnings\"")
                .contains("\"totalEarnings\":12.340000")
                .contains("\"currentStreak\":3")
                .contains("\"receiptNo\":\"RCPT-1001\"");
    }

    @Test
    void uploadsProofAssetVideoThroughStorage() {
        when(storageService.put(any(), any(), any(InputStream.class), anyLong()))
                .thenAnswer(invocation -> new StoredObject(
                        "nexion",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(3)));
        when(proofAssetService.create(any())).thenAnswer(invocation -> {
            ProofAssetCreateRequest create = invocation.getArgument(0);
            ProofAsset proof = new ProofAsset();
            proof.setUserId(create.getUserId());
            proof.setObjectKey(create.getObjectKey());
            proof.setContentType(create.getContentType());
            proof.setSizeBytes(create.getSizeBytes());
            proof.setStatus("PENDING");
            return proof;
        });

        ProofAsset result = service.uploadProofAsset(
                proofRequest(), file("proof.mp4", "video/mp4", "video-bytes"));

        assertThat(result.getObjectKey()).startsWith("compliance/proofs/10001/compute_receipt/");
        assertThat(result.getObjectKey()).endsWith(".mp4");
        assertThat(result.getContentType()).isEqualTo("video/mp4");
    }

    @Test
    void rejectsUnsupportedUploadContentType() {
        assertThatThrownBy(() -> service.uploadProofAsset(
                        proofRequest(), file("task.exe", "application/octet-stream", "bytes")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Unsupported evidence content type");
    }

    @Test
    void rejectsContentTypeAndExtensionMismatch() {
        assertThatThrownBy(() -> service.uploadKycDocument(
                        kycRequest(), file("passport.exe", "image/png", "image-bytes")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Unsupported evidence file extension");
    }

    @Test
    void createsPresignedUploadPolicyWithGeneratedObjectKey() {
        when(storageService.presignPut(any(), any(), any())).thenReturn("http://minio.local/upload");

        EvidenceUploadPolicyRequest request = new EvidenceUploadPolicyRequest();
        request.setUserId(10001L);
        request.setEvidenceType("kyc_document");
        request.setFileName("passport.pdf");
        request.setContentType("application/pdf");
        request.setSizeBytes(4096L);

        EvidenceUploadPolicyResponse response = service.createUploadPolicy(request);

        assertThat(response.getObjectKey()).startsWith("compliance/kyc/10001/kyc_document/");
        assertThat(response.getObjectKey()).endsWith(".pdf");
        assertThat(response.getUploadUrl()).isEqualTo("http://minio.local/upload");
        assertThat(response.getExpiresInSeconds()).isEqualTo(900);
    }

    private KycDocumentUploadRequest kycRequest() {
        KycDocumentUploadRequest request = new KycDocumentUploadRequest();
        request.setUserId(10001L);
        request.setCountry("US");
        request.setApplicantName("Test User");
        request.setDocumentType("passport");
        request.setDocumentLast4("1234");
        return request;
    }

    private ProofAssetUploadRequest proofRequest() {
        ProofAssetUploadRequest request = new ProofAssetUploadRequest();
        request.setUserId(10001L);
        request.setProofType("compute_receipt");
        request.setRelatedBizType("COMPUTE_TASK");
        request.setRelatedBizNo("TASK-1001");
        request.setSubmittedBy("worker-1");
        request.setMetadataVariant("earnings");
        request.setTotalEarnings(new BigDecimal("12.340000"));
        request.setCurrentStreak(3);
        request.setReceiptNo("RCPT-1001");
        return request;
    }

    private MockMultipartFile file(String fileName, String contentType, String body) {
        return new MockMultipartFile(
                "file", fileName, contentType, body.getBytes(StandardCharsets.UTF_8));
    }
}
