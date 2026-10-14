package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.facade.SupportFundAuthBirthFacade;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** AUTH account proof is trusted; wallet proof is always read here in its owning domain. */
@ApplicationService
public class SupportFundAuthBirthAdapter implements SupportFundAuthBirthFacade {
    private final SupportFundJournalMapper mapper;
    private final SupportFundJournalFacade binding;
    private final ObjectMapper json;
    public SupportFundAuthBirthAdapter(SupportFundJournalMapper mapper,SupportFundJournalFacade binding,ObjectMapper json) {
        this.mapper=mapper;this.binding=binding;this.json=json;
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Outcome record(AuthenticatedRegistrationFacts f) {
        if (f==null || f.customerId()<=0 || f.customerCreatedAt()==null || f.outboxId()<=0 || f.eventTs()==null
                || f.schemaRevision()<=0 || f.eventId()==null || !f.eventId().matches("[0-9a-f]{32}")
                || f.expectedWalletIncarnation()==null || !f.expectedWalletIncarnation().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Authenticated registration facts required");
        var wallets=mapper.lockWallet(f.customerId());
        if (wallets==null || wallets.size()!=1) throw new IllegalStateException("AUTH_FUND_WALLET_MISSING_OR_AMBIGUOUS");
        Map<String,Object> wallet=wallets.get(0);
        if (!Long.valueOf(f.customerId()).equals(wallet.get("userId"))) throw new IllegalStateException("AUTH_FUND_WALLET_CUSTOMER_MISMATCH");
        // CLIENT_FOUND_ROWS may report 1 for an orphan upsert: only the private INSERT nonce proves new wallet creation.
        if (!f.expectedWalletIncarnation().equals(wallet.get("incarnation"))) return Outcome.UNKNOWN;
        if (!(wallet.get("id") instanceof Number id) || id.longValue()<=0) throw new IllegalStateException("AUTH_FUND_WALLET_ID_INVALID");
        List<Map<String,Object>> rows=mapper.journal(f.expectedWalletIncarnation(),-1,0);
        if (rows==null) throw new IllegalStateException("AUTH_FUND_JOURNAL_READ_FAILED");
        if (rows.size()!=1) return Outcome.UNKNOWN;
        Map<String,Object> birth=rows.get(0);
        if (birth==null) throw new IllegalStateException("AUTH_FUND_JOURNAL_ROW_INVALID");
        if ("NEW_UNPROVEN".equals(birth.get("observation"))) return Outcome.UNKNOWN;
        // Existing guard proves full zero tuple, identity, protocol and the complete unique lifetime before accepting.
        var before=binding.beginBirth(f.customerId());
        Map<String,Object> source=new LinkedHashMap<>();
        source.put("creator","AUTH_NEW_ACCOUNT_REGISTRATION"); source.put("customerId",f.customerId());
        source.put("customerCreatedAt",f.customerCreatedAt().toString()); source.put("sandbox",0);source.put("isDeleted",0);
        source.put("eventId",f.eventId());source.put("outboxId",f.outboxId());source.put("eventTs",f.eventTs().toString());
        source.put("aggregateType","USER_REGISTRATION");source.put("eventName","auth.register_completed");
        source.put("schemaRevision",f.schemaRevision());source.put("actualUserInsert",1);
        source.put("expectedWalletIncarnation",f.expectedWalletIncarnation());
        source.put("wallet",snapshot(wallet));source.put("birthJournal",snapshot(birth));
        Map<String,Object> proof=Map.of("protocol","AUTH_FUND_BIRTH_V1","sourceEnvironment","PRODUCTION",
            "actualAuthInsertTokenVerified",true,"registrationOutboxReadbackVerified",true,"customerIds",List.of(f.customerId()));
        var result=binding.bind(before,new SupportFundJournalFacade.ReceiptInput("AUTH_FUND_BIRTH:"+f.customerId(),
            "AUTH_FUND_BIRTH","REGISTERED","nx_event_outbox",f.eventId(),"PRODUCTION","[]",encode(source),encode(proof)));
        if (result.journalLegs()!=1) throw new IllegalStateException("AUTH_FUND_BIRTH_LEG_MISMATCH");
        return Outcome.BOUND;
    }

    private static Map<String,Object> snapshot(Map<String,Object> row) {
        Map<String,Object> copy=new LinkedHashMap<>();
        row.forEach((key,value)->copy.put(key,value instanceof LocalDateTime t?t.toString():value instanceof Timestamp t?t.toLocalDateTime().toString():value));
        return copy;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("AUTH_FUND_BIRTH_SERIALIZATION_FAILED",ex); }
    }
}
