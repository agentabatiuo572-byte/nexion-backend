package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.team.facade.SupportDirectReferralReadFacade;
import ffdd.opsconsole.team.facade.SupportDirectReferralReadFacade.Source;
import ffdd.opsconsole.team.mapper.SupportDirectReferralReadMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Captures the original recovery transaction; never changes its cash or entitlement algorithm. */
@Service
@RequiredArgsConstructor
public class SupportDirectReferralFundAdapter {
    private final SupportFundJournalFacade journal;
    private final SupportDirectReferralReadFacade sources;
    private final SupportDirectReferralReadMapper mapper;
    private final ObjectMapper json;
    public static final String SCHEMA="SUPPORT_DIRECT_REFERRAL_V1";
    public static final class Token {
        private final SupportDirectReferralFundAdapter owner;private final SupportFundJournalFacade.Before before;
        private final Source source;private final Map<String,Object> wallet;private final long predecessor;private final String phase;
        private Token(SupportDirectReferralFundAdapter owner,SupportFundJournalFacade.Before before,Source source,Map<String,Object> wallet,long predecessor,String phase) {
            this.owner=owner;this.before=before;this.source=source;this.wallet=wallet;this.predecessor=predecessor;this.phase=phase;
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Token begin(String key) {
        return begin(key,key.startsWith("S:")?"RECOVERY":"REISSUE");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Token beginFailure(String key) {return begin(key,"FAILURE");}
    private Token begin(String key,String phase) {
        Source source=one(key);long customer=SupportDirectReferralReadFacade.customer(source);
        var wallets=mapper.wallets(customer);if(wallets.size()!=1)return null;
        var before=journal.begin(List.of(customer));var wallet=SupportDirectReferralReadFacade.immutable(wallets.get(0));Long previous=mapper.previousOperation(customer);
        return new Token(this,before,source,wallet,previous==null?0:previous,phase);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void recorded(Token token) {
        if(token==null||token.owner!=this)throw new IllegalArgumentException("Invalid Direct capture token");
        Source after=one(token.source.key());long user=SupportDirectReferralReadFacade.customer(after);var wallet=wallet(user);
        String phase=token.phase,table=after.key().startsWith("S:")?"nx_direct_referral_settlement":"nx_unilevel_event_recovery";
        String key=SupportDirectReferralReadFacade.operationKey(after.key(),token.predecessor,after);
        var events=SupportDirectReferralReadFacade.events(token.source,after,key);
        var rows=mapper.journal((String)token.wallet.get("incarnation"),SupportDirectReferralReadFacade.number(token.wallet.get("revision")),SupportDirectReferralReadFacade.number(wallet.get("revision")));
        var trace=new ArrayList<Map<String,Object>>();
        for(var row:rows)trace.add(Map.of("sourceId",table+":"+after.key(),"eventIds",events.stream().map(e->e.id()).toList(),"journal",row));
        var source=new LinkedHashMap<String,Object>();source.put("schemaVersion",SCHEMA);source.put("before",token.source);source.put("after",after);
        source.put("phaseAt",after.key().startsWith("S:")?after.subject().get("updated_at"):after.recovery().get("updated_at"));
        var proof=new LinkedHashMap<String,Object>();proof.put("schemaVersion",SCHEMA);proof.put("cashBefore",token.wallet);proof.put("cashAfter",wallet);
        proof.put("cashTrace",trace);proof.put("previousOperationId",token.predecessor);
        journal.bind(token.before,new SupportFundJournalFacade.ReceiptInput(key,"SUPPORT_DIRECT_REFERRAL",phase,table,after.key(),"PRODUCTION",encode(events),encode(source),encode(proof)));
    }
    private Source one(String key) {var found=sources.readByKeys(List.of(key));if(found.size()!=1||!key.equals(found.get(0).key()))throw new IllegalArgumentException("Ambiguous Direct recovery source");return found.get(0);}
    private Map<String,Object> wallet(long user) {var rows=mapper.wallets(user);if(rows.size()!=1)throw new IllegalArgumentException("Ambiguous Direct wallet");return SupportDirectReferralReadFacade.immutable(rows.get(0));}
    private String encode(Object value) {try{return json.writeValueAsString(normalize(value));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("Invalid Direct source snapshot",e);}}
    private Object normalize(Object value) {
        if(value instanceof Source s)return Map.of("key",s.key(),"subject",normalize(s.subject()),"events",normalize(s.events()),"entries",normalize(s.entries()),"recovery",s.recovery()==null?json.nullNode():normalize(s.recovery()),"ledgers",normalize(s.ledgers()));
        if(value instanceof Timestamp t)return t.toLocalDateTime().toString();if(value instanceof LocalDateTime t)return t.toString();
        if(value instanceof Map<?,?> map){var result=new LinkedHashMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),normalize(v)));return result;}
        if(value instanceof Collection<?> rows)return rows.stream().map(this::normalize).toList();return value;
    }
}
