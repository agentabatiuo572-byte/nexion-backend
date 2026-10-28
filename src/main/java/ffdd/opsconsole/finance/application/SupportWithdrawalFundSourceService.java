package ffdd.opsconsole.finance.application;

import static ffdd.opsconsole.finance.application.SupportPaymentFactService.flag;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Account;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Action;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Event;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records actual withdrawal cash revisions; missing old fund lots remain unknown. */
@Service
@RequiredArgsConstructor
public class SupportWithdrawalFundSourceService {
    private static final String SCHEMA = "SUPPORT_WITHDRAWAL_FUND_V1";
    private final SupportFundJournalFacade fund;
    private final SupportFundJournalMapper journal;
    private final WithdrawalPayoutMapper mapper;
    private final ObjectMapper json;

    public interface Before {}
    private record Token(SupportFundJournalFacade.Before binding, Map<String,Object> wallet,
                         long customerId) implements Before {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Before begin(long customerId) {
        Map<String,Object> wallet = wallet(customerId);
        return new Token(flag(wallet,"sandbox")==0?fund.begin(List.of(customerId)):null, Map.copyOf(wallet), customerId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasCashReservation(String withdrawalNo, long customerId, BigDecimal gross, BigDecimal nex) {
        Map<String,Object> order = mapper.fundOrder(withdrawalNo);
        if (order == null || number(order,"userId") != customerId) throw failure("ORDER_IDENTITY_MISMATCH");
        if(!"USDT".equals(order.get("asset")))return false;
        if(!(order.get("amount") instanceof Number) || !(order.get("nexBurned") instanceof Number))return false;
        if(!same(order.get("amount"),gross) || !same(order.get("nexBurned"),nz(nex)))throw failure("ORDER_IDENTITY_MISMATCH");
        return cashReservation(order, mapper.fundReservationLedgers(withdrawalNo));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserved(Before before, String withdrawalNo) { bind(before,withdrawalNo,"RESERVE",null); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmed(Before before, String withdrawalNo, LocalDateTime succeededAt) {
        bind(before,withdrawalNo,"CONFIRM",succeededAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void returned(Before before, String withdrawalNo) { bind(before,withdrawalNo,"RETURN",null); }

    private void bind(Before before, String withdrawalNo, String phase, LocalDateTime succeededAt) {
        if (!(before instanceof Token token)) throw failure("INVALID_BEFORE");
        if(token.binding()==null)return;
        var order = mapper.fundOrder(withdrawalNo);
        if(order == null || number(order,"userId") != token.customerId()) throw failure("ORDER_IDENTITY_MISMATCH");
        if(!"USDT".equals(order.get("asset")))throw failure("ORDER_CURRENCY_MISMATCH");
        var ledgers = mapper.fundReservationLedgers(withdrawalNo);
        BigDecimal gross = decimal(order.get("amount")), nex = decimal(order.get("nexBurned"));
        if(gross.signum()<=0 || nex.signum()<0 || !same(order.get("netReceive"),gross.subtract(decimal(order.get("actualFee")))))
            throw failure("ORDER_AMOUNT_MISMATCH");
        if("RESERVE".equals(phase) && !cashReservation(order,ledgers)) throw failure("RESERVATION_SOURCE_MISMATCH");
        if("CONFIRM".equals(phase) && (!"CONFIRMED".equals(order.get("status")) || succeededAt == null
                || time(order.get("completedAt"))==null
                || !succeededAt.withNano(0).equals(time(order.get("completedAt")).withNano(0)))) throw failure("SUCCESS_SOURCE_MISMATCH");
        if("RETURN".equals(phase) && !Set.of("FAILED","REFUNDED","REVIEW_REJECTED").contains(order.get("status")))
            throw failure("RETURN_SOURCE_MISMATCH");
        var after = wallet(token.customerId());
        var events = events(token.customerId(),withdrawalNo,phase,gross,nex);
        BigDecimal usdt = "RESERVE".equals(phase)?gross.negate():"RETURN".equals(phase)?gross:BigDecimal.ZERO;
        BigDecimal nexDelta = "RESERVE".equals(phase)?nex.negate():"RETURN".equals(phase)?nex:BigDecimal.ZERO;
        BigDecimal pending = "RESERVE".equals(phase)?gross:gross.negate();
        var trace = cashTrace(token.wallet(),after,token.customerId(),usdt,nexDelta,pending);
        String environment = "PRODUCTION";
        String key = operationKey(withdrawalNo,phase);
        var snapshot = new TreeMap<String,Object>();
        snapshot.put("schemaVersion",SCHEMA);snapshot.put("customerId",token.customerId());
        snapshot.put("withdrawalNo",withdrawalNo);snapshot.put("phase",phase);snapshot.put("order",order);
        snapshot.put("reservationLedgers",ledgers);snapshot.put("gross",gross);snapshot.put("netReceive",order.get("netReceive"));
        snapshot.put("actualFee",order.get("actualFee"));snapshot.put("nexBurned",nex);snapshot.put("succeededAt","CONFIRM".equals(phase)?order.get("completedAt"):null);
        snapshot.put("bankReservation","BANK-VND".equals(order.get("chain"))?mapper.fundBankReservation(withdrawalNo):null);
        snapshot.put("submissionAudits",mapper.fundSubmissionAudits(withdrawalNo));
        var proof = new TreeMap<String,Object>();
        proof.put("schemaVersion",SCHEMA);proof.put("cashBefore",token.wallet());proof.put("cashAfter",after);
        proof.put("cashTrace",List.of(Map.of("sourceId","nx_withdrawal_order:"+withdrawalNo,
                "eventIds",events.stream().map(Event::id).toList(),"journal",trace)));
        proof.put("reservationSource",cashReservation(order,ledgers)?"VERIFIED_CASH_SOURCE":"UNKNOWN");
        // No old receipt is invented: the reader must resolve and verify this reference or retain UNKNOWN.
        proof.put("originalReserve", "RESERVE".equals(phase)?null:operationKey(withdrawalNo,"RESERVE"));
        var original = "RESERVE".equals(phase)?null:journal.operation(operationKey(withdrawalNo,"RESERVE"));
        if(original!=null && original.isEmpty())original=null;
        proof.put("originalFundEvidence","RESERVE".equals(phase)?null:original==null?"UNKNOWN":"PRESENT");
        proof.put("originalReserveOperation",original);
        var receipt = fund.bind(token.binding(),new SupportFundJournalFacade.ReceiptInput(key,"SUPPORT_WITHDRAWAL",phase,
                "nx_withdrawal_order",withdrawalNo,environment,encode(events),encode(snapshot),encode(proof)));
        if(receipt == null || !key.equals(receipt.operationKey()) || receipt.journalLegs()!=1) throw failure("BOUND_RECEIPT_MISMATCH");
    }

    static List<Event> events(long customerId,String withdrawalNo,String phase,BigDecimal gross,BigDecimal nex) {
        String reserve = "RESERVE:"+withdrawalNo, fee = "FEE:"+withdrawalNo+":NEX";
        var events = new ArrayList<Event>();
        Action action = Action.valueOf(phase);
        events.add(new Event("RESERVE".equals(phase)?reserve:phase+":"+withdrawalNo,action,
                action==Action.RESERVE?new Account(customerId,"USDT","available"):null,null,
                action==Action.RESERVE?null:reserve,action==Action.RESERVE?gross:null,null));
        if(nex.signum()>0 && action==Action.RESERVE)
            events.add(new Event(fee,Action.FEE,new Account(customerId,"UVT","available"),null,null,nex,null));
        if(nex.signum()>0 && action==Action.RETURN)
            events.add(new Event("REFUND:"+withdrawalNo+":NEX",Action.REFUND,null,null,fee,nex,null));
        return List.copyOf(events);
    }

    static boolean reservation(Map<String,Object> order,List<Map<String,Object>> ledgers) {
        if(!"COMMITTED".equals(order.get("attemptStatus")) || !Objects.equals(order.get("withdrawalNo"),order.get("attemptWithdrawalNo"))
                || number(order,"userId")!=number(order,"attemptUserId") || !Objects.equals(order.get("idempotencyKey"),order.get("attemptKey"))) return false;
        return reservationLedgers(order,ledgers) && ledgers.stream().noneMatch(row -> Objects.toString(row.get("bizNo")).endsWith(":USDT:PENALTY_FEE"));
    }

    private boolean cashReservation(Map<String,Object> order,List<Map<String,Object>> ledgers) {
        if(reservation(order,ledgers))return true;
        if(!reservationLedgers(order,ledgers))return false;
        String no=Objects.toString(order.get("withdrawalNo"));
        if("BANK-VND".equals(order.get("chain"))) {
            var bank=mapper.fundBankReservation(no);
            return bank!=null && number(bank,"userId")==number(order,"userId")
                    && no.equals(bank.get("withdrawalNo")) && same(bank.get("amount"),decimal(order.get("amount")))
                    && same(bank.get("fee"),decimal(order.get("actualFee"))) && same(bank.get("net"),decimal(order.get("netReceive")))
                    && decimal(order.get("nexBurned")).signum()==0;
        }
        var audits=mapper.fundSubmissionAudits(no);
        if(audits==null || audits.size()!=1)return false;
        var audit=audits.get(0);
        if(number(audit,"userId")!=number(order,"userId") || number(audit,"actorId")!=number(order,"userId")
                || !"USER".equals(audit.get("actorType")) || !"SUCCESS".equals(audit.get("result")))return false;
        try {
            var detail=json.readTree(Objects.toString(audit.get("detailJson"),""));
            return detail!=null && detail.isObject() && no.equals(detail.path("withdrawal_id").asText()) && Objects.equals(order.get("chain"),detail.path("chain").asText())
                    && detail.path("amount_usdt").isNumber() && detail.path("amount_usdt").decimalValue().compareTo(decimal(order.get("amount")))==0
                    && detail.path("net_receive").isNumber() && detail.path("net_receive").decimalValue().compareTo(decimal(order.get("netReceive")))==0
                    && detail.path("actual_fee").isNumber() && detail.path("actual_fee").decimalValue().compareTo(decimal(order.get("actualFee")))==0
                    && detail.path("nex_burned").isNumber() && detail.path("nex_burned").decimalValue().compareTo(decimal(order.get("nexBurned")))==0;
        } catch(com.fasterxml.jackson.core.JsonProcessingException malformed){return false;}
    }

    static boolean reservationLedgers(Map<String,Object> order,List<Map<String,Object>> ledgers) {
        if(!(order.get("amount") instanceof Number) || !(order.get("nexBurned") instanceof Number)
                || !(order.get("actualFee") instanceof Number) || !(order.get("netReceive") instanceof Number))return false;
        if(decimal(order.get("amount")).signum()<=0 || decimal(order.get("netReceive")).signum()<=0
                || decimal(order.get("actualFee")).signum()<0 || decimal(order.get("nexBurned")).signum()<0
                || decimal(order.get("netReceive")).add(decimal(order.get("actualFee"))).compareTo(decimal(order.get("amount")))!=0)return false;
        String no=Objects.toString(order.get("withdrawalNo"));long user=number(order,"userId");
        var expected=new HashMap<String,BigDecimal>();
        expected.put(no+":USDT:PRINCIPAL",decimal(order.get("netReceive")));
        expected.put(no+":NEX:OFFSET",decimal(order.get("nexBurned")));
        expected.entrySet().removeIf(e -> e.getValue().signum()==0);
        if(expected.values().stream().anyMatch(v -> v.signum()<0) || ledgers==null)return false;
        var found=new HashSet<String>();BigDecimal usdt=BigDecimal.ZERO,fee=BigDecimal.ZERO;
        for(var row:ledgers) {
            String biz=Objects.toString(row.get("bizNo"));String asset=biz.endsWith(":NEX:OFFSET")?"UVT":"USDT";
            String type=biz.endsWith(":PRINCIPAL")?"WITHDRAW_NET_PRINCIPAL":biz.endsWith(":BANK_FEE")?"WITHDRAW_BANK_FEE"
                    :biz.endsWith(":NETWORK_FEE")?"WITHDRAW_NETWORK_FEE":biz.endsWith(":PENALTY_FEE")?"WITHDRAW_PENALTY_FEE":"WITHDRAW_FEE_OFFSET";
            boolean feeComponent="BANK-VND".equals(order.get("chain"))?biz.equals(no+":USDT:BANK_FEE"):
                    biz.equals(no+":USDT:NETWORK_FEE") || biz.equals(no+":USDT:PENALTY_FEE");
            if(!found.add(biz) || !(row.get("amount") instanceof Number) || decimal(row.get("amount")).signum()<=0
                    || (!feeComponent && (!expected.containsKey(biz) || !same(row.get("amount"),expected.get(biz))))
                    || number(row,"userId")!=user || !asset.equals(row.get("asset")) || !type.equals(row.get("bizType"))
                    || !"OUT".equals(row.get("direction")) || !"POSTED".equals(row.get("status")) || flag(row,"isDeleted")!=0) return false;
            if("USDT".equals(asset))usdt=usdt.add(decimal(row.get("amount")));
            if(feeComponent)fee=fee.add(decimal(row.get("amount")));
        }
        return found.containsAll(expected.keySet()) && same(order.get("amount"),usdt) && same(order.get("actualFee"),fee);
    }

    static Map<String,Object> cashTrace(Map<String,Object> before,Map<String,Object> after,long customer,
            BigDecimal usdt,BigDecimal nex,BigDecimal pending,List<Map<String,Object>> rows) {
        if(rows==null || rows.size()!=1 || number(after,"revision")!=number(before,"revision")+1
                || !Objects.equals(before.get("incarnation"),after.get("incarnation")) || number(before,"id")!=number(after,"id"))throw failure("CASH_TRACE_MISMATCH");
        var row=rows.get(0);
        if(!"CHANGE".equals(row.get("observation")) || !Objects.equals(before.get("incarnation"),row.get("incarnation"))
                || number(row,"revision")!=number(after,"revision") || number(row,"oldUserId")!=customer || number(row,"newUserId")!=customer
                || number(row,"oldId")!=number(before,"id") || number(row,"newId")!=number(after,"id")
                || number(row,"oldVersion")!=number(before,"version") || number(row,"newVersion")!=number(after,"version")
                || number(after,"version")!=number(before,"version")+1)throw failure("CASH_TRACE_MISMATCH");
        var deltas=Map.of("usdtAvailable",usdt,"nexAvailable",nex,"pendingWithdraw",pending,"cregisRiskHeld",BigDecimal.ZERO);
        for(var field:deltas.keySet()) {
            String cap=Character.toUpperCase(field.charAt(0))+field.substring(1);
            if(!same(row.get("old"+cap),decimal(before.get(field))) || !same(row.get("new"+cap),decimal(after.get(field)))
                    || decimal(after.get(field)).subtract(decimal(before.get(field))).compareTo(deltas.get(field))!=0)throw failure("CASH_TRACE_MISMATCH");
        }
        for(String field:List.of("sandbox","isDeleted")) {
            String cap=Character.toUpperCase(field.charAt(0))+field.substring(1);
            if(flag(row,"old"+cap)!=flag(before,field) || flag(row,"new"+cap)!=flag(after,field)
                    || flag(before,field)!=flag(after,field))throw failure("CASH_TRACE_MISMATCH");
        }
        return row;
    }
    private Map<String,Object> cashTrace(Map<String,Object> before,Map<String,Object> after,long customer,BigDecimal usdt,BigDecimal nex,BigDecimal pending) {
        return cashTrace(before,after,customer,usdt,nex,pending,journal.journal(Objects.toString(before.get("incarnation")),number(before,"revision"),number(after,"revision")));
    }
    private Map<String,Object> wallet(long customer) {
        var rows=journal.lockWallet(customer);
        if(rows==null || rows.size()!=1 || number(rows.get(0),"userId")!=customer || flag(rows.get(0),"isDeleted")!=0)throw failure("WALLET_IDENTITY_MISMATCH");
        return rows.get(0);
    }
    public static String operationKey(String no,String phase) {
        try{return "WITHDRAWAL:"+phase+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(no.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private String encode(Object value) {
        try{return json.writeValueAsString(normalize(value));}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException(ex);}
    }
    private Object normalize(Object value) {
        if(value instanceof LocalDateTime at)return at.toString();
        if(value instanceof java.sql.Timestamp at)return at.toLocalDateTime().toString();
        if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),normalize(v)));return sorted;}
        if(value instanceof Collection<?> list)return list.stream().map(this::normalize).toList();
        return value;
    }
    private static LocalDateTime time(Object value){return value instanceof java.sql.Timestamp at?at.toLocalDateTime():(LocalDateTime)value;}
    private static BigDecimal nz(BigDecimal value){return value==null?BigDecimal.ZERO:value;}
    private static BigDecimal decimal(Object value){if(!(value instanceof Number))throw failure("MISSING_AMOUNT");return new BigDecimal(value.toString());}
    private static boolean same(Object value,BigDecimal expected){return value instanceof Number && decimal(value).compareTo(expected)==0;}
    private static long number(Map<String,Object> map,String key){Object value=map.get(key);return value instanceof Number n?n.longValue():Long.MIN_VALUE;}
    private static IllegalStateException failure(String reason){return new IllegalStateException("WITHDRAWAL_FUND_"+reason);}
}
