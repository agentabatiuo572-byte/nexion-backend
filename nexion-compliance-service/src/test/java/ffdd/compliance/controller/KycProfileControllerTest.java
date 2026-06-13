package ffdd.compliance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.audit.AuditLogService;
import ffdd.compliance.domain.KycProfile;
import ffdd.compliance.dto.KycProfileSubmitRequest;
import ffdd.compliance.service.KycProfileService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class KycProfileControllerTest {
    private final KycProfileService service = mock(KycProfileService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final KycProfileController controller = new KycProfileController(service, auditLogService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void appKycProfileReadsCurrentRoleUserOnly() {
        authenticateUser("10001");
        KycProfile profile = new KycProfile();
        profile.setUserId(10001L);
        profile.setStatus("PENDING");
        when(service.getByUserId(10001L)).thenReturn(profile);

        KycProfile response = controller.getMyKycProfile().getData();

        assertThat(response.getUserId()).isEqualTo(10001L);
        verify(service).getByUserId(10001L);
    }

    @Test
    void appKycSubmitOverridesSpoofedRequestUserId() {
        authenticateUser("10001");
        KycProfileSubmitRequest request = new KycProfileSubmitRequest();
        request.setUserId(99999L);
        request.setCountry("US");
        request.setDocumentType("PASSPORT");
        request.setDocumentObjectKey("compliance/kyc/10001/passport.png");
        KycProfile profile = new KycProfile();
        profile.setUserId(10001L);
        profile.setStatus("PENDING");
        when(service.submit(request)).thenReturn(profile);

        KycProfile response = controller.submitMyKycProfile(request).getData();

        assertThat(request.getUserId()).isEqualTo(10001L);
        assertThat(response.getUserId()).isEqualTo(10001L);
        verify(service).submit(request);
    }

    private void authenticateUser(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
