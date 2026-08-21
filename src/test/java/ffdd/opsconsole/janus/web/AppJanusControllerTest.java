package ffdd.opsconsole.janus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.janus.application.OpsJanusService;
import ffdd.opsconsole.janus.application.JanusExecutorClaimVerifier;
import ffdd.opsconsole.janus.application.JanusCommandLeaseService;
import ffdd.opsconsole.janus.dto.JanusCommandAckRequest;
import ffdd.opsconsole.janus.dto.JanusDeviceReportRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppJanusControllerTest {
    private final OpsJanusService service = mock(OpsJanusService.class);
    private final JanusExecutorClaimVerifier verifier = mock(JanusExecutorClaimVerifier.class);
    private final JanusCommandLeaseService leaseService = mock(JanusCommandLeaseService.class);
    private final AppJanusController controller = new AppJanusController(service, verifier, leaseService);

    @Test
    void adminTokenCannotImpersonateDeviceReportingUser() {
        var auth = new UsernamePasswordAuthenticationToken("1", "n/a", List.of());
        auth.setDetails(Map.of("subjectType", "ADMIN"));

        ApiResult<Map<String, Object>> result = controller.pending("D-1", "executor-1", "D-1",
                "a".repeat(32), System.currentTimeMillis(), "b".repeat(64),
                AppJanusController.bodyDigest(null), null, auth);

        assertThat(result.getCode()).isEqualTo(403);
    }

    @Test
    void userTokenBindsAckToAuthenticatedUserId() {
        var auth = new UsernamePasswordAuthenticationToken("42", "n/a", List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        JanusCommandAckRequest request = new JanusCommandAckRequest("D-1", 2L, "device-status:2", "e".repeat(64), 1L,
                true, "HIT", "ok", "device-receipt", "device-status:2", 2L, 1L,
                2L,"app-1","approved",2,9L,"PRODUCTION","executor-1","a".repeat(32),
                1_723_000_000_000L,"a".repeat(64));
        assertThat(AppJanusController.bodyDigest(request))
                .isEqualTo("fa5d4f1d74a310e82c5d14b0fa530161c307a0fdec60017d5310592dc792381c");
        when(service.acknowledgeCommand(eq(42L), eq(request)))
                .thenReturn(ApiResult.ok(Map.of("state", "ACKED")));
        when(verifier.verify(eq(42L), anyClaim())).thenReturn(
                new JanusExecutorClaimVerifier.Verification(true, false, null));
        when(leaseService.verify(eq("D-1"), eq("device-status:2"), eq(2L), eq("executor-1"),
                eq("e".repeat(64)), eq(1L))).thenReturn(new JanusCommandLeaseService.Verification(true, null));

        ApiResult<Map<String, Object>> result = controller.acknowledge(request, "executor-1", "D-1",
                "a".repeat(32), System.currentTimeMillis(), "b".repeat(64),
                AppJanusController.bodyDigest(request), "e".repeat(64), 1L, auth);

        assertThat(result.getCode()).isZero();
        verify(service).acknowledgeCommand(42L, request);
    }

    @Test
    void bodyDigestMismatchIsRejectedBeforeClaimVerification() {
        var auth = new UsernamePasswordAuthenticationToken("42", "n/a", List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        JanusCommandAckRequest request = new JanusCommandAckRequest("D-1", 2L, "device-status:2", "e".repeat(64), 1L,
                true, "HIT", "ok", "device-receipt", "device-status:2", 2L, 1L,
                2L,"app-1","approved",2,9L,"PRODUCTION","executor-1","a".repeat(32),
                System.currentTimeMillis(),"a".repeat(64));

        ApiResult<Map<String, Object>> result = controller.acknowledge(request, "executor-1", "D-1",
                "a".repeat(32), System.currentTimeMillis(), "b".repeat(64),
                "f".repeat(64), "e".repeat(64), 1L, auth);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("JANUS_REQUEST_BODY_DIGEST_MISMATCH");
        verify(verifier, org.mockito.Mockito.never()).verify(eq(42L), anyClaim());
    }

    @Test
    void pendingCommandCarriesADeviceBoundAuthorizationForItsImmutableDigest() {
        var auth = new UsernamePasswordAuthenticationToken("42", "n/a", List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        when(verifier.verify(eq(42L), anyClaim())).thenReturn(
                new JanusExecutorClaimVerifier.Verification(true, false, null));
        when(service.pendingCommand(42L,"D-1")).thenReturn(ApiResult.ok(Map.of(
                "hasCommand",true,"sid","SID-1","commandId","cmd-1","commandVersion",3L,
                "commandType","ACTIVATE","expectedTargetId","approved","expectedTargetVersion",2,
                "expectedTargetCatalogVersion",9L,"remoteTargetUrl","https://approved.example/remote")));
        when(leaseService.claim(eq("D-1"),eq("cmd-1"),eq(3L),eq("executor-1"),eq("a".repeat(32)),eq(null)))
                .thenReturn(new JanusCommandLeaseService.Lease(true,null,"e".repeat(64),1L,
                        System.currentTimeMillis()+60_000));
        when(verifier.authorizeCommand(eq(42L),eq("executor-1"),eq("D-1"),org.mockito.ArgumentMatchers.matches("[a-f0-9]{64}")))
                .thenReturn("f".repeat(64));

        ApiResult<Map<String,Object>> result=controller.pending("D-1","executor-1","D-1","a".repeat(32),
                System.currentTimeMillis(),"b".repeat(64),AppJanusController.bodyDigest(null),null,auth);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("commandAuthorization","f".repeat(64));
    }

    @Test
    void immutableDigestBindsSidAndLeaseExpiry() {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("sid","SID-1");row.put("commandType","ACTIVATE");
        row.put("expectedTargetId","approved");row.put("expectedTargetVersion",2);
        row.put("expectedTargetCatalogVersion",9L);row.put("remoteTargetUrl","https://approved.example/remote");
        var lease=new JanusCommandLeaseService.Lease(true,null,"e".repeat(64),1L,10_000L);
        String digest=AppJanusController.commandDigest(row,"cmd-1",3L,lease);

        row.put("sid","SID-2");
        assertThat(AppJanusController.commandDigest(row,"cmd-1",3L,lease)).isNotEqualTo(digest);
        row.put("sid","SID-1");
        assertThat(AppJanusController.commandDigest(row,"cmd-1",3L,
                new JanusCommandLeaseService.Lease(true,null,"e".repeat(64),1L,10_001L))).isNotEqualTo(digest);
    }

    @Test
    void reportBodyDigestMatchesTheNativeClientCanonicalJson() throws Exception {
        long now=1_723_000_000_000L;String device="device-e2e-fixed";ObjectMapper mapper=new ObjectMapper();
        JanusDeviceReportRequest request=new JanusDeviceReportRequest(
                "executor-"+now+"-"+device,device,now,now,now,null,"official",null,null,null,
                "android/e2e/test","android","e2e","test","native",null,null,null,null,
                mapper.readTree("{\"appOpenCount\":1,\"sessionCount\":1,\"foregroundDurationSeconds\":0,\"repeatStreakDays\":1,\"benchmarkViewed\":false,\"optimizeDone\":false,\"marketViewed\":false,\"walletViewed\":false}"),
                mapper.readTree("{\"isHeadless\":false,\"automationSignalCount\":0,\"fpBlocklistHit\":false,\"screenAnomaly\":false,\"timezoneMismatch\":false,\"languageMismatch\":false}"),
                null,null,null,
                mapper.readTree("{\"sessionId\":\"executor-session-1723000000000-device-e2e-fixed\",\"startedAt\":1723000000000,\"lastSeenAt\":1723000000000,\"foregroundDurationSeconds\":0}"),null);
        assertThat(AppJanusController.bodyDigest(request))
                .isEqualTo("0cde9cd7c772179808955b658394d2e158e1edad3d4706d6a0ddaa4eedc36176");
    }

    private static JanusExecutorClaimVerifier.Claim anyClaim() {
        return org.mockito.ArgumentMatchers.any(JanusExecutorClaimVerifier.Claim.class);
    }
}
