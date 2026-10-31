package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.finance.cregis.CregisReorgResolutionService;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.domain.SupportFundReplay.Stamp;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Source-side validation for immutable reorg compensation receipts, separate from cash-revision verification. */
final class SupportFundCregisReorgEvidence {
    static Stamp verify(SupportFundJournalBindingService codec,Map<String,Object> op,JsonNode source,JsonNode proof,
                        List<Event> events,List<Map<String,Object>> actual) {
        String no=String.valueOf(op.get("operationKey"));
        var matches=actual.stream().filter(row->no.equals(row.get("operationNo"))).toList();
        require(matches.size()==1);var stored=matches.get(0);
        require("SUPPORT_CREGIS_REORG".equals(op.get("businessType")) && "nx_cregis_reorg_operation".equals(op.get("sourceTable"))
                && no.equals(op.get("sourceKey")) && number(stored,"auditCount")==1
                && source.equals(codec.readCurrentProof(String.valueOf(stored.get("snapshotJson"))))
                && proof.equals(codec.readCurrentProof(String.valueOf(stored.get("proofJson"))))
                && codec.canonical(codec.readCurrentProof(String.valueOf(stored.get("eventsJson")))).equals(codec.canonical(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(events)))
                && "CREGIS_REORG_V1".equals(source.path("schemaVersion").asText()) && "CREGIS_REORG_V1".equals(proof.path("schemaVersion").asText()));
        var row=source.path("row");long user=row.path("userId").longValue(),incident=row.path("id").longValue();
        require(user==number(stored,"userId") && row.path("eventId").longValue()==number(stored,"eventId")
                && CregisReorgResolutionService.operationNo(incident,number(stored,"resolutionVersion")).equals(no)
                && source.path("phase").asText().equals(stored.get("phase")) && op.get("phase").equals(stored.get("phase"))
                && ("DEPOSIT:"+row.path("ledgerId").longValue()).equals(stored.get("originalFactId")));
        BigDecimal net=money(row,"netAmount");require(net.signum()>0 && net.compareTo(new BigDecimal(String.valueOf(stored.get("netAmount"))))==0);
        String origin=CregisReorgResolutionService.depositOrigin(new com.fasterxml.jackson.databind.ObjectMapper().convertValue(row,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        BigDecimal available=money(proof.path("cashAfter"),"usdtAvailable").subtract(money(proof.path("cashBefore"),"usdtAvailable"));
        BigDecimal risk=money(proof.path("cashAfter"),"cregisRiskHeld").subtract(money(proof.path("cashBefore"),"cregisRiskHeld"));
        Account account=new Account(user,"USDT","available"),held=new Account(user,"USDT","risk:CR-"+incident);
        String phase=String.valueOf(stored.get("phase"));
        require(number(stored,"reserveCount")== (Set.of("REVERSE","RESTORE").contains(phase)?1:0));
        switch(phase) {
            case "HOLD" -> {
                require(events.size()==1 && events.get(0).action()==Action.MOVE && account.equals(events.get(0).from())
                    && held.equals(events.get(0).to()) && events.get(0).amount().compareTo(money(row,"heldAmount"))==0
                    && available.compareTo(events.get(0).amount().negate())==0 && risk.compareTo(events.get(0).amount())==0);
            }
            case "RELEASE_HOLD" -> {
                require(events.size()==1 && events.get(0).action()==Action.MOVE && held.equals(events.get(0).from())
                    && account.equals(events.get(0).to()) && available.compareTo(events.get(0).amount())==0 && risk.compareTo(available.negate())==0);
            }
            case "REVERSE" -> {
                require(!events.isEmpty() && events.get(events.size()-1).action()==Action.REVOKE_DEPOSIT
                    && origin.equals(events.get(events.size()-1).reference()) && net.compareTo(events.get(events.size()-1).amount())==0);
                BigDecimal collected=BigDecimal.ZERO,fromAvailable=BigDecimal.ZERO,fromRisk=BigDecimal.ZERO;
                for(int i=0;i<events.size()-1;i++) {
                    var event=events.get(i);require(event.action()==Action.REORG_DEBIT && origin.equals(event.reference()));
                    if(account.equals(event.from()))fromAvailable=fromAvailable.add(event.amount());
                    else {require(held.equals(event.from()));fromRisk=fromRisk.add(event.amount());}
                    collected=collected.add(event.amount());
                }
                require(available.compareTo(fromAvailable.negate())==0 && risk.compareTo(fromRisk.negate())==0
                    && collected.compareTo(money(row,"recoveredAmount"))==0 && collected.add(money(row,"outstandingDebt")).compareTo(net)==0);
            }
            case "COLLECT_DEBT" -> require(events.size()==1 && events.get(0).action()==Action.REORG_DEBIT
                && account.equals(events.get(0).from()) && origin.equals(events.get(0).reference())
                && available.compareTo(events.get(0).amount().negate())==0 && risk.signum()==0);
            case "RESTORE" -> {
                require(!events.isEmpty() && events.get(0).action()==Action.RESTORE_DEPOSIT && origin.equals(events.get(0).reference())
                    && net.compareTo(events.get(0).amount())==0 && risk.signum()==0 && money(row,"outstandingDebt").signum()==0);
                BigDecimal refunded=BigDecimal.ZERO;
                for(int i=1;i<events.size();i++) {var event=events.get(i);require(event.action()==Action.REORG_REFUND && account.equals(event.to()));refunded=refunded.add(event.amount());}
                require(refunded.compareTo(available)==0 && refunded.compareTo(money(row,"recoveredAmount"))==0);
            }
            default -> throw new IllegalArgumentException("Unsupported reorg phase");
        }
        require(money(proof.path("cashAfter"),"nexAvailable").compareTo(money(proof.path("cashBefore"),"nexAvailable"))==0
            && money(proof.path("cashAfter"),"pendingWithdraw").compareTo(money(proof.path("cashBefore"),"pendingWithdraw"))==0);
        Object at=stored.get("createdAt");LocalDateTime time=at instanceof LocalDateTime value?value:LocalDateTime.parse(String.valueOf(at).replace(' ','T'));
        return new Stamp(no,time,DateTimeFormatConfig.BUSINESS_ZONE.getId(),6);
    }
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static BigDecimal money(JsonNode row,String key){require(row.path(key).isNumber());return row.path(key).decimalValue();}
    private static void require(boolean condition){if(!condition)throw new IllegalArgumentException("CREGIS_REORG_SOURCE_MISMATCH");}
}
