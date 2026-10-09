package ffdd.opsconsole.promotion.application;

import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class PromotionRewardScheduler {
    private final PromotionRewardService rewards;
    private final PromotionAvailabilityService availability;
    private final AtomicReference<String> reversalCursor=new AtomicReference<>("");
    @Scheduled(fixedDelayString="${nexion.promotion.dispatch-delay-ms:60000}")
    public void dispatch(){
        String cursor="";
        while(true){
            var activities=availability.candidatesAfter(cursor,100);if(activities.isEmpty())break;
            for(String activity:activities){
                try{availability.pauseIfShort(activity);}
                catch(RuntimeException failure){log.error("Promotion capacity check failed activity={}",activity,failure);}
            }
            cursor=activities.get(activities.size()-1);if(activities.size()<100)break;
        }
        for(String id:rewards.pending(50)){
            String command=id("PC");
            try{rewards.issue(id,command);}
            catch(RuntimeException failure){
                log.warn("Promotion issuance failed obligation={} reason={}",id,failure.getMessage());
                try{rewards.recordFailure(id,command,failure.getMessage());}
                catch(RuntimeException auditFailure){log.error("Promotion failure could not be recorded obligation={}",id,auditFailure);}
            }
        }
        var reversals=rewards.reversalsAfter(reversalCursor.get(),50);
        if(reversals.isEmpty()){reversalCursor.set("");return;}
        for(String id:reversals){
            String command=id("PC");
            try{rewards.reverseRefund(id,command);}
            catch(RuntimeException failure){
                log.error("Promotion refund recovery remains pending obligation={}",id,failure);
                try{rewards.recordActionFailure(id,command,"REVERSE",failure.getMessage());}
                catch(RuntimeException auditFailure){log.error("Promotion recovery failure could not be recorded obligation={}",id,auditFailure);}
            }
            reversalCursor.set(id);
        }
    }
}
