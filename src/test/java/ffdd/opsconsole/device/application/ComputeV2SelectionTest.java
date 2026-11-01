package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import ffdd.opsconsole.device.domain.TaskSchedulingSettings;
import org.junit.jupiter.api.Test;

class ComputeV2SelectionTest {
    private ComputeV2Selection.Candidate candidate(String id,int weight,int min,int max,String lower,String upper){
        return new ComputeV2Selection.Candidate(id,id,"EM","BGE-M3",new BigDecimal(lower),new BigDecimal(upper),7,
                new TaskSchedulingSettings(weight,min,max,Math.max(300,max),"test-bge-m3-v1","test-bge-m3-v1","{}"),List.of("a","b","c"));
    }
    @Test void configuredWeightControlsDistributionAndBothRealWorkloadsAreSelected(){
        var tasks=List.of(candidate("short",1,5,5,"0.000001","0.000001"),candidate("long",3,12,18,"0.000002","0.010002"));
        var random=new Random(150412);int shortCount=0,longCount=0,minDuration=100,maxDuration=0;
        for(int i=0;i<20000;i++){
            var result=ComputeV2Selection.draw(tasks,random);
            if(result.candidate().taskId().equals("short")){shortCount++;assertThat(result.rawMicros()).isEqualTo(1);assertThat(result.expectedSeconds()).isEqualTo(5);}
            else{longCount++;assertThat(result.rawMicros()).isBetween(2L,10002L);minDuration=Math.min(minDuration,result.expectedSeconds());maxDuration=Math.max(maxDuration,result.expectedSeconds());}
        }
        assertThat(shortCount).isBetween(4700,5300);assertThat(longCount).isBetween(14700,15300);
        assertThat(minDuration).isEqualTo(12);assertThat(maxDuration).isEqualTo(18);
    }
    @Test void fixedSixDecimalQuoteAndDurationAreExact(){
        var result=ComputeV2Selection.draw(List.of(candidate("fixed",1,900,900,"0.123456","0.123456")),new Random(1));
        assertThat(result.rawMicros()).isEqualTo(123456);assertThat(result.expectedSeconds()).isEqualTo(900);
    }
    @Test void emptyPoolAndOutOfRangeMoneyCannotManufactureAReward(){
        assertThatThrownBy(()->ComputeV2Selection.draw(List.of(),new Random(1))).hasMessage("COMPUTE_V2_NO_INSTALLED_TASK");
        assertThatThrownBy(()->ComputeV2Selection.draw(List.of(candidate("bad",1,5,5,"-1","0")),new Random(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ComputeV2Selection.draw(List.of(candidate("bad",1,5,5,"0.0000001","0.01")),new Random(1))).isInstanceOf(ArithmeticException.class);
    }
}
