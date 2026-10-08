package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.SupportBulk;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.finance.application.FinanceSupportReadService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportBulkScopeTest {
    final SupportBulkMapper mapper=mock(SupportBulkMapper.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final SupportAttachmentService attachments=mock(SupportAttachmentService.class);
    final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    final String batch="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    final SupportBulkService service=new SupportBulkService(mapper,mock(SupportBindingMapper.class),ownership,
            mock(SupportActivityService.class),mock(FinanceSupportReadService.class),attachments,
            mock(SupportHumanMessageService.class),mock(OpsConversationService.class),mock(AdminIdempotencyService.class),
            mock(ProductionSupportPathGuard.class),transactions,new ObjectMapper(),mock(AuditLogService.class),mock(ApplicationEventPublisher.class));
    @BeforeEach void setup() {
        when(attachments.actor("ADMIN")).thenReturn(7L);
        when(mapper.readerGrant(7L,false)).thenReturn(1);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.scopedCounts(anyMap())).thenReturn(counts(0));
    }
    @Test void formerManagerWithoutAnyCurrentRecipientCannotReadForeignBatch() {
        when(mapper.job(batch)).thenReturn(job(8L));
        when(mapper.jobByCommand(7L,"original-command")).thenReturn(job(8L));
        assertThat(service.page(1,20).getRecords()).isEmpty();
        assertThatThrownBy(()->service.detail(batch)).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(404));
        assertThatThrownBy(()->service.recipients(batch,1,20)).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(404));
        assertThatThrownBy(()->service.recover("original-command")).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(404));
        verify(mapper,never()).counts(anyString());
    }
    @Test void senderLosingQualificationRetainsOnlyOriginalSummaryWithoutRecipientsOrContent() {
        when(mapper.job(batch)).thenReturn(job(7L));when(mapper.counts(batch)).thenReturn(counts(2));
        when(mapper.scopedJobs(anyMap())).thenReturn(List.of(job(7L)));
        when(mapper.scopedJobCount(anyMap())).thenReturn(1L);
        when(mapper.jobByCommand(7L,"original-command")).thenReturn(job(7L));
        when(ownership.defaultQueryScope(null,null)).thenThrow(new BizException(403,"SUPPORT_SERVICE_REQUIRED"));
        var history=service.page(1,20);
        assertThat(history.getTotal()).isEqualTo(1);
        assertThat(history.getRecords().get(0)).containsEntry("visibleCount",0L).containsEntry("contentRestricted",true)
                .doesNotContainKeys("content","assetId","linkTarget","skuId");
        verify(mapper).scopedJobs(argThat(q->((ReadScope)q.get("scope")).equals(new ReadScope(7L,ReadMode.PERSONAL,null,null))
                && Boolean.TRUE.equals(q.get("senderSummary")) && q.get("managedScope")==null && q.get("personalScope")==null));
        var result=service.detail(batch);
        assertThat(result).containsEntry("visibleCount",0L).containsEntry("contentRestricted",true)
                .doesNotContainKeys("content","assetId","linkTarget","skuId");
        assertThat(((SupportBulk.Counts)result.get("counts")).total()).isEqualTo(2);
        assertThat(service.recover("original-command").toString()).contains("contentRestricted=true")
                .doesNotContain("private original content");
        assertThat(service.recipients(batch,1,20).getRecords()).isEmpty();
        verify(mapper).scopedRecipients(argThat(q->((ReadScope)q.get("scope")).mode()==ReadMode.ALL
                && ((ReadScope)q.get("managedScope")).mode()==ReadMode.MANAGED
                && ((ReadScope)q.get("personalScope")).mode()==ReadMode.PERSONAL));
    }
    @Test void foreignManagerSeesOnlyCurrentRecipientCountsNotOriginalFrozenTotal() {
        when(mapper.job(batch)).thenReturn(job(8L));when(mapper.scopedRecipientCount(anyMap())).thenReturn(1L);
        when(mapper.scopedCounts(anyMap())).thenReturn(counts(1));
        var result=service.detail(batch);
        assertThat(result).containsEntry("frozenCount",1L).containsEntry("contentRestricted",true);
        assertThat(((SupportBulk.Counts)result.get("counts")).total()).isEqualTo(1);
        verify(mapper,never()).counts(anyString());
    }
    @Test void managementListDoesNotMergeDualRolePersonalRowsOrSenderSummary() {
        var scope=new ReadScope(7L,ReadMode.MANAGED,null,null);
        when(ownership.supervisor(7L)).thenReturn(true);when(mapper.readerGrant(7L,true)).thenReturn(1);
        when(ownership.defaultQueryScope(null,null)).thenReturn(scope);
        when(mapper.scopedJobs(anyMap())).thenReturn(List.of(job(7L)));
        when(mapper.scopedRecipientCount(anyMap())).thenReturn(1L);when(mapper.scopedCounts(anyMap())).thenReturn(counts(1));
        var result=service.page(1,20).getRecords().get(0);
        assertThat(result).containsEntry("frozenCount",1L);
        verify(mapper).scopedJobs(argThat(q->q.get("scope")==scope && q.get("managedScope")==null && q.get("personalScope")==null
                && Boolean.FALSE.equals(q.get("senderSummary"))));
        verify(mapper,never()).counts(anyString());
    }
    @Test void retainedCommandUsesCurrentRestrictedProjection() {
        when(mapper.jobByCommand(7L,"original-command")).thenReturn(job(7L));
        when(mapper.job(batch)).thenReturn(job(7L));when(mapper.counts(batch)).thenReturn(counts(2));
        assertThat(service.recover("original-command").toString()).contains("contentRestricted=true").doesNotContain("private original content");
    }
    @Test void originalReadCapabilityStillRequiredForSenderSummary() {
        when(mapper.readerGrant(7L,false)).thenReturn(0);
        assertThatThrownBy(()->service.page(1,20)).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(403));
        assertThatThrownBy(()->service.detail(batch)).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(403));
        assertThatThrownBy(()->service.recover("original-command")).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(403));
        assertThatThrownBy(()->service.recipients(batch,1,20)).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(403));
        verify(mapper,never()).job(anyString());
        verify(mapper,never()).scopedJobs(anyMap());verify(mapper,never()).jobByCommand(anyLong(),anyString());
    }
    @Test void revokedAccountOrSessionRejectsEveryHistoricalEntryBeforeQuery() {
        when(attachments.actor("ADMIN")).thenThrow(new BizException(401,"SESSION_EXPIRED"));
        assertThatThrownBy(()->service.page(1,20)).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(401));
        assertThatThrownBy(()->service.detail(batch)).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(401));
        assertThatThrownBy(()->service.recover("original-command")).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(401));
        assertThatThrownBy(()->service.recipients(batch,1,20)).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(401));
        verifyNoInteractions(mapper);
    }
    private Map<String,Object> job(Long sender) {
        return Map.of("id",batch,"actorId",sender,"frozenCount",2L,"state","COMPLETED","version",1L,
                "contentJson","{\"content\":\"private original content\"}");
    }
    private Map<String,Object> counts(long total) {
        return Map.of("total",total,"pending",0L,"sent",total,"failed",0L,"skipped",0L,"cancelled",0L,"unknown",0L);
    }
}
