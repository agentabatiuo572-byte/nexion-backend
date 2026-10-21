package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.cregis.CregisReorgResolutionService;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportFundCregisReorgEvidenceTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final SupportFundJournalBindingService codec=new SupportFundJournalBindingService(mock(SupportFundJournalMapper.class),mock(DataSource.class),json);
    final Map<String,Object> row=map("id",1L,"eventId",2L,"projectId",42L,"userId",7L,"ledgerId",10L,"netAmount",n("100"),"heldAmount",n("0"),"recoveredAmount",n("40"),"outstandingDebt",n("60"));
    Map<String,Object> source,proof,op,stored;List<Event> events;
    @BeforeEach void setup()throws Exception {
        String no="CR-REORG-1:1",origin=CregisReorgResolutionService.depositOrigin(row);
        events=List.of(new Event(no+":HELD",Action.REORG_DEBIT,new Account(7,"USDT","risk:CR-1"),null,origin,n("40"),null),new Event(no+":REVOKE",Action.REVOKE_DEPOSIT,null,null,origin,n("100"),null));
        source=map("schemaVersion","CREGIS_REORG_V1","row",row,"phase","REVERSE","operationNo",no);
        proof=map("schemaVersion","CREGIS_REORG_V1","cashBefore",cash("40"),"cashAfter",cash("0"));
        stored=map("operationNo",no,"incidentId",1L,"resolutionVersion",1L,"phase","REVERSE","originalFactId","DEPOSIT:10","userId",7L,"eventId",2L,"netAmount",n("100"),"auditCount",1,"reserveCount",1,"createdAt",LocalDateTime.of(2026,10,22,0,0));
        refresh();op=map("operationKey",no,"businessType","SUPPORT_CREGIS_REORG","sourceTable","nx_cregis_reorg_operation","sourceKey",no,"phase","REVERSE");
    }
    @Test void exactPersistedOriginalPairedReversalIsVerifiable()throws Exception {assertThat(verify().sourceReference()).isEqualTo("CR-REORG-1:1");}
    @Test void forgedSnapshotAndUnpairedReserveOrAuditCannotBeReady()throws Exception {
        stored.put("reserveCount",0);assertThatThrownBy(this::verify).hasMessage("CREGIS_REORG_SOURCE_MISMATCH");stored.put("reserveCount",1);
        stored.put("auditCount",0);assertThatThrownBy(this::verify).hasMessage("CREGIS_REORG_SOURCE_MISMATCH");stored.put("auditCount",1);
        row.put("recoveredAmount",n("50"));refresh();assertThatThrownBy(this::verify).hasMessage("CREGIS_REORG_SOURCE_MISMATCH");
    }
    @Test void anotherUsersEventOrOriginalCannotSubstitute()throws Exception {stored.put("userId",8L);assertThatThrownBy(this::verify).hasMessage("CREGIS_REORG_SOURCE_MISMATCH");stored.put("userId",7L);stored.put("originalFactId","DEPOSIT:11");assertThatThrownBy(this::verify).hasMessage("CREGIS_REORG_SOURCE_MISMATCH");}
    ffdd.opsconsole.finance.domain.SupportFundReplay.Stamp verify()throws Exception{return SupportFundCregisReorgEvidence.verify(codec,op,codec.read(json.writeValueAsString(source)),codec.read(json.writeValueAsString(proof)),events,List.of(stored));}
    void refresh()throws Exception {stored.put("snapshotJson",json.writeValueAsString(source));stored.put("proofJson",json.writeValueAsString(proof));stored.put("eventsJson",json.writeValueAsString(events));}
    static Map<String,Object> cash(String risk){return map("usdtAvailable",n("0"),"cregisRiskHeld",n(risk),"nexAvailable",n("0"),"pendingWithdraw",n("0"));}
    static BigDecimal n(String v){return new BigDecimal(v);}
    static Map<String,Object> map(Object... values){var r=new HashMap<String,Object>();for(int i=0;i<values.length;i+=2)r.put((String)values[i],values[i+1]);return r;}
}
