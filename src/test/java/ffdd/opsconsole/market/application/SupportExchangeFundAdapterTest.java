package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.finance.domain.SupportFundAllocation;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Account;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Action;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Event;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupportExchangeFundAdapterTest {
    static BigDecimal n(String value){return new BigDecimal(value);}
    static Account a(String currency){return new Account(7,currency,"available");}
    static Account held(String currency){return new Account(7,currency,"held:exchange-EX-abcdefgh");}
    List<Event> events(String phase,String from,String gross,String target,String fee){return SupportExchangeFundAdapter.events(7,"EX-abcdefgh",phase,from,n(gross),n(target),n(fee));}
    SupportFundAllocation funds(String asset,String amount){var f=new SupportFundAllocation();f.apply(new Event("original",Action.DEPOSIT,null,a(asset),null,n(amount),null));return f;}

    @Test void immediateUsdtMinimumFeePreservesOriginalContributionAndExactNetNex(){
        var f=funds("USDT","1.000001");var events=events("IMMEDIATE","USDT","1.000001","0.166667","0.500000");
        assertThat(events).extracting(Event::action).containsExactly(Action.FEE,Action.EXCHANGE);
        assertThat(f.applyBatch(events)).isEmpty();assertThat(f.balance(a("USDT"))).isZero();assertThat(f.balance(a("UVT"))).isEqualByComparingTo("0.166667");assertThat(f.total("USDT")).isEqualByComparingTo("1.000001");
    }
    @Test void nexFeeUsesOnlyConvertedUsdtAndDoesNotTakeExistingUsdtLots(){
        var f=funds("UVT","10");f.apply(new Event("other-usdt",Action.DEPOSIT,null,a("USDT"),null,n("50"),null));
        var events=events("IMMEDIATE","UVT","10","0.666667","0.500000");
        assertThat(events).extracting(Event::action).containsExactly(Action.EXCHANGE,Action.FEE,Action.MOVE);
        assertThat(f.applyBatch(events)).isEmpty();assertThat(f.balance(a("USDT"))).isEqualByComparingTo("50.666667");assertThat(f.total("UVT")).isEqualByComparingTo("10");assertThat(f.total("USDT")).isEqualByComparingTo("50");assertThat(f.heldBalances()).isEmpty();
    }
    @Test void queuedUsdtHeldIsTheOnlySourceForCompletion(){
        var f=funds("USDT","20");f.applyBatch(events("HOLD","USDT","20","20","0"));
        f.apply(new Event("later",Action.DEPOSIT,null,a("USDT"),null,n("50"),null));
        assertThat(f.balance(held("USDT"))).isEqualByComparingTo("20");
        assertThat(f.applyBatch(events("COMPLETE","USDT","20","6.333333","1"))).isEmpty();
        assertThat(f.balance(a("USDT"))).isEqualByComparingTo("50");assertThat(f.balance(a("UVT"))).isEqualByComparingTo("6.333333");assertThat(f.heldBalances()).isEmpty();assertThat(f.total("USDT")).isEqualByComparingTo("70");
    }
    @Test void queuedNexCancelRestoresOnlyItsOriginalLotAndReplayDoesNotAddContribution(){
        var f=funds("UVT","10");f.applyBatch(events("HOLD","UVT","10","5","0"));var cancel=events("CANCEL","UVT","10","5","0");
        assertThat(f.applyBatch(cancel)).isEmpty();assertThat(f.applyBatch(cancel)).isEmpty();assertThat(f.balance(a("UVT"))).isEqualByComparingTo("10");assertThat(f.total("UVT")).isEqualByComparingTo("10");assertThat(f.heldBalances()).isEmpty();
    }
    @Test void zeroFeeIsOneExactExchangeAndRoundingNeverManufacturesAFee(){
        var events=events("IMMEDIATE","USDT","2.999999","0.999999","0");
        assertThat(events).hasSize(1);assertThat(events.get(0).action()).isEqualTo(Action.EXCHANGE);assertThat(events.get(0).amount()).isEqualByComparingTo("2.999999");assertThat(events.get(0).targetAmount()).isEqualByComparingTo("0.999999");
    }
    @Test void unavailableHoldNeverGetsInventedAndUnsupportedPrecisionFailsClosed(){
        var f=funds("USDT","20");assertThatThrownBy(()->f.applyBatch(events("CANCEL","USDT","20","20","0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->events("IMMEDIATE","USDT","20.0000001","1","0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->events("IMMEDIATE","USDT","1","1","1")).isInstanceOf(IllegalArgumentException.class);
    }
}
