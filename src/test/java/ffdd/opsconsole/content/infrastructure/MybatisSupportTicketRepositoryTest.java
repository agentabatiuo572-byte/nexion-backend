package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.mapper.SupportTicketMapper;
import org.junit.jupiter.api.Test;

class MybatisSupportTicketRepositoryTest {
    private final SupportTicketMapper ticketMapper = mock(SupportTicketMapper.class);
    private final MybatisSupportTicketRepository repository = new MybatisSupportTicketRepository(ffdd.opsconsole.content.SupportTestDependencies.ownership(), ticketMapper);

    @Test void ticketCollectionCountAndRowsKeepTheSameGroupAndQuery() {
        var scope=new ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope(7L,
                ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.MANAGED,9L,null);
        var visibility=new SupportTicketMapper.Visibility(null,null,false,false);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        // The existing repository normalizes the all-tab to null; the authorization scope stays explicit.
        when(ticketMapper.countTicketsScoped(null,null,null,null,null,null,"needle",visibility,scope)).thenReturn(2L);
        var request=new ffdd.opsconsole.content.dto.SupportTicketQueryRequest("all",null,null,null,null,null,"needle",2L,10L);
        assertThat(repository.pageTickets(request,scope).getTotal()).isEqualTo(2L);
        verify(ticketMapper).countTicketsScoped(null,null,null,null,null,null,"needle",visibility,scope);
        verify(ticketMapper).pageTicketsScoped(null,null,null,null,null,null,"needle",10L,10L,visibility,scope);
    }
    @Test void missingTicketScopeNeverFallsBackToAppQuery() {
        org.assertj.core.api.Assertions.assertThatThrownBy(()->repository.pageTickets(null,null)).isInstanceOf(NullPointerException.class);
        org.mockito.Mockito.verifyNoInteractions(ticketMapper);
    }
}
