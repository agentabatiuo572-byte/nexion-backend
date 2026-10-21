package ffdd.opsconsole.finance.cregis;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import ffdd.opsconsole.finance.mapper.CregisExceptionRefundMapper;
import org.junit.jupiter.api.Test;

class CregisExceptionRefundWorkerTest {
    @Test void approvedVerificationFailureRemainsRetryableAndVisibleWithoutCreate(){
        var service=mock(CregisExceptionRefundService.class);var db=mock(CregisExceptionRefundMapper.class);var router=mock(CregisGatewayRouter.class);
        var config=new CregisProperties();config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(42);
        var row=CregisExceptionRefundService.map("id",1L,"state","APPROVED");var failure=new IllegalStateException("RPC unavailable");
        when(db.work(42,0)).thenReturn(List.of(row));when(service.fresh(row)).thenThrow(failure);
        new CregisExceptionRefundWorker(service,db,config,router,mock(BscDepositProof.class)).process();
        verify(service).dispatchBlocked(1,failure);verify(service,never()).beginDispatch(anyLong(),any());verifyNoInteractions(router);
    }
    @Test void timeoutAndRestartedWorkerNeverResubmitUnknownOriginalBusinessKey(){
        var service=mock(CregisExceptionRefundService.class);var db=mock(CregisExceptionRefundMapper.class);var router=mock(CregisGatewayRouter.class);var gateway=mock(CregisGateway.class);
        var config=new CregisProperties();config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(42);config.setCallbackBaseUrl("https://callback.invalid/api/cregis/callbacks");
        var row=CregisExceptionRefundService.map("id",1L,"caseNo","CR-RF-test","businessKey","CR-RF-test","state","APPROVED","returnAddress","0x"+"a".repeat(40),"netAmount",BigDecimal.ONE);
        when(db.work(eq(42L),anyLong())).thenAnswer(call->call.<Long>getArgument(1)==0?List.of(row):List.of());
        when(service.beginDispatch(eq(1L),any())).thenAnswer(call->{row.put("state","SUBMITTING");return row;});
        when(router.provider()).thenReturn(gateway);when(gateway.createPayout(any())).thenThrow(new CregisGatewayException(CregisGatewayException.Kind.SUBMISSION_UNKNOWN,"timeout"));
        doAnswer(call->{row.put("state","UNKNOWN");return null;}).when(service).unknown(1,"CREGIS_REFUND_CREATE_UNKNOWN");
        var chain=mock(BscDepositProof.class);new CregisExceptionRefundWorker(service,db,config,router,chain).process();
        new CregisExceptionRefundWorker(service,db,config,router,chain).process();
        verify(gateway,times(1)).createPayout(argThat(r->r.thirdPartyId().equals("CR-RF-test")&&r.amount().compareTo(BigDecimal.ONE)==0));
        verify(service).unknown(1,"CREGIS_REFUND_CREATE_UNKNOWN");verify(service,never()).rejectedCreate(anyLong(),any());
    }
    @Test void persistedIntentPastLeaseBecomesUnknownWithoutCreate(){
        var service=mock(CregisExceptionRefundService.class);var db=mock(CregisExceptionRefundMapper.class);var router=mock(CregisGatewayRouter.class);var config=new CregisProperties();config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(42);
        when(db.work(42,0)).thenReturn(List.of(CregisExceptionRefundService.map("id",1L,"state","SUBMITTING","dispatchIntentAt",LocalDateTime.now().minusMinutes(3))));
        new CregisExceptionRefundWorker(service,db,config,router,mock(BscDepositProof.class)).process();
        verify(service).unknown(1,"CREGIS_REFUND_SUBMISSION_UNKNOWN");verifyNoInteractions(router);
    }
}
