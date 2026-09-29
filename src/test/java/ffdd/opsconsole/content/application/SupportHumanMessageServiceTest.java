package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.content.domain.SupportAssignment;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;

class SupportHumanMessageServiceTest {
    @Test void recordDelimiterCollisionDoesNotAliasPayloadAndLegacyFingerprintRemainsStable() {
        var a=new ffdd.opsconsole.content.dto.ConversationInitiateRequest("support",20L,null,null,"hello, reason=abcdefgh","ijklmnop",null,"TEXT",null,"SERVICE","same-client-id",3L);
        var b=new ffdd.opsconsole.content.dto.ConversationInitiateRequest("support",20L,null,null,"hello","abcdefgh, reason=ijklmnop",null,"TEXT",null,"SERVICE","same-client-id",3L);
        assertThat(a.toString()).isNotEqualTo(b.toString());
        assertThat(new AppSupportService.ReplyRequest("hello","OPEN",1L).toString()).isEqualTo("ReplyRequest[body=hello, expectedStatus=OPEN, expectedVersion=1]");
        var mapper=mock(SupportHumanMessageMapper.class);when(mapper.captureFence()).thenReturn(1L);when(mapper.find(any(),any(),any())).thenReturn(null);
        var ownership=mock(SupportOwnershipService.class);when(ownership.requireWriter(20L,true)).thenReturn(new SupportAssignment(3L,20L,1L,1L,"MANUAL",20L,0,null,null));
        var service=new SupportHumanMessageService(mapper,mock(SupportBindingMapper.class),ownership,mock(SupportMaintenanceService.class),mock(SupportAttachmentService.class));
        var p=service.prepare(20L,"ADMIN",1L,"key-12345","CREATE",a,a.clientMessageId(),a.kind(),a.intent(),null,3L);
        when(mapper.find("ADMIN",1L,"same-client-id")).thenReturn(Map.of("customerId",20L,"payloadHash",p.hash(),"messageId",40L,"conversationNo","CV-40"));
        assertThatThrownBy(()->service.prepare(20L,"ADMIN",1L,"key-other","CREATE",b,b.clientMessageId(),b.kind(),b.intent(),null,3L)).hasMessageContaining("SUPPORT_CLIENT_MESSAGE_CONFLICT");
    }
    @Test void contentDiscriminatorRejectsEmptyTextButAllowsCaptionlessImage() {
        assertThat(SupportHumanMessageService.validContent(null," ")).isFalse();
        assertThat(SupportHumanMessageService.validContent("IMAGE",null)).isTrue();
        assertThat(SupportHumanMessageService.validContent("SVG","hello")).isFalse();
        assertThat(SupportHumanMessageService.validContent("TEXT","x".repeat(2001))).isFalse();
    }
    @Test void permanentClientIdReplayCannotChangePayloadOrCreateAnotherExecution() {
        var mapper=mock(SupportHumanMessageMapper.class);when(mapper.captureFence()).thenReturn(1L);
        when(mapper.find(any(),any(),any())).thenReturn(null);
        var bindings=mock(SupportBindingMapper.class);
        var ownership=mock(SupportOwnershipService.class);
        var maintenance=mock(SupportMaintenanceService.class);
        var service=new SupportHumanMessageService(mapper,bindings,ownership,maintenance,mock(SupportAttachmentService.class));
        var prepared=service.prepare(20L,"USER",20L,"original-key","CREATE","payload","message-123","TEXT","SERVICE",null,null);
        when(mapper.find("USER",20L,"message-123")).thenReturn(Map.of("customerId",20L,"payloadHash",prepared.hash(),"messageId",40L,"conversationNo","CV-40"));
        assertThat(service.prepare(20L,"USER",20L,"different-key","CREATE","payload","message-123","TEXT","SERVICE",null,null).previousMessageId()).isEqualTo(40L);
        assertThatThrownBy(()->service.prepare(20L,"USER",20L,"different-key","CREATE","changed","message-123","TEXT","SERVICE",null,null))
            .hasMessageContaining("SUPPORT_CLIENT_MESSAGE_CONFLICT");
        verifyNoInteractions(maintenance);
    }
    @Test void onlyExplicitAdminMaintenanceCreatesExecutionAndRequiresAssignment() {
        var mapper=mock(SupportHumanMessageMapper.class);when(mapper.captureFence()).thenReturn(1L);
        when(mapper.find(any(),any(),any())).thenReturn(null);
        var ownership=mock(SupportOwnershipService.class);
        var assignment=new SupportAssignment(3L,20L,1L,1L,"MANUAL",20L,0,null,null);
        when(ownership.requireWriter(20L,true)).thenReturn(assignment);
        var maintenance=mock(SupportMaintenanceService.class);
        var service=new SupportHumanMessageService(mapper,mock(SupportBindingMapper.class),ownership,maintenance,mock(SupportAttachmentService.class));
        assertThatThrownBy(()->service.prepare(20L,"USER",20L,"key-12345","CREATE","payload","message-123","TEXT","MAINTENANCE",null,null))
            .hasMessageContaining("SUPPORT_MESSAGE_INPUT_INVALID");
        assertThatThrownBy(()->service.prepare(20L,"ADMIN",1L,"key-12345","CREATE","payload","message-123","TEXT","MAINTENANCE",null,null))
            .hasMessageContaining("SUPPORT_ASSIGNMENT_CHANGED");
        var p=service.prepare(20L,"ADMIN",1L,"key-12345","CREATE","payload","message-123","TEXT","MAINTENANCE",null,3L);
        service.committed(p,40L,"key-12345");
        verify(maintenance).executed(20L,assignment,40L,"key-12345");
    }
}
