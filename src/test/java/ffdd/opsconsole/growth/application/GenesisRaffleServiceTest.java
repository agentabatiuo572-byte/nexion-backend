package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.dto.GenesisRaffleRequest;
import ffdd.opsconsole.growth.dto.GenesisRaffleRequest.Prize;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GenesisRaffleServiceTest {
    private Prize prize(String id,String type,String amount,int count,String product){
        return new Prize(id,"奖励","Reward","Phần thưởng",type,new BigDecimal(amount),count,product);
    }
    private GenesisRaffleRequest request(List<Prize> prizes){
        return new GenesisRaffleRequest("genesis-oct",1L,false,"创世抽奖","Genesis raffle","Rút thăm Genesis",
                "2026-10-25T00:00:00Z","2026-11-01T00:00:00Z",prizes,"Configure a reviewed campaign");
    }
    @Test void acceptsFiniteCurrencyAndDevicePrizes(){
        assertThatCode(()->GenesisRaffleService.validate(request(List.of(prize("uvt","UVT","20",10,null),prize("device","DEVICE","1",1,"stellarbox-pro")))))
                .doesNotThrowAnyException();
    }
    @Test void rejectsUnknownAssetsFractionalDevicesAndExcessLiability(){
        for(Prize prize:List.of(prize("bad","BTC","1",1,null),prize("bad","DEVICE","2",1,"stellarbox-pro"),
                prize("bad","USDT","0",1,null),prize("bad","USDT","1000001",1,null),prize("bad","USDT","1.0000001",1,null),
                prize("bad","USDT","1",101,null)))
            assertThatThrownBy(()->GenesisRaffleService.validate(request(List.of(prize)))).isInstanceOf(BizException.class);
    }
    @Test void rejectsDuplicateIdsAndMoreThanOneHundredAwards(){
        assertThatThrownBy(()->GenesisRaffleService.validate(request(List.of(prize("a","UVT","1",1,null),prize("a","USDT","1",1,null))))).isInstanceOf(BizException.class);
        assertThatThrownBy(()->GenesisRaffleService.validate(request(List.of(prize("a","UVT","1",100,null),prize("b","UVT","1",1,null))))).isInstanceOf(BizException.class);
    }
    @Test void eachTicketHasExactlyOnePositionInTheWeightedPopulation(){
        long[] counts={2,0,3};
        assertThat(java.util.stream.LongStream.range(0,5).mapToInt(n->GenesisRaffleService.choose(counts,n)).toArray()).containsExactly(0,0,2,2,2);
        counts[0]--;
        assertThat(java.util.stream.LongStream.range(0,4).mapToInt(n->GenesisRaffleService.choose(counts,n)).toArray()).containsExactly(0,2,2,2);
        assertThatThrownBy(()->GenesisRaffleService.choose(counts,4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GenesisRaffleService.choose(counts,-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
