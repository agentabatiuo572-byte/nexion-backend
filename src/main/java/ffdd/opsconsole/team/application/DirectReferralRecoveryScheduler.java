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
    @Scheduled(fixedDelayString="${nexion.f5.recovery-delay-ms:600000}",initialDelayString="${nexion.f5.recovery-initial-delay-ms:300000}")
    public void recover(){
        var scope=policies.scope();
        var pending=mapper.pendingRecovery(scope.sourceEnvironment(),scope.runId(),cursor.get());
        if(pending.isEmpty()&&cursor.get()>0){cursor.set(0);pending=mapper.pendingRecovery(scope.sourceEnvironment(),scope.runId(),0);}
        for(var row:pending){
            try{service.reverse(row.settlementNo(),row.refundRatio());}
            catch(RuntimeException failure){log.warn("Direct referral recovery remains pending settlement={} reason={}",row.settlementNo(),failure.getMessage());}
            finally{cursor.set(row.id());}
        }
    }
}
