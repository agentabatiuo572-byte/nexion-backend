package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.finance.mapper.CregisExceptionSourceMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Single financial disposition for one fixed USDT-BEP20 Transfer. Caller locks the project gate first. */
@Service @RequiredArgsConstructor
public class CregisExceptionSourceService {
    private final CregisExceptionSourceMapper db;
    private final CregisDepositMapper deposits;
    public record Source(long projectId,long cid,String txid,int logIndex,String address,BigDecimal grossAmount) {
        public Source {
            if(projectId<=0 || cid<=0 || logIndex<0 || txid==null || !txid.matches("(?i)(0x)?[0-9a-f]{64}")
                    || address==null || !address.matches("(?i)0x[0-9a-f]{40}") || CregisAmount.normalizePositive(grossAmount)==null
                    || grossAmount.stripTrailingZeros().scale()>6 || grossAmount.compareTo(new BigDecimal("999999999999.999999"))>0)
                throw new BizException(422,"CREGIS_EXCEPTION_SOURCE_INVALID");
            txid="0x"+txid.replaceFirst("(?i)^0x","").toLowerCase(java.util.Locale.ROOT);
            address=address.toLowerCase(java.util.Locale.ROOT);
        }
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void reserve(Source source,String kind,String reference) {
        require(kind,reference);
        var current=locked(source);
        if("RESERVED".equals(current.get("state")) && owned(current,kind,reference)) return;
        if(!"AVAILABLE".equals(current.get("state"))) throw conflict();
        var event=db.sourceEvent(source);
        if(event!=null && (((Number)event.get("cid")).longValue()!=source.cid() || "CLAIM".equals(kind) || event.get("ledgerId")!=null
                || !java.util.Set.of("DUST_HOLD","REVIEW_HOLD").contains(String.valueOf(event.get("status"))))) throw conflict();
        change(source,current,"RESERVED",kind,reference);
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void ensureReserved(Source source,String kind,String reference) {
        require(kind,reference);
        var current=locked(source);
        if(!"RESERVED".equals(current.get("state")) || !owned(current,kind,reference)) throw conflict();
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void release(Source source,String kind,String reference) {
        require(kind,reference);
        var current=locked(source);
        if(!"RESERVED".equals(current.get("state")) || !owned(current,kind,reference)) throw conflict();
        change(source,current,"AVAILABLE",null,null);
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void complete(Source source,String kind,String reference,String terminal) {
        require(kind,reference);
        if(!java.util.Set.of("CREDITED","REFUNDED").contains(terminal)) throw conflict();
        var current=locked(source);
        if(terminal.equals(current.get("state")) && owned(current,kind,reference)) return;
        if(!"RESERVED".equals(current.get("state")) || !owned(current,kind,reference)) throw conflict();
        change(source,current,terminal,kind,reference);
        if("REFUNDED".equals(terminal)) db.refundEvent(source);
        db.finishObservation(source,"REFUNDED".equals(terminal)?"REFUNDED":"MATCHED");
        db.finishDeliveries(source,terminal);
        db.resolveOwnAlert(source);
        db.retireResolvedAddress(source);
        if(deposits.unresolvedExposure(source.projectId()).compareTo(new BigDecimal("500"))<0)
            deposits.resolveExposureAlert(source.projectId());
    }

    /** Re-mining changes a block-global index; both identities remain financially consumed. */
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void relocateCredited(Source original,Source canonical,String reference) {
        if(reference==null || reference.isBlank() || reference.length()>96 || original.projectId()!=canonical.projectId()
                || original.cid()!=canonical.cid() || !original.txid().equals(canonical.txid())
                || !original.address().equals(canonical.address()) || original.grossAmount().compareTo(canonical.grossAmount())!=0) throw conflict();
        // The caller owns the project gate. The ledger-backed event authenticates legacy source rows too.
        var event=db.sourceEvent(original);
        if(event==null || ((Number)event.get("cid")).longValue()!=original.cid() || event.get("ledgerId")==null
                || !original.txid().equalsIgnoreCase(String.valueOf(event.get("txid")))
                || ((Number)event.get("logIndex")).intValue()!=original.logIndex()
                || !original.address().equalsIgnoreCase(String.valueOf(event.get("address")))
                || original.grossAmount().compareTo((BigDecimal)event.get("grossAmount"))!=0
                || !java.util.Set.of("CREDITED","REORG_HOLD","REORG_INVESTIGATING","REORG_REVERSED","PROVIDER_CONFLICT_HOLD").contains(String.valueOf(event.get("status")))) throw conflict();
        var prior=locked(original);
        if("AVAILABLE".equals(prior.get("state"))) {
            if(db.consumeAlias(original,original.logIndex(),reference,((Number)prior.get("version")).longValue())!=1) throw conflict();
        } else if(!"CREDITED".equals(prior.get("state"))) throw conflict();
        if(original.logIndex()==canonical.logIndex()) return;
        var next=locked(canonical);
        if("CREDITED".equals(next.get("state"))) return;
        if(!"AVAILABLE".equals(next.get("state")) || db.consumeAlias(canonical,original.logIndex(),reference,
                ((Number)next.get("version")).longValue())!=1) throw conflict();
    }
    private Map<String,Object> locked(Source s) {
        if(db.directPurchaseAddress(s))throw new BizException(409,"CREGIS_DIRECT_PURCHASE_SEPARATE_REVIEW");
        db.ensure(s); var row=db.lock(s);
        if(row==null || ((Number)row.get("cid")).longValue()!=s.cid()
                || !s.address().equalsIgnoreCase((String)row.get("address"))
                || s.grossAmount().compareTo((BigDecimal)row.get("grossAmount"))!=0) throw conflict();
        return row;
    }
    private void change(Source s,Map<String,Object> row,String state,String kind,String ref) {
        if(db.transition(s,state,kind,ref,((Number)row.get("version")).longValue())!=1) throw conflict();
    }
    private boolean owned(Map<String,Object> row,String kind,String ref) {
        return Objects.equals(kind,row.get("operationKind")) && Objects.equals(ref,row.get("operationRef"));
    }
    private void require(String kind,String reference) {
        if(!java.util.Set.of("CLAIM","REFUND","AUTO","REVIEW").contains(String.valueOf(kind))
                || reference==null || reference.isBlank() || reference.length()>96) throw conflict();
    }
    private BizException conflict() {return new BizException(409,"CREGIS_EXCEPTION_SOURCE_OCCUPIED");}
}
