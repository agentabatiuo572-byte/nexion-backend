package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class A2DefaultAvatarCapabilityTest {
    final AuditReplayCommand command=new AuditReplayCommand("A","a1_account_create",Map.of("username","new.advisor","role","support","avatarGender","MALE","useDefaultAvatar",true));
    @BeforeEach void auth(){var auth=new UsernamePasswordAuthenticationToken("2","ignored",List.of(new SimpleGrantedAuthority("platform_a1_write"),new SimpleGrantedAuthority("platform_a2_operation_approve")));auth.setDetails(Map.of("subjectType","ADMIN"));SecurityContextHolder.getContext().setAuthentication(auth);}
    @AfterEach void clear(){A2ReplayContext.exitReplay();SecurityContextHolder.clearContext();}
    @Test void exactFrozenCreateAllowsOnlyItsOnceBoundTargetAndNeverMakerUpload() {
        assertThat(A2ReplayContext.isAvatarCommand(command)).isTrue();
        A2ReplayContext.enterAvatarApproval("ticket",1L,2L,command);A2ReplayContext.requireAvatarCommand(command);A2ReplayContext.bindDefaultAvatarTarget(9L);
        A2ReplayContext.requireDefaultAvatarTarget(2L,9L);
        assertThatThrownBy(()->A2ReplayContext.requireDefaultAvatarTarget(2L,10L)).hasMessage("A2_AVATAR_APPROVAL_REQUIRED");
        assertThatThrownBy(()->A2ReplayContext.approvedAvatarUploader(2L,9L,"asset")).hasMessage("A2_AVATAR_APPROVAL_REQUIRED");
        assertThatThrownBy(()->A2ReplayContext.bindDefaultAvatarTarget(10L)).hasMessage("A2_AVATAR_APPROVAL_REQUIRED");
    }
    @Test void genericReplayFlagCannotAuthorizeDefaultCreation() {
        A2ReplayContext.enterReplay("ticket");assertThatThrownBy(()->A2ReplayContext.bindDefaultAvatarTarget(9L)).hasMessage("A2_AVATAR_APPROVAL_REQUIRED");
        assertThatThrownBy(()->A2ReplayContext.requireDefaultAvatarTarget(2L,9L)).hasMessage("A2_AVATAR_APPROVAL_REQUIRED");
    }
    @Test void approvalRequiresMatchingFrozenDefaultIntent() {
        A2ReplayContext.enterAvatarApproval("ticket",1L,2L,command);
        var altered=new AuditReplayCommand("A","a1_account_create",Map.of("username","new.advisor","role","support","avatarGender","FEMALE","useDefaultAvatar",true));
        assertThatThrownBy(()->A2ReplayContext.requireAvatarCommand(altered)).hasMessage("A2_AVATAR_APPROVAL_REQUIRED");
    }
}
