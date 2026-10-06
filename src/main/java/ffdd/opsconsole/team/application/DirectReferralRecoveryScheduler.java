package ffdd.opsconsole.team.application;

import ffdd.opsconsole.team.mapper.DirectReferralMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class DirectReferralRecoveryScheduler {
    private final DirectReferralMapper mapper;
    private final DirectReferralService service;
    private final DirectReferralPolicyService policies;
    private final java.util.concurrent.atomic.AtomicLong cursor=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicReference<String> calculationCursor=new java.util.concurrent.atomic.AtomicReference<>("");
    private final java.util.concurrent.atomic.AtomicReference<String> reissueCursor=new java.util.concurrent.atomic.AtomicReference<>("");
    @Scheduled(fixedDelayString="${nexion.f5.recovery-delay-ms:600000}",initialDelayString="${nexion.f5.recovery-initial-delay-ms:300000}")
    public void recover(){
        var scope=policies.scope();
        var calculations=mapper.waitingCalculation(scope.sourceEnvironment(),scope.runId(),calculationCursor.get());
        if(calculations.isEmpty()&&!calculationCursor.get().isEmpty()){calculationCursor.set("");calculations=mapper.waitingCalculation(scope.sourceEnvironment(),scope.runId(),"");}
        for(String no:calculations){try{service.resumeGroup(no);}catch(RuntimeException failure){log.warn("Reward calculation remains pending settlement={} reason={}",no,failure.getMessage());}finally{calculationCursor.set(no);}}
        var reissues=mapper.reissueRecoveryOrders(scope.sourceEnvironment(),scope.runId(),reissueCursor.get());
        if(reissues.isEmpty()&&!reissueCursor.get().isEmpty()){reissueCursor.set("");reissues=mapper.reissueRecoveryOrders(scope.sourceEnvironment(),scope.runId(),"");}
        for(String order:reissues){try{service.recoverOrderReissues(order);}catch(RuntimeException failure){log.warn("Reissue recovery remains pending order={} reason={}",order,failure.getMessage());}finally{reissueCursor.set(order);}}
        var pending=mapper.pendingRecovery(scope.sourceEnvironment(),scope.runId(),cursor.get());
        if(pending.isEmpty()&&cursor.get()>0){cursor.set(0);pending=mapper.pendingRecovery(scope.sourceEnvironment(),scope.runId(),0);}
        for(var row:pending){
            try{service.retryRecovery(row.settlementNo());}
            catch(RuntimeException failure){log.warn("Direct referral recovery remains pending settlement={} reason={}",row.settlementNo(),failure.getMessage());}
            finally{cursor.set(row.id());}
        }
    }
}
