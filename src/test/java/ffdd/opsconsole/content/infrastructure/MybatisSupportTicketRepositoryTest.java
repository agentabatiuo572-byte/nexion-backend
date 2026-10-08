package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.mapper.SupportTicketMapper;
import ffdd.opsconsole.content.mapper.SupportTicketMessageMapper;
import ffdd.opsconsole.content.domain.SupportTicketView;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MybatisSupportTicketRepositoryTest {
    private final SupportTicketMapper ticketMapper = mock(SupportTicketMapper.class);
    private final SupportTicketMessageMapper messageMapper = mock(SupportTicketMessageMapper.class);
    private final ffdd.opsconsole.content.application.SupportTicketCreationPolicyService creationPolicy = mock(ffdd.opsconsole.content.application.SupportTicketCreationPolicyService.class);
    private final ffdd.opsconsole.content.application.SupportTicketOwnerService ticketOwners = mock(ffdd.opsconsole.content.application.SupportTicketOwnerService.class);
    private final MybatisSupportTicketRepository repository = new MybatisSupportTicketRepository(ffdd.opsconsole.content.SupportTestDependencies.ownership(), ticketMapper, messageMapper, creationPolicy, ticketOwners);

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
        verify(ticketMapper).pageTicketsScoped(null,null,null,null,null,null,"needle",null,false,10L,10L,visibility,scope);
        org.mockito.Mockito.verify(ticketMapper,org.mockito.Mockito.never()).countTickets(any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void missingTicketScopeNeverFallsBackToAppQuery() {
        org.assertj.core.api.Assertions.assertThatThrownBy(()->repository.pageTickets(null,null)).isInstanceOf(NullPointerException.class);
        org.mockito.Mockito.verifyNoInteractions(ticketMapper);
    }

    @Test
    void keepsFullTranscriptInMessageAndBoundsTheTicketListHeader() {
        when(ticketMapper.insert(any(SupportTicketEntity.class))).thenAnswer(invocation -> {
            SupportTicketEntity entity = invocation.getArgument(0);
            entity.setId(88L);
            return 1;
        });
        String transcript = "会话全文" + "x".repeat(700);
        when(ticketOwners.resolveForCreate(1001L, null)).thenReturn(new ffdd.opsconsole.content.domain.DedicatedAdvisorBindingView(7L, "Dedicated advisor"));
        when(creationPolicy.requireAllowed(1001L, "TECHNICAL", "会话转工单", transcript))
                .thenReturn(LocalDateTime.of(2026, 7, 17, 12, 0));

        var result = repository.createTicket(
                "TK-001",
                1001L,
                "TECHNICAL",
                "HIGH",
                "会话转工单",
                transcript,
                null,
                "未分配",
                "superadmin",
                LocalDateTime.of(2026, 7, 17, 12, 0));

        ArgumentCaptor<SupportTicketEntity> ticketCaptor = ArgumentCaptor.forClass(SupportTicketEntity.class);
        verify(ticketMapper).insert(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().getAssignedAdminId()).isEqualTo(7L);
        assertThat(ticketCaptor.getValue().getAssignedAdminName()).isEqualTo("Dedicated advisor");
        assertThat(result.assignedAdminId()).isEqualTo(7L);
        assertThat(result.assignedAdminName()).isEqualTo("Dedicated advisor");
        assertThat(ticketCaptor.getValue().getLastMessage().codePointCount(0, ticketCaptor.getValue().getLastMessage().length()))
                .isEqualTo(512);
        assertThat(ticketCaptor.getValue().getLastMessage()).endsWith("…");

        ArgumentCaptor<SupportTicketMessageEntity> messageCaptor = ArgumentCaptor.forClass(SupportTicketMessageEntity.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).isEqualTo(transcript);
        assertThat(result.lastMessage()).isEqualTo(ticketCaptor.getValue().getLastMessage());
    }

    @Test
    void readAcknowledgementUsesTicketOwnerStatusAndVersionInTheCas() {
        SupportTicketView ticket = new SupportTicketView(
                88L, "TK-READ", 1001L, "TECHNICAL", "NORMAL", "OPEN", "title", "reply",
                null, "Unassigned", 1, 0, 1, LocalDateTime.of(2026, 8, 31, 12, 0),
                null, LocalDateTime.of(2026, 8, 31, 11, 0), LocalDateTime.of(2026, 8, 31, 12, 0),
                false, null, 7L, true);
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 12, 1);
        when(ticketMapper.markUserRead("TK-READ", 1001L, "OPEN", 7L, now)).thenReturn(1);

        assertThat(repository.markUserReadCas(ticket, now)).isTrue();
        verify(ticketMapper).markUserRead("TK-READ", 1001L, "OPEN", 7L, now);
    }
}
