package ffdd.opsconsole.finance.application;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade.BeforeSource;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.ConnectionHolder;

/** Pure in-memory storage with the actual binder; no JDBC persistence claim. */
final class SupportPaymentFundFixture {
    final SupportFundJournalMapper mapper=mock(SupportFundJournalMapper.class);
    final EarningsReleaseMapper earnings=mock(EarningsReleaseMapper.class);
    final Connection connection=mock(Connection.class);
    final ConnectionHolder holder=new ConnectionHolder(connection);
    final SupportFundJournalFacade fund;
    final Map<String,Map<String,Object>> operations=new LinkedHashMap<>();
    final Map<Long,List<Map<String,Object>>> parties=new HashMap<>(),legs=new HashMap<>();
    final List<Map<String,Object>> journal=new ArrayList<>();
    Map<String,Object> wallet;
    private long sequence=100;
    private final ObjectMapper json;

    SupportPaymentFundFixture(DataSource dataSource,ObjectMapper json) {
        this.json=json;holder.setSynchronizedWithTransaction(true);
        fund=new SupportFundJournalBindingService(mapper,dataSource,json);
        when(mapper.lockWallet(anyLong())).thenAnswer(i->wallet==null?List.of():List.of(new LinkedHashMap<>(wallet)));
        when(mapper.journal(anyString(),anyLong(),anyLong())).thenAnswer(i->journal.stream()
            .filter(r->Objects.equals(r.get("incarnation"),i.getArgument(0)) && ((Number)r.get("revision")).longValue()>(long)i.getArgument(1)
                && ((Number)r.get("revision")).longValue()<=(long)i.getArgument(2)).map(LinkedHashMap::new).map(r->(Map<String,Object>)r).toList());
        when(mapper.operation(anyString())).thenAnswer(i->operations.get(i.getArgument(0)));
        when(mapper.insertOperation(anyMap())).thenAnswer(i->{
            Map<String,Object> input=i.getArgument(0);String key=input.get("operationKey").toString();
            if(operations.containsKey(key))throw new DuplicateKeyException("immutable operation");
            var row=new LinkedHashMap<>(input);row.put("id",++sequence);row.put("recordedDbUtc",LocalDateTime.of(2026,10,8,1,0));operations.put(key,row);return 1;
        });
        when(mapper.insertParty(anyLong(),anyLong())).thenAnswer(i->{
            long id=i.getArgument(0),customer=i.getArgument(1);parties.computeIfAbsent(id,k->new ArrayList<>()).add(Map.of("operationId",id,"customerId",customer));return 1;
        });
        when(mapper.insertLeg(anyLong(),anyString(),anyLong())).thenAnswer(i->{
            long id=i.getArgument(0),revision=i.getArgument(2);String incarnation=i.getArgument(1);
            legs.computeIfAbsent(id,k->new ArrayList<>()).add(Map.of("operationId",id,"incarnation",incarnation,"revision",revision));return 1;
        });
        when(mapper.parties(anyLong())).thenAnswer(i->parties.getOrDefault(i.getArgument(0),List.of()));
        when(mapper.legs(anyLong())).thenAnswer(i->legs.getOrDefault(i.getArgument(0),List.of()));
    }

