package ffdd.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import ffdd.common.exception.BizException;
import ffdd.common.storage.ObjectStorageService;
import ffdd.common.storage.StoredObject;
import ffdd.system.client.NotificationClient;
import ffdd.system.domain.SupportTicket;
import ffdd.system.domain.SupportTicketMessage;
import ffdd.system.dto.NotificationCreateRequest;
import ffdd.system.dto.SupportTicketAttachmentRequest;
import ffdd.system.dto.SupportTicketAttachmentResponse;
import ffdd.system.dto.SupportTicketCreateRequest;
import ffdd.system.dto.SupportTicketOpsUpdateRequest;
import ffdd.system.dto.SupportTicketReplyRequest;
import ffdd.system.dto.SupportTicketResponse;
import ffdd.system.mapper.SupportTicketAttachmentMapper;
import ffdd.system.mapper.SupportTicketMapper;
import ffdd.system.mapper.SupportTicketMessageMapper;
import ffdd.system.service.impl.SupportTicketServiceImpl;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class SupportTicketServiceTest {
    private final SupportTicketMapper ticketMapper = mock(SupportTicketMapper.class);
    private final SupportTicketMessageMapper messageMapper = mock(SupportTicketMessageMapper.class);
    private final SupportTicketAttachmentMapper attachmentMapper = mock(SupportTicketAttachmentMapper.class);
    private final NotificationClient notificationClient = mock(NotificationClient.class);
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final SupportTicketServiceImpl service =
            new SupportTicketServiceImpl(ticketMapper, messageMapper, attachmentMapper, notificationClient, storageService);
    private final List<SupportTicketMessage> messages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(messages);
        when(attachmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        doAnswer(invocation -> {
                    SupportTicket ticket = invocation.getArgument(0);
                    ticket.setId(10L);
                    ticket.setCreatedAt(LocalDateTime.now());
                    ticket.setUpdatedAt(LocalDateTime.now());
                    when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
                    return 1;
                })
                .when(ticketMapper)
                .insert(any(SupportTicket.class));
        doAnswer(invocation -> {
                    SupportTicketMessage message = invocation.getArgument(0);
                    message.setId((long) messages.size() + 1);
                    message.setCreatedAt(LocalDateTime.now());
                    message.setUpdatedAt(LocalDateTime.now());
                    messages.add(message);
                    return 1;
                })
                .when(messageMapper)
                .insert(any(SupportTicketMessage.class));
    }

    @Test
    void createsTicketWithInitialUserMessage() {
        SupportTicketCreateRequest request = new SupportTicketCreateRequest();
        request.setCategory("wallet");
        request.setTitle("Withdrawal issue");
        request.setContent("Please help");

        SupportTicketResponse response = service.create(1001L, request);

        ArgumentCaptor<SupportTicket> ticketCaptor = ArgumentCaptor.forClass(SupportTicket.class);
        verify(ticketMapper).insert(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().getCategory()).isEqualTo("WALLET");
        assertThat(ticketCaptor.getValue().getPriority()).isEqualTo("NORMAL");
        assertThat(ticketCaptor.getValue().getOpsUnreadCount()).isEqualTo(1);
        assertThat(response.userId()).isEqualTo(1001L);
        assertThat(response.messages()).hasSize(1);
    }

    @Test
    void rejectsCrossUserDetail() {
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket(10L, "TK1", 1001L, "OPEN"));

        assertThatThrownBy(() -> service.detailForUser(2002L, "TK1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Support ticket not found");
    }

    @Test
    void opsReplyMovesTicketToWaitingUserAndNotifies() {
        SupportTicket ticket = ticket(10L, "TK1", 1001L, "OPEN");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        SupportTicketReplyRequest request = new SupportTicketReplyRequest();
        request.setContent("We are checking it.");

        service.opsReply(1L, "superadmin", "TK1", request);

        verify(ticketMapper).update(any(), any(UpdateWrapper.class));
        ArgumentCaptor<NotificationCreateRequest> notificationCaptor =
                ArgumentCaptor.forClass(NotificationCreateRequest.class);
        verify(notificationClient).create(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getUserId()).isEqualTo(1001L);
        assertThat(notificationCaptor.getValue().getType()).isEqualTo("SUPPORT");
    }

    @Test
    void userCannotReplyClosedTicket() {
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket(10L, "TK1", 1001L, "CLOSED"));
        SupportTicketReplyRequest request = new SupportTicketReplyRequest();
        request.setContent("hello");

        assertThatThrownBy(() -> service.userReply(1001L, "TK1", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Closed support ticket");
    }

    @Test
    void opsUpdateStatusNotifiesUser() {
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket(10L, "TK1", 1001L, "OPEN"));
        SupportTicketOpsUpdateRequest request = new SupportTicketOpsUpdateRequest();
        request.setStatus("RESOLVED");

        service.updateByOps("TK1", request);

        verify(notificationClient).create(any(NotificationCreateRequest.class));
    }

    @Test
    void uploadsSupportTicketAttachmentToMinioObjectKey() {
        when(storageService.put(any(), any(), any(InputStream.class), anyLong()))
                .thenReturn(new StoredObject("nexion", "support/tickets/20260607112233-abc.png", "image/png", 11L));
        when(storageService.presignGet(any(), any())).thenReturn("http://minio.local/support-preview");

        SupportTicketAttachmentResponse response = service.uploadAttachment(
                new MockMultipartFile("file", "proof.png", "image/png", "image-bytes".getBytes()));

        assertThat(response.objectKey()).startsWith("support/tickets/");
        assertThat(response.fileName()).isEqualTo("proof.png");
        assertThat(response.contentType()).isEqualTo("image/png");
        verify(storageService).put(any(), any(), any(InputStream.class), anyLong());
    }

    @Test
    void rejectsSupportTicketAttachmentUrlObjectKey() {
        SupportTicket ticket = ticket(10L, "TK1", 1001L, "OPEN");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        SupportTicketReplyRequest request = new SupportTicketReplyRequest();
        request.setContent("See this");
        SupportTicketAttachmentRequest attachment = new SupportTicketAttachmentRequest();
        attachment.setObjectKey("https://cdn.example.com/raw.png");
        request.setAttachments(List.of(attachment));

        assertThatThrownBy(() -> service.opsReply(1L, "superadmin", "TK1", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("support/tickets");
    }

    @Test
    void supportTicketAttachmentObjectKeyMustUseSupportPrefix() {
        SupportTicket ticket = ticket(10L, "TK1", 1001L, "OPEN");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        SupportTicketReplyRequest request = new SupportTicketReplyRequest();
        request.setContent("See this");
        SupportTicketAttachmentRequest attachment = new SupportTicketAttachmentRequest();
        attachment.setObjectKey("commerce/products/detail/not-support.png");
        request.setAttachments(List.of(attachment));

        assertThatThrownBy(() -> service.userReply(1001L, "TK1", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("support/tickets");
    }

    private SupportTicket ticket(Long id, String ticketNo, Long userId, String status) {
        SupportTicket ticket = new SupportTicket();
        ticket.setId(id);
        ticket.setTicketNo(ticketNo);
        ticket.setUserId(userId);
        ticket.setCategory("WALLET");
        ticket.setPriority("NORMAL");
        ticket.setStatus(status);
        ticket.setTitle("Withdrawal issue");
        ticket.setLastMessage("Please help");
        ticket.setUserUnreadCount(0);
        ticket.setOpsUnreadCount(0);
        ticket.setMessageCount(1);
        ticket.setLastMessageAt(LocalDateTime.now());
        ticket.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        ticket.setUpdatedAt(LocalDateTime.now());
        ticket.setIsDeleted(0);
        return ticket;
    }
}
