package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.cregis.CregisDepositService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CregisDepositControllerTest {
    private final CregisDepositService service = mock(CregisDepositService.class);
    private final CregisDepositController controller = new CregisDepositController(service);

    @Test
    void unauthenticatedRequestCannotClaimAddress() {
        assertThatThrownBy(() -> controller.address("BEP20", null)).hasMessage("USER_AUTH_REQUIRED");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"false,USER,2503", "true,ADMIN,2503", "true,USER,0", "true,USER,-1", "true,USER,invalid"})
    void removingAllowlistDoesNotPermitOtherSubjects(boolean authenticated, String subject, String principal) {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(authenticated);
        when(auth.getDetails()).thenReturn(Map.of("subjectType", subject));
        when(auth.getPrincipal()).thenReturn(principal);
        assertThatThrownBy(() -> controller.address("BEP20", auth)).hasMessage("USER_AUTH_REQUIRED");
        verifyNoInteractions(service);
    }

    @Test
    void regularUserUsesOnlyItsAuthenticatedIdentity() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getDetails()).thenReturn(Map.of("subjectType", "USER"));
        when(auth.getPrincipal()).thenReturn("2503");
        controller.address("BEP20", auth);
        verify(service).address(2503L);
        verifyNoMoreInteractions(service);
    }
}
