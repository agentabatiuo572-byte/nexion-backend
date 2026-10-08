package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.content.domain.SupportAssignment;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;

class SupportHumanMessageServiceTest {
    @Test void incompleteFrozenPayloadChecksEvenOrphanFactWithoutAuthorizingNewSend() {
        var mapper=mock(ffdd.opsconsole.content.mapper.SupportHumanMessageMapper.class);
        var ownership=mock(SupportOwnershipService.class);
        var service=new SupportHumanMessageService(mapper,mock(ffdd.opsconsole.content.mapper.SupportBindingMapper.class),ownership,mock(SupportMaintenanceService.class),mock(SupportAttachmentService.class));
        when(mapper.findCommittedAdmin(1L,"bulk_reserved")).thenReturn(Map.of("metadataMessageId",40L));
        assertThat(service.hasCommittedAdminFact(1L,"bulk_reserved")).isTrue();
        when(mapper.findCommittedAdmin(1L,"bulk_reserved")).thenReturn(null);
        assertThat(service.hasCommittedAdminFact(1L,"bulk_reserved")).isFalse();
        verifyNoInteractions(ownership);
    }
    @Test void httpAdminCannotTakeReservedBulkClientNamespaceButUserNamespaceRemainsIndependent() {
        var mapper=mock(ffdd.opsconsole.content.mapper.SupportHumanMessageMapper.class);
        var bindings=mock(ffdd.opsconsole.content.mapper.SupportBindingMapper.class);
        var ownership=mock(SupportOwnershipService.class);
        var service=new SupportHumanMessageService(mapper,bindings,ownership,mock(SupportMaintenanceService.class),mock(SupportAttachmentService.class));
        assertThatThrownBy(()->service.prepare(20L,"ADMIN",1L,"key-12345","CREATE","payload","bulk_reserved","TEXT","SERVICE",null,3L))
            .hasMessageContaining("SUPPORT_CLIENT_MESSAGE_ID_RESERVED");
        verifyNoInteractions(ownership,mapper);
        when(mapper.captureFence()).thenReturn(1L);
        when(mapper.find(any(),any(),any())).thenReturn(null);
        assertThat(service.prepare(20L,"USER",20L,"key-12345","CREATE","payload","bulk_reserved","TEXT","SERVICE",null,null).client()).isEqualTo("bulk_reserved");
    }
    @Test void persistedActorUsesLockedDatabaseGrantWithoutAnAmbientSession() {
        var bindings=mock(SupportBindingMapper.class);
        var assignment=new SupportAssignment(3L,20L,1L,1L,"MANUAL",20L,0,null,null);
        when(bindings.lockCustomer(20L)).thenReturn(20L);
        when(bindings.lockAgent(1L)).thenReturn(1L);
        when(bindings.eligibleAgent(1L)).thenReturn(1);
        when(bindings.writerGrant(1L)).thenReturn(java.util.List.of(9L));
        when(bindings.current(20L)).thenReturn(assignment);
        var ownership=new SupportOwnershipService(bindings,mock(SupportGroupMapper.class));
        boolean prior=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(ownership.requireWriterForActor(1L,20L,true)).isEqualTo(assignment);
            var order=inOrder(bindings);
            order.verify(bindings).lockCustomer(20L);
            order.verify(bindings).lockAgent(1L);
            order.verify(bindings).eligibleAgent(1L);
            order.verify(bindings).writerGrant(1L);
            order.verify(bindings).current(20L);
            when(bindings.writerGrant(1L)).thenReturn(java.util.List.of());
            assertThatThrownBy(()->ownership.requireWriterForActor(1L,20L,true)).hasMessageContaining("SUPPORT_WRITE_FORBIDDEN");
            when(bindings.eligibleAgent(1L)).thenReturn(0);
            assertThatThrownBy(()->ownership.requireSendingActor(1L)).hasMessageContaining("SUPPORT_AGENT_UNAVAILABLE");
        } finally {org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(prior);}
    }

    @Test void unknownRecoveryChecksOriginalPayloadAndActualAuthorWithoutRecheckingNewSendEligibility() {
        var mapper=mock(SupportHumanMessageMapper.class);
        when(mapper.captureFence()).thenReturn(1L);
        when(mapper.find(any(),any(),any())).thenReturn(null);
        var ownership=mock(SupportOwnershipService.class);
        var assignment=new SupportAssignment(3L,20L,1L,1L,"MANUAL",20L,0,null,null);
        when(ownership.requireWriterForActor(1L,20L,true)).thenReturn(assignment);
        var service=new SupportHumanMessageService(mapper,mock(SupportBindingMapper.class),ownership,mock(SupportMaintenanceService.class),mock(SupportAttachmentService.class));
        var request=new ffdd.opsconsole.content.dto.ConversationInitiateRequest("support",20L,null,null,"hello","Recovery regression",null,"TEXT",null,"SERVICE","bulk-client-01",3L);
        var p=service.prepareForActor(1L,20L,"key-12345","CREATE",request,request.clientMessageId(),request.kind(),request.intent(),null,3L);
        var old=new java.util.HashMap<String,Object>(Map.of("customerId",20L,"messageCustomerId",20L,"payloadHash",p.hash(),
            "actorId",1L,"actorType","ADMIN","senderId",1L,"senderType","agent","metadataMessageId",40L,"messageId",40L,"conversationNo","CV-40"));
        when(mapper.findCommittedAdmin(1L,"bulk-client-01")).thenReturn(old);
        clearInvocations(ownership);
        when(ownership.requireWriterForActor(1L,20L,true)).thenThrow(new ffdd.opsconsole.shared.exception.BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND"));
        assertThat(service.findCommittedAdmin(1L,"bulk-client-01",20L,"CREATE",request))
            .contains(new SupportHumanMessageService.CommittedAdminMessage(40L,"CV-40"));
        verifyNoInteractions(ownership);
        assertThatThrownBy(()->service.findCommittedAdmin(1L,"bulk-client-01",20L,"REPLY:CV-40",request)).hasMessageContaining("SUPPORT_CLIENT_MESSAGE_CONFLICT");
        old.put("senderId",2L);
        assertThatThrownBy(()->service.findCommittedAdmin(1L,"bulk-client-01",20L,"CREATE",request)).hasMessageContaining("SUPPORT_CLIENT_MESSAGE_CONFLICT");
        old.put("senderId",1L);old.remove("messageId");
        assertThatThrownBy(()->service.findCommittedAdmin(1L,"bulk-client-01",20L,"CREATE",request)).hasMessageContaining("SUPPORT_CLIENT_MESSAGE_CONFLICT");
        when(mapper.findCommittedAdmin(1L,"bulk-client-01")).thenReturn(null);
        assertThat(service.findCommittedAdmin(1L,"bulk-client-01",20L,"CREATE",request)).isEmpty();
    }

    @Test void internalMaintenanceImageCommitsUsingItsPersistedActorOnly() {
        var mapper=mock(SupportHumanMessageMapper.class);when(mapper.captureFence()).thenReturn(1L);
        when(mapper.find(any(),any(),any())).thenReturn(null);
        var ownership=mock(SupportOwnershipService.class);
        var assignment=new SupportAssignment(3L,20L,1L,1L,"MANUAL",20L,0,null,null);
        when(ownership.requireWriterForActor(1L,20L,true)).thenReturn(assignment);
        var maintenance=mock(SupportMaintenanceService.class);
        var attachments=mock(SupportAttachmentService.class);
        var service=new SupportHumanMessageService(mapper,mock(SupportBindingMapper.class),ownership,maintenance,attachments);
        var request=new ffdd.opsconsole.content.dto.ConversationInitiateRequest("support",20L,null,null,null,"Maintenance regression",null,"IMAGE","image-ref","MAINTENANCE","bulk-client-01",3L);
        var p=service.prepareForActor(1L,20L,"key-12345","CREATE",request,request.clientMessageId(),request.kind(),request.intent(),request.attachmentId(),3L);
        service.committedForActor(p,40L,"key-12345");
        verify(attachments).attachToMessageForActor(20L,1L,3L,"image-ref",40L);
        verify(maintenance).executedForActor(20L,1L,assignment,40L,"key-12345");
        verify(ownership,never()).actorId();
        verify(attachments,never()).attachToMessage(any(),any(),any(),any(),any(),any());
    }

    @Test void authenticatedPreparationCannotRecordAnotherAdminAsAuthor() {
        var mapper=mock(SupportHumanMessageMapper.class);when(mapper.captureFence()).thenReturn(1L);
        var ownership=mock(SupportOwnershipService.class);
        when(ownership.requireWriter(20L,true)).thenReturn(new SupportAssignment(3L,20L,1L,1L,"MANUAL",20L,0,null,null));
        var service=new SupportHumanMessageService(mapper,mock(SupportBindingMapper.class),ownership,mock(SupportMaintenanceService.class),mock(SupportAttachmentService.class));
        assertThatThrownBy(()->service.prepare(20L,"ADMIN",2L,"key-12345","CREATE","payload","bulk-client-01","TEXT","SERVICE",null,3L))
            .hasMessageContaining("SUPPORT_SUBJECT_REQUIRED");
    }

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
