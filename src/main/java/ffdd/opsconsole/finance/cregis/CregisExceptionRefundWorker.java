package ffdd.opsconsole.finance.cregis;

import static ffdd.opsconsole.finance.cregis.CregisExceptionRefundService.*;
import ffdd.opsconsole.finance.mapper.CregisExceptionRefundMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class CregisExceptionRefundWorker {
    private final CregisExceptionRefundService service;
    private final CregisExceptionRefundMapper db;
    private final CregisProperties config;
    private final CregisGatewayRouter router;
    private final BscDepositProof chain;
    private final AtomicLong cursor=new AtomicLong();

    @Scheduled(fixedDelayString="${nexion.finance.cregis.exception-refund-poll-ms:30000}")
    public synchronized void process() {
        if(config.getMode()!=CregisProperties.Mode.PROVIDER)return;
        var rows=db.work(config.getProjectId(),cursor.get());
        if(rows.isEmpty()&&cursor.get()!=0){cursor.set(0);rows=db.work(config.getProjectId(),0);}
        for(var row:rows){cursor.set(number(row,"id"));try{process(row);}catch(RuntimeException unavailable){
            if("APPROVED".equals(row.get("state")))service.dispatchBlocked(number(row,"id"),unavailable);
        }}
    }
    private void process(Map<String,Object> row) {
        long id=number(row,"id");String state=text(row,"state");
        if("APPROVED".equals(state)){dispatch(row);return;}
        if("SUBMITTING".equals(state)) {
            Object stored=row.get("dispatchIntentAt");
            LocalDateTime intent=stored instanceof LocalDateTime time?time:stored instanceof java.sql.Timestamp stamp?stamp.toLocalDateTime():null;
            if(intent!=null&&intent.isBefore(LocalDateTime.now().minusMinutes(2)))
                service.unknown(id,"CREGIS_REFUND_SUBMISSION_UNKNOWN");
            return;
        }
        long cid=number(row,"providerCid");
        if(cid<=0){
            var callbacks=db.callbackCids(text(row,"caseNo"));
            if(callbacks.size()!=1)return;
            cid=callbacks.get(0);service.query(row,cid);service.bindSubmission(id,cid);
            row=db.byId(config.getProjectId(),id);if(row==null)return;
        }
        var observed=service.query(row,cid);
        // Persist vendor success before an RPC outage can interrupt chain verification.
        service.observed(id,observed,null);
        if(observed.status()==CregisGateway.PayoutStatus.SUCCEEDED&&observed.txid()!=null) {
            var proof=chain.verifyPayout(observed.txid(),text(row,"returnAddress"),decimal(row,"netAmount")).orElse(null);
            if(proof!=null)service.observed(id,observed,proof);
        }
    }
    private void dispatch(Map<String,Object> candidate) {
        long id=number(candidate,"id");
        var row=service.beginDispatch(id,service.fresh(candidate));if(row==null)return;
        try {
            var submission=router.provider().createPayout(new CregisGateway.PayoutRequest(CregisConstants.USDT_BEP20_CURRENCY,
                    text(row,"returnAddress"),decimal(row,"netAmount"),text(row,"businessKey"),
                    config.getCallbackBaseUrl().replaceAll("/+$","")+"/payout","UVEL exception refund "+text(row,"caseNo")));
            if(submission==null||submission.cid()<=0||!text(row,"businessKey").equals(submission.thirdPartyId())) {
                service.unknown(id,"CREGIS_REFUND_SUBMISSION_IDENTITY_CONFLICT");return;
            }
            service.bindSubmission(id,submission.cid());
        } catch(CregisGatewayException failure) {
            if(failure.kind()==CregisGatewayException.Kind.REJECTED||failure.kind()==CregisGatewayException.Kind.CONFIGURATION)
                service.rejectedCreate(id,"CREGIS_REFUND_CREATE_REJECTED");
            else service.unknown(id,"CREGIS_REFUND_CREATE_UNKNOWN");
        } catch(RuntimeException failure) {service.unknown(id,"CREGIS_REFUND_CREATE_UNKNOWN");}
    }
}
