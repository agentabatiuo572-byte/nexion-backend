package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.team.dto.VRankBootstrapRequest;
import ffdd.opsconsole.platform.application.AuditReplayBusinessPermissionGuard;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class VRankBootstrapContractTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void missingNumbersFractionalPointsNoGatesAndFinancialRewardsAreRejected() {
        var params=DataBootstrapMySqlIntegrationTest.params();
        assertThat(VRankBootstrapRequest.from(params).ranks()).hasSize(9);
        var row=rows(params).get(1);
        row.remove("directRefs");assertThatThrownBy(()->VRankBootstrapRequest.from(params)).hasMessage("VRANK_BOOTSTRAP_INVALID");
        row.put("directRefs",0);row.put("selfComputePoints",1.5);
        assertThatThrownBy(()->VRankBootstrapRequest.from(params)).hasMessage("VRANK_BOOTSTRAP_INVALID");
        row.put("selfComputePoints",0);
        assertThatThrownBy(()->VRankBootstrapRequest.from(params)).hasMessage("VRANK_BOOTSTRAP_INVALID");
        row.put("selfComputePoints",100);row.put("rewards",List.of(Map.of("type","usdt","amount",100)));
        assertThatThrownBy(()->VRankBootstrapRequest.from(params)).hasMessage("VRANK_BOOTSTRAP_USE_EXISTING_REWARD_EDITOR");
    }

    @Test void a2UsesExactF1PermissionCanonicalLockAndFullServerGeneratedReview() {
        var guard=new AuditReplayBusinessPermissionGuard(mock(ffdd.opsconsole.content.domain.TrustDisclosureRepository.class),
                mock(ffdd.opsconsole.shared.security.AdminOperatorRoleResolver.class),mock(ffdd.opsconsole.emergency.domain.EmergencyControlRepository.class),
                null,mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class));
        var command=new AuditReplayCommand("F","f_vrank_bootstrap",DataBootstrapMySqlIntegrationTest.params());
        authenticate("platform_a2_proposal_create","network_f2_write");
        assertThat(guard.validateProposal(command).getCode()).isEqualTo(403);
        authenticate("platform_a2_proposal_create","network_f1_write");
        assertThat(guard.validateProposal(command).getCode()).isZero();
        var request=new AuditOperationProposalRequest("client copy","current","client","client","maker","operator","param",false,false,
                "client gate","initialize configured ranks","F1",command,new AuditLockTarget("F","vrank_bootstrap","current"),null);
        var descriptor=guard.validateProposalContext(request).getData();
        assertThat(descriptor.amplifies()).isTrue();assertThat(descriptor.operationType()).isEqualTo("fund");
        assertThat(descriptor.afterValue()).contains("V0","V8","selfComputePoints","peerBonusRate").hasSizeGreaterThan(1024);
        assertThat(descriptor.target()).isEqualTo(new AuditLockTarget("F","vrank_bootstrap","current"));
        authenticate("platform_a2_operation_approve","network_f2_write");
        assertThat(guard.validateApproval(command).getCode()).isEqualTo(403);
    }

    @Test void forwardMigrationsAreInStartupAndNexOnlyConstraintRebuildIsRetired() throws Exception {
        String runner=Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(runner).contains("20261101_uvt_legacy_asset_migration.sql","20261101_vrank_bootstrap.sql")
                .doesNotContain("(Join-Path $root \"scripts\\migrations\\20261025_promotion_order_discount.sql\")");
        assertThat(Files.readString(Path.of("scripts/migrations/20261101_vrank_bootstrap.sql")))
                .doesNotContain("INSERT INTO nx_v_rank_config","UPDATE nx_v_rank_config","nx_team_member");
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> p){return (List<Map<String,Object>>)p.get("ranks");}
    private static void authenticate(String... authorities){
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("checker","n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
