package ffdd.compliance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.audit.AuditLogService;
import ffdd.compliance.domain.ProofAsset;
import ffdd.compliance.service.ProofAssetService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ProofAssetControllerTest {
    private final ProofAssetService service = mock(ProofAssetService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ProofAssetController controller = new ProofAssetController(service, auditLogService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void appProofAssetsReadsCurrentRoleUserOnly() {
        authenticateUser("10001");
        ProofAsset proof = new ProofAsset();
        proof.setUserId(10001L);
        proof.setProofNo("PROOF-10001-1");
        proof.setProofType("EARNINGS");
        proof.setStatus("PENDING");
        when(service.list(10001L, "EARNINGS", "PENDING", 12)).thenReturn(List.of(proof));

        List<ProofAsset> response = controller.listMine("EARNINGS", "PENDING", 12).getData();

        assertThat(response).hasSize(1);
        assertThat(response.get(0).getUserId()).isEqualTo(10001L);
        verify(service).list(10001L, "EARNINGS", "PENDING", 12);
    }

    private void authenticateUser(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
