package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.boundary.AdminSearchHit;
import ffdd.opsconsole.content.facade.ContentSearchFacade;
import ffdd.opsconsole.finance.facade.FinanceSearchFacade;
import ffdd.opsconsole.platform.dto.AdminGlobalSearchResult;
import ffdd.opsconsole.user.facade.UserSearchFacade;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsGlobalSearchServiceTest {
    private final UserSearchFacade users = mock(UserSearchFacade.class);
    private final ContentSearchFacade content = mock(ContentSearchFacade.class);
    private final FinanceSearchFacade finance = mock(FinanceSearchFacade.class);
    private final OpsGlobalSearchService service = new OpsGlobalSearchService(users, content, finance);

    @Test
    void retiredTicketsAreNoLongerASearchDomainWhileTheOtherDomainsKeepTheirOrder() {
        when(users.searchAdminUsers("needle", 6)).thenReturn(List.of(new AdminSearchHit("user", "1", "u", "", "/users/1", 1)));
        when(content.searchConversations("needle", 6)).thenReturn(List.of(new AdminSearchHit("conversation", "CV-1", "c", "", "/service/sessions?conversation=CV-1", 1)));
        when(finance.searchWithdrawals("needle", 6)).thenReturn(List.of(new AdminSearchHit("withdrawal", "W-1", "w", "", "/finance/withdrawals/W-1", 1)));

        var result = service.search("needle", 8);

        assertThat(result.getData()).extracting(AdminGlobalSearchResult::kind).containsExactly("user", "conversation", "withdrawal");
        verify(content).searchConversations("needle", 6);
        verifyNoMoreInteractions(content);
        assertThat(Arrays.stream(ContentSearchFacade.class.getMethods()).map(java.lang.reflect.Method::getName))
                .containsExactly("searchConversations");
    }
}
