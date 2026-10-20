package ffdd.opsconsole.user.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.user.application.PhoneReplacementOperationsService;
import ffdd.opsconsole.user.dto.PhoneReplacementCommands.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Executes the real method-security interceptor; service tests separately cover current DB permissions. */
class PhoneReplacementOperationsAuthorizationTest {
    static final String READ=PhoneReplacementOperationsService.READ;
    static final String GRANT=PhoneReplacementOperationsService.GRANT;
    static final String REVOKE=PhoneReplacementOperationsService.REVOKE;
    static final String REASON="Verified account support request";
    @Configuration @EnableMethodSecurity static class Guards { }
    final PhoneReplacementOperationsService service=mock(PhoneReplacementOperationsService.class);
    AnnotationConfigApplicationContext context;
    PhoneReplacementOperationsController controller;
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext();context.register(Guards.class);
        context.registerBean(PhoneReplacementOperationsController.class,()->new PhoneReplacementOperationsController(service));
        context.refresh();controller=context.getBean(PhoneReplacementOperationsController.class);
    }
    @AfterEach void close(){SecurityContextHolder.clearContext();context.close();}
    void authenticate(String... rights) {
        var auth=new UsernamePasswordAuthenticationToken("900","unused",Arrays.stream(rights).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(Map.of("subjectType","ADMIN"));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    Issue issue(){return new Issue("request",4L,true,false,REASON,"evidence",true);}
    Confirm confirm(){return new Confirm(REASON,true);}
    @Test void ordinarySupportAndSessionRevocationRightsCannotGrantCloseRevokeOrRecover() {
        for(String[] rights:List.of(new String[]{READ},new String[]{READ,"user_c5_session_revoke"},new String[]{"ROLE_SUPPORT"})) {
            authenticate(rights);
            for(Runnable call:List.<Runnable>of(
                    ()->controller.verify(71,"request","key",new Verify(REASON,true)),
                    ()->controller.issue(71,"key",issue()),()->controller.close(71,"request","key",confirm()),
                    ()->controller.revoke(71,"grant","key",confirm()),()->controller.operation("PRO-original"),
                    ()->controller.byKey("PHONE_GRANT_ISSUE","original-key")))
                assertThatThrownBy(call::run).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(service);
    }
    @Test void verificationRecoveryRequiresGrantPermissionAndMissingReceiptStaysUnknownWithoutPost() {
        for(String[] rights:List.of(new String[]{READ},new String[]{REVOKE},new String[]{})) {
            authenticate(rights);
            assertThatThrownBy(()->controller.verification(71,"original-key")).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(service);
        authenticate(GRANT);
        var missing=controller.verification(71,"original-key");
        assertThat(missing.getCode()).isEqualTo(404);
        assertThat(missing.getMessage()).isEqualTo("OPERATION_RESULT_UNKNOWN");assertThat(missing.getData()).isNull();
        var original=new PhoneReplacementOperationsService.VerificationView("evidence-original","request-original","support-original",900,1234);
        when(service.verificationByKey(71,"original-key")).thenReturn(original);
        var recovered=controller.verification(71,"original-key");
        assertThat(recovered.getCode()).isZero();assertThat(recovered.getData()).isSameAs(original);
        verify(service,times(2)).verificationByKey(71,"original-key");verifyNoMoreInteractions(service);
    }
    @Test void readPermissionAllowsOnlyTheThreeAccountReadSurfaces() {
        authenticate(READ);
        controller.binding(71);controller.requests(71,1,20);controller.history(71,"SUCCESS",1,20);
        verify(service).binding(71);verify(service).requests(71,1,20);verify(service).history(71,"SUCCESS",1,20);
        verifyNoMoreInteractions(service);
    }
    @Test void grantPermissionAllowsVerificationIssueCloseButNotRevoke() {
        authenticate(GRANT);
        controller.verify(71,"request","verify-key",new Verify(REASON,true));
        controller.issue(71,"issue-key",issue());controller.close(71,"request","close-key",confirm());
        verify(service).verify(71,"request",new Verify(REASON,true),"verify-key");
        verify(service).issue(71,issue(),"issue-key");verify(service).close(71,"request",confirm(),"close-key");
        assertThatThrownBy(()->controller.revoke(71,"grant","key",confirm())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->controller.binding(71)).isInstanceOf(AccessDeniedException.class);
        verify(service,never()).revoke(anyLong(),any(),any(),any());
    }
    @Test void revokePermissionDoesNotAuthorizeGrantOrRequestClose() {
        authenticate(REVOKE);
        controller.revoke(71,"grant","revoke-key",confirm());
        verify(service).revoke(71,"grant",confirm(),"revoke-key");
        assertThatThrownBy(()->controller.issue(71,"key",issue())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->controller.close(71,"request","key",confirm())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->controller.verify(71,"request","key",new Verify(REASON,true))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void policyCheckerNeedsBothRightsForReceiptAndStillCannotRevokeUserGrant() {
        for(String right:List.of("platform_a2_operation_approve","device_e6_read")) {
            authenticate(right);
            assertThatThrownBy(()->controller.operation("PPO-prior")).isInstanceOf(AccessDeniedException.class);
        }
        authenticate("platform_a2_operation_approve","device_e6_read");
        controller.operation("PPO-prior");
        controller.byKey("PHONE_POLICY_PUBLISH","original-key");
        verify(service).operation("PPO-prior");verify(service).operationByKey("PHONE_POLICY_PUBLISH","original-key");
        assertThatThrownBy(()->controller.revoke(71,"grant","key",confirm())).isInstanceOf(AccessDeniedException.class);
        verify(service,never()).revoke(anyLong(),any(),any(),any());
    }
    @Test void unknownReceiptRemainsUnknownAndRecoveryDelegatesCurrentAuthorizationToService() {
        authenticate(GRANT);
        var byId=controller.operation("PRO-missing");var byKey=controller.byKey("PHONE_GRANT_ISSUE","original-key");
        assertThat(byId.getCode()).isEqualTo(404);assertThat(byId.getMessage()).isEqualTo("OPERATION_RESULT_UNKNOWN");
        assertThat(byId.getData()).isNull();assertThat(byKey.getCode()).isEqualTo(404);
        verify(service).operation("PRO-missing");verify(service).operationByKey("PHONE_GRANT_ISSUE","original-key");
        verifyNoMoreInteractions(service);
    }
}
