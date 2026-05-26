package ffdd.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.compliance.domain.ProofAsset;
import ffdd.compliance.dto.ProofAssetCreateRequest;
import ffdd.compliance.dto.ProofAssetReviewRequest;
import ffdd.compliance.mapper.ProofAssetMapper;
import org.junit.jupiter.api.Test;

class ProofAssetServiceTest {
    private final ProofAssetMapper proofAssetMapper = mock(ProofAssetMapper.class);
    private final ProofAssetService service = new ProofAssetService(proofAssetMapper);

    @Test
    void createsPendingProofAssetMetadata() {
        doAnswer(invocation -> {
            ProofAsset proof = invocation.getArgument(0);
            proof.setId(601L);
            return 1;
        }).when(proofAssetMapper).insert(any(ProofAsset.class));

        ProofAsset result = service.create(createRequest());

        assertThat(result.getId()).isEqualTo(601L);
        assertThat(result.getProofNo()).startsWith("PROOF-10001-");
        assertThat(result.getUserId()).isEqualTo(10001L);
        assertThat(result.getProofType()).isEqualTo("COMPUTE_RECEIPT");
        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getObjectKey()).isEqualTo("proofs/task-1001.json");
        assertThat(result.getChecksum()).isEqualTo("sha256:abc123");
        assertThat(result.getMetadataJson()).isEqualTo("{\"receiptNo\":\"RCPT-1001\"}");
    }

    @Test
    void verifiesProofAssetWithReviewerMetadata() {
        ProofAsset existing = existingProof("PENDING");
        when(proofAssetMapper.selectOne(any())).thenReturn(existing);

        ProofAsset result = service.verify("PROOF-1", reviewRequest("ops-1", "hash matched"));

        assertThat(result.getStatus()).isEqualTo("VERIFIED");
        assertThat(result.getReviewedBy()).isEqualTo("ops-1");
        assertThat(result.getReviewedAt()).isNotNull();
        assertThat(result.getRejectReason()).isNull();
        assertThat(result.getReviewNote()).isEqualTo("hash matched");
        assertThat(result.getMetadataJson()).isNull();
        verify(proofAssetMapper).update(any(), any());
    }

    @Test
    void rejectsProofAssetWithReason() {
        ProofAsset existing = existingProof("PENDING");
        when(proofAssetMapper.selectOne(any())).thenReturn(existing);

        ProofAsset result = service.reject("PROOF-1", reviewRequest("ops-2", "checksum mismatch"));

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        assertThat(result.getReviewedBy()).isEqualTo("ops-2");
        assertThat(result.getRejectReason()).isEqualTo("checksum mismatch");
        assertThat(result.getReviewNote()).isEqualTo("checksum mismatch");
        verify(proofAssetMapper).update(any(), any());
    }

    @Test
    void rejectsInvalidProofStatusQuery() {
        assertThatThrownBy(() -> service.list(null, null, "done", 20))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Unsupported proof status");
    }

    private ProofAssetCreateRequest createRequest() {
        ProofAssetCreateRequest request = new ProofAssetCreateRequest();
        request.setUserId(10001L);
        request.setProofType("compute_receipt");
        request.setObjectKey("proofs/task-1001.json");
        request.setFileName("task-1001.json");
        request.setContentType("application/json");
        request.setSizeBytes(2048L);
        request.setChecksum("sha256:abc123");
        request.setRelatedBizType("COMPUTE_TASK");
        request.setRelatedBizNo("TASK-1001");
        request.setSubmittedBy("worker-1");
        request.setMetadataJson("{\"receiptNo\":\"RCPT-1001\"}");
        return request;
    }

    private ProofAssetReviewRequest reviewRequest(String reviewer, String reason) {
        ProofAssetReviewRequest request = new ProofAssetReviewRequest();
        request.setReviewer(reviewer);
        request.setReason(reason);
        return request;
    }

    private ProofAsset existingProof(String status) {
        ProofAsset proof = new ProofAsset();
        proof.setId(77L);
        proof.setUserId(10001L);
        proof.setProofNo("PROOF-1");
        proof.setProofType("COMPUTE_RECEIPT");
        proof.setObjectKey("proofs/task-1001.json");
        proof.setStatus(status);
        proof.setIsDeleted(0);
        return proof;
    }
}
