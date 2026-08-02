package ffdd.opsconsole.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppReferralCodeControllerTest {
    private final UserOpsMapper users = mock(UserOpsMapper.class);
    private final AppReferralCodeController controller = new AppReferralCodeController(users);

    @Test
    void unauthenticatedRequestIsRejectedWithoutLookup() {
        ApiResult<?> result = controller.current(null);

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMessage()).isEqualTo("USER_AUTH_REQUIRED");
        verifyNoInteractions(users);
    }

    @Test
    void userTokenCanReadOnlyItsOwnMinimalServerGeneratedCode() {
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setReferralCode("NXABC123DEF4");
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        user.setPhone("secret-phone-must-not-leak");
        user.setPasswordHash("secret-hash-must-not-leak");
        when(users.selectById(42L)).thenReturn(user);
        var auth = userAuth("42");

        ApiResult<Map<String, String>> result = controller.current(auth);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsExactly(Map.entry("referralCode", "NXABC123DEF4"));
    }

    @Test
    void adminTokenAndMalformedPrincipalCannotChooseAnotherUsersCode() {
        var admin = new UsernamePasswordAuthenticationToken("42", "n/a", List.of());
        admin.setDetails(Map.of("subjectType", "ADMIN"));
        var malformed = userAuth("42 OR 1=1");

        assertThat(controller.current(admin).getCode()).isEqualTo(401);
        assertThat(controller.current(malformed).getCode()).isEqualTo(401);
        verifyNoInteractions(users);
    }

    @Test
    void inactiveDeletedAndMissingUsersFailClosed() {
        UserEntity inactive = activeUser("NXINACTIVE1");
        inactive.setStatus("SUSPENDED");
        UserEntity deleted = activeUser("NXDELETED12");
        deleted.setIsDeleted(1);
        when(users.selectById(43L)).thenReturn(inactive);
        when(users.selectById(44L)).thenReturn(deleted);
        when(users.selectById(45L)).thenReturn(null);

        assertThat(controller.current(userAuth("43")).getCode()).isEqualTo(401);
        assertThat(controller.current(userAuth("44")).getCode()).isEqualTo(401);
        assertThat(controller.current(userAuth("45")).getCode()).isEqualTo(401);
    }

    @Test
    void missingOrBlankCodeDoesNotBecomeShareable() {
        UserEntity missing = activeUser(null);
        UserEntity blank = activeUser("  ");
        when(users.selectById(46L)).thenReturn(missing);
        when(users.selectById(47L)).thenReturn(blank);

        ApiResult<?> missingResult = controller.current(userAuth("46"));
        ApiResult<?> blankResult = controller.current(userAuth("47"));

        assertThat(missingResult.getCode()).isEqualTo(503);
        assertThat(missingResult.getMessage()).isEqualTo("REFERRAL_CODE_UNAVAILABLE");
        assertThat(blankResult.getCode()).isEqualTo(503);
        assertThat(blankResult.getMessage()).isEqualTo("REFERRAL_CODE_UNAVAILABLE");
    }

    private UserEntity activeUser(String referralCode) {
        UserEntity user = new UserEntity();
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        user.setReferralCode(referralCode);
        return user;
    }

    private UsernamePasswordAuthenticationToken userAuth(String principal) {
        var auth = new UsernamePasswordAuthenticationToken(principal, "n/a", List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        return auth;
    }
}
