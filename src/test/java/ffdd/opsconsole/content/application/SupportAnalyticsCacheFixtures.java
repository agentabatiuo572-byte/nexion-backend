package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAnalyticsStats;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import java.time.*;
import java.util.*;

final class SupportAnalyticsCacheFixtures {
    static SupportAnalyticsFactSnapshot empty(Instant asOf){
        var views=new TreeMap<String,List<SupportAnalyticsStats.Customer>>();for(String kind:List.of("HISTORY:","ASSET:"))for(String currency:List.of("*","USDT","UVT"))views.put(kind+currency,List.of());
        var funds=new Snapshot(List.of(),new WalletRead(List.of(),ReadState.READY,List.of(),null),new WithdrawalRead(List.of(),ReadState.READY,List.of(),null,CoverageState.UNKNOWN,CoverageState.UNKNOWN),"Asia/Shanghai");
        var compiled=new SupportAnalyticsCompiledFacts(views,new SupportDeviceReadFacade.Snapshot(List.of(),List.of(),null),Map.of(),SupportAnalyticsStats.unavailableWindow(),null,Map.of(),Map.of(),List.of(),List.of(),funds,new SupportRules(1L,7,7,7,"INFINITE",null),List.of(),List.of(),List.of());
        return new SupportAnalyticsFactSnapshot(asOf,List.of(),new SupportPaymentFacts.Snapshot(List.of(),List.of(),List.of(),"Asia/Shanghai",asOf),new NetSnapshot(List.of(),List.of(),List.of()),List.of(),List.of(),0,compiled);
    }
}
