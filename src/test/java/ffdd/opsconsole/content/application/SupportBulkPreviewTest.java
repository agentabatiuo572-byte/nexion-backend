package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.SupportBulk;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.dto.SupportBulkRequest;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.finance.application.FinanceSupportReadService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportBulkPreviewTest {
    @Test void stateChangesAfterFreezeSkipWithoutSendingOrCreatingAReplacementSegment() {
        // D-6: a conversation that closed after the freeze is not skipped; the send resumes the customer's thread.
        for(String state:List.of("TRANSFERRED","ARCHIVED","CONVERTED","CHANGED")) {
            var mapper=mock(SupportBulkMapper.class);
            var ownership=mock(SupportOwnershipService.class);
            var messages=mock(SupportHumanMessageService.class);
            var conversations=mock(OpsConversationService.class);
            var transactions=mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
            var service=new SupportBulkService(mapper,mock(SupportBindingMapper.class),ownership,mock(SupportActivityService.class),
                    mock(FinanceSupportReadService.class),mock(SupportAttachmentService.class),messages,conversations,
                    mock(AdminIdempotencyService.class),mock(ProductionSupportPathGuard.class),transactions,new ObjectMapper(),
                    mock(AuditLogService.class),mock(ApplicationEventPublisher.class),300);
            String batch="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
            var job=Map.<String,Object>of("id",batch,"actorId",7L,"state","QUEUED","frozenCount",1L);
            var row=Map.<String,Object>of("customerId",1L,"state","PENDING","resultCertainty","KNOWN",
                    "operation","REPLY:CV-OLD","conversationNo","CV-OLD","expectedAssignmentId",101L,"clientMessageId","bulk_stable");
            when(mapper.jobForUpdate(batch)).thenReturn(job);
            when(mapper.job(batch)).thenReturn(job);
            when(mapper.recipientForUpdate(batch,1L)).thenReturn(row);
            when(mapper.maintenanceEnabled(1L)).thenReturn(true);
            when(ownership.requireWriterForActor(7L,1L,false)).thenReturn(new ffdd.opsconsole.content.domain.SupportAssignment(101L,1L,7L,1L,"TEST",null,null,null,null));
            when(mapper.conversation(1L)).thenReturn(Map.of("conversationNo","CHANGED".equals(state)?"CV-NEW":"CV-OLD",
                    "status",state,"archived","ARCHIVED".equals(state),"converted","CONVERTED".equals(state)));
            when(mapper.counts(batch)).thenReturn(Map.of("total",1L,"pending",0L,"sent",0L,"failed",0L,"skipped",1L,"cancelled",0L,"unknown",0L));
            service.processRecipient(batch,1L);
            String code="CHANGED".equals(state)?"SUPPORT_BULK_CONVERSATION_CHANGED":"CONVERSATION_"+state;
            verify(mapper).outcome(batch,1L,"SKIPPED","KNOWN",null,null,code,false,1);
            verify(mapper,never()).freezeRequest(anyString(),anyLong(),anyString());
            verifyNoInteractions(conversations);
            verify(messages,times(2)).hasCommittedAdminFact(7L,"bulk_stable");
        }
    }

    @Test void previewExcludesBlockedSegmentsWithExactReasonsAndFreezesOnlyWritableCustomers() {
        var mapper=mock(SupportBulkMapper.class);
        var bindings=mock(SupportBindingMapper.class);
        var ownership=mock(SupportOwnershipService.class);
        var activity=mock(SupportActivityService.class);
        var attachments=mock(SupportAttachmentService.class);
        var messages=mock(SupportHumanMessageService.class);
        var conversations=mock(OpsConversationService.class);
        var service=new SupportBulkService(mapper,bindings,ownership,activity,mock(FinanceSupportReadService.class),
                attachments,messages,conversations,mock(AdminIdempotencyService.class),
                mock(ProductionSupportPathGuard.class),mock(PlatformTransactionManager.class),new ObjectMapper(),
                mock(AuditLogService.class),mock(ApplicationEventPublisher.class),300);
        var now=LocalDateTime.of(2026,10,17,1,0);
        when(attachments.actor("ADMIN")).thenReturn(7L);
        when(activity.checkpoint()).thenReturn(new SupportActivityService.Coverage(now.minusDays(40),now));
        when(bindings.rules()).thenReturn(new SupportRules(1L,30,7,30,"NONE",0));
        when(ownership.queryScope(ReadMode.PERSONAL,null,null)).thenReturn(new ReadScope(7L,ReadMode.PERSONAL,null,null));
        var candidates=new ArrayList<Map<String,Object>>();
        for(long customer=1;customer<=9;customer++) {
            candidates.add(Map.of("customerId",customer,"assignmentId",customer+100,"enabled",customer!=8));
            when(bindings.canonicalCustomer(customer)).thenReturn(1);
        }
        when(mapper.candidates(anyMap())).thenReturn(candidates);
        when(mapper.conversation(2L)).thenReturn(Map.of("status","OPEN"));
        when(mapper.conversation(3L)).thenReturn(Map.of("status","RESOLVED"));
        when(mapper.conversation(4L)).thenReturn(Map.of("status","CLOSED"));
        when(mapper.conversation(5L)).thenReturn(Map.of("status","TRANSFERRED"));
        when(mapper.conversation(6L)).thenReturn(Map.of("status","OPEN","archived",true));
        when(mapper.conversation(7L)).thenReturn(Map.of("status","CLOSED","converted",true));

        for(String mode:List.of("EXPLICIT","ALL_FILTERED")) {
            var ids="ALL_FILTERED".equals(mode)?List.<Long>of():List.of(1L,2L,3L,4L,5L,6L,7L,8L,9L);
            var preview=service.preview(new SupportBulkRequest.Preview(null,ids,List.of(9L),mode));
            // D-6: a closed (not converted) conversation stays in the selection; the send resumes the customer's thread.
            assertThat(preview.count()).isEqualTo(4);
            assertThat(preview.customers()).extracting(SupportBulk.Customer::id).containsExactly(1L,2L,3L,4L);
            assertThat(preview.excluded()).extracting(SupportBulk.Excluded::reason).containsExactly(
                    "CONVERSATION_TRANSFERRED","CONVERSATION_ARCHIVED",
                    "CONVERSATION_CONVERTED","MAINTENANCE_STOPPED","EXPLICITLY_EXCLUDED");
            verify(mapper).insertRecipient(eq(preview.selectionId()),eq(1L),eq(101L),anyString());
            verify(mapper).insertRecipient(eq(preview.selectionId()),eq(4L),eq(104L),anyString());
            for(long customer=5;customer<=9;customer++) verify(mapper,never()).insertRecipient(eq(preview.selectionId()),eq(customer),anyLong(),anyString());
        }
        verify(mapper,never()).conversation(8L);
        verify(mapper,never()).conversation(9L);
        verify(ownership,times(2)).requireSendingActor(7L);
        verifyNoInteractions(messages,conversations);
    }
}