    void wallet(BigDecimal usdt) {
        wallet=new LinkedHashMap<>();wallet.put("id",70L);wallet.put("userId",7L);wallet.put("version",0L);wallet.put("revision",0L);
        wallet.put("incarnation","00000000-0000-4000-8000-000000000007");wallet.put("sandbox",0);wallet.put("isDeleted",0);
        wallet.put("createdAt",LocalDateTime.of(2026,10,8,0,0));wallet.put("usdtAvailable",usdt);wallet.put("nexAvailable",BigDecimal.ZERO);
        wallet.put("pendingWithdraw",BigDecimal.ZERO);wallet.put("cregisRiskHeld",BigDecimal.ZERO);
    }
    void arm(BeforeSource before) {
        if(wallet!=null)return;
        boolean out=Set.of(Source.WALLET_ORDER,Source.TRADE_IN,Source.CAPACITY_KEEP,Source.TRIAL_CONVERT).contains(before.source());
        wallet(out?new BigDecimal(before.source()==Source.WALLET_ORDER?"90":"20"):BigDecimal.ZERO);
    }
    void advance(BigDecimal usdt,BigDecimal nex) {
        var old=new LinkedHashMap<>(wallet);wallet.put("usdtAvailable",((BigDecimal)wallet.get("usdtAvailable")).add(usdt));
        wallet.put("nexAvailable",((BigDecimal)wallet.get("nexAvailable")).add(nex));
        wallet.put("version",((Number)wallet.get("version")).longValue()+1);wallet.put("revision",((Number)wallet.get("revision")).longValue()+1);
        var row=new LinkedHashMap<String,Object>();row.put("id",1000L+journal.size());row.put("incarnation",wallet.get("incarnation"));
        row.put("revision",wallet.get("revision"));row.put("observation","CHANGE");row.put("recordedDbUtc",LocalDateTime.of(2026,10,8,1,0));
        row.put("protocolSha256",sha("SUPPORT_WALLET_JOURNAL_V1"));
        for(String field:List.of("id","userId","usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld","version","sandbox","isDeleted","createdAt")) {
            String cap=Character.toUpperCase(field.charAt(0))+field.substring(1);row.put("old"+cap,old.get(field));row.put("new"+cap,wallet.get(field));
        }
        journal.add(row);
    }
    Map<String,Object> authority(Map<String,Object> payment) {
        var audit=new LinkedHashMap<String,Object>();String source=payment.get("source").toString(),order=payment.get("orderNo").toString();
        audit.put("id",12L);audit.put("customerId",7L);audit.put("actorId",7L);audit.put("validDigest",1);audit.put("actorType","USER");audit.put("result","SUCCESS");
        audit.put("orderNo",order);audit.put("amount",payment.get("amount"));audit.put("paidAt",payment.get("succeededAt"));audit.put("auditAt",LocalDateTime.of(2026,10,8,12,0,3));
        audit.put("deviceMatched",1);audit.put("method","POST");audit.put("paymentNo",payment.get("paymentNo"));audit.put("bizNo",order);audit.put("resourceId",order);
        Map<String,Object> detail;
        if(source.equals("WALLET_ORDER")) {
            audit.put("action","APP_ORDER_WALLET_PAYMENT");audit.put("resourceType","ORDER");audit.put("path","/api/orders/"+order+"/pay");
            detail=Map.of("canonical",true,"paymentRail","UVEL_WALLET","paymentNo",payment.get("paymentNo"),"amountUsdt",payment.get("amount"));
        } else if(source.equals("TRIAL_CONVERT")) {
            String claim=payment.get("businessId").toString().replace(":CHARGE","");audit.put("bizNo",claim);audit.put("resourceId",claim);
            audit.put("action","H2_TRIAL_CONVERTED");audit.put("resourceType","TRIAL_CLAIM");
            detail=Map.of("sourceEnvironment","PRODUCTION","serverCanonical",true,"runId","","paymentRail","UVEL_USDT_WALLET","orderNo",order,"amountUsdt",payment.get("amount"));
        } else if(source.equals("CAPACITY_KEEP")) {
            audit.put("action","USER_CAPACITY_KEEP_PURCHASE_COMPLETED");audit.put("resourceType","DEVICE_ORDER");audit.put("path","/api/app/trade-in/capacity-keep");
            detail=Map.of("decisionSource","server","after",Map.of("orderNo",order,"walletDebitUsdt",payment.get("amount")));
        } else {
            audit.put("action","USER_TRADEIN_COMPLETED");audit.put("resourceType","TRADEIN_APPLICATION");audit.put("path","/api/app/trade-in/submit");
            detail=Map.of("pricing",Map.of("orderNo",order,"walletDebitUsdt",payment.get("amount")));
        }
        try {audit.put("detail",json.writeValueAsString(detail));}catch(Exception ex){throw new IllegalStateException(ex);}
        return audit;
    }
    private static String sha(String value) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
}
