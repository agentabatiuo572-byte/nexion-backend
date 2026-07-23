package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.mapper.SupportTicketMapper;
import ffdd.opsconsole.content.mapper.SupportTicketMessageMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MybatisSupportTicketRepositoryTest {
    private final SupportTicketMapper ticketMapper = mock(SupportTicketMapper.class);
    private final SupportTicketMessageMapper messageMapper = mock(SupportTicketMessageMapper.class);
    private final MybatisSupportTicketRepository repository = new MybatisSupportTicketRepository(ticketMapper, messageMapper);

    @Test
    void keepsFullTranscriptInMessageAndBoundsTheTicketListHeader() {
        when(ticketMapper.insert(any(SupportTicketEntity.class))).thenAnswer(invocation -> {
            SupportTicketEntity entity = invocation.getArgument(0);
            entity.setId(88L);
            return 1;
        });
        String transcript = "会话全文" + "x".repeat(700);

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
        assertThat(ticketCaptor.getValue().getLastMessage().codePointCount(0, ticketCaptor.getValue().getLastMessage().length()))
                .isEqualTo(512);
        assertThat(ticketCaptor.getValue().getLastMessage()).endsWith("…");

        ArgumentCaptor<SupportTicketMessageEntity> messageCaptor = ArgumentCaptor.forClass(SupportTicketMessageEntity.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).isEqualTo(transcript);
        assertThat(result.lastMessage()).isEqualTo(ticketCaptor.getValue().getLastMessage());
    }
}
