package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.domain.TaskSchedulingSettings;
import java.math.BigDecimal;
import java.util.List;
import java.util.random.RandomGenerator;

/** Draw only once at issuance; all retries use the persisted frozen snapshot. */
final class ComputeV2Selection {
    record Candidate(String taskId,String name,String taskClass,String model,BigDecimal minReward,
            BigDecimal maxReward,long revision,TaskSchedulingSettings settings,List<String> texts) { }
    record Selection(Candidate candidate,int expectedSeconds,long rawMicros) { }

    static Selection draw(List<Candidate> eligible,RandomGenerator random) {
        if(eligible.isEmpty()) throw new IllegalStateException("COMPUTE_V2_NO_INSTALLED_TASK");
        long total=0;
        for(var candidate:eligible) total=Math.addExact(total,candidate.settings().selectionWeight());
        long draw=random.nextLong(total);
        Candidate selected=null;
        for(var candidate:eligible) {
            draw-=candidate.settings().selectionWeight();
            if(draw<0) { selected=candidate; break; }
        }
        if(selected==null) throw new IllegalStateException("COMPUTE_V2_SELECTION_INVALID");
        var policy=selected.settings();
        int duration=random.nextInt(policy.expectedDurationMinSec(),policy.expectedDurationMaxSec()+1);
        long lower=micros(selected.minReward()),upper=micros(selected.maxReward());
        if(lower<0 || upper<lower || upper==Long.MAX_VALUE) throw new IllegalArgumentException("TASK_REWARD_RANGE_INVALID");
        return new Selection(selected,duration,lower==upper?lower:random.nextLong(lower,upper+1));
    }
    static long micros(BigDecimal amount) {
        if(amount==null) throw new IllegalArgumentException("TASK_REWARD_RANGE_INVALID");
        return amount.movePointRight(6).longValueExact();
    }
}
