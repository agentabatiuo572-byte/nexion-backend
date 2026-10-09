package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.finance.mapper.SupportFundsReadMapper;
import ffdd.opsconsole.finance.mapper.SupportFundsReadMapper.WalletRow;
import ffdd.opsconsole.finance.mapper.SupportFundsReadMapper.WithdrawalRow;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Joins the caller's RR transaction. No transaction annotation, locks, financial writes, or totals. */
@ApplicationService
public class SupportFundsReadService implements SupportFundsReadFacade {
    private final SupportFundsReadMapper mapper;
    public SupportFundsReadService(SupportFundsReadMapper mapper) {this.mapper=Objects.requireNonNull(mapper);}

    @Override public Snapshot readCurrent(Collection<Long> customerIds) {
        if(customerIds==null || customerIds.stream().anyMatch(id->id==null || id<=0))throw invalid("INVALID_SUPPORT_FUNDS_SCOPE");
        var ids=List.copyOf(new TreeSet<>(customerIds));
        if(ids.isEmpty())return new Snapshot(ids,new WalletRead(List.of(),ReadState.READY,List.of(),null),
            withdrawalRead(List.of(),ReadState.READY,List.of(),null),DateTimeFormatConfig.BUSINESS_ZONE.getId());
        if(!TransactionSynchronizationManager.isActualTransactionActive()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),Connection.TRANSACTION_REPEATABLE_READ))
            throw invalid("SUPPORT_FUNDS_REPEATABLE_READ_REQUIRED");
        return new Snapshot(ids,readWallets(ids),readWithdrawals(ids),DateTimeFormatConfig.BUSINESS_ZONE.getId());
    }

    private WalletRead readWallets(List<Long> ids) {
        List<WalletRow> rows;
        try {rows=mapper.wallets(ids);}
        catch(DataAccessException ex) {return new WalletRead(ids.stream().map(id->unknownWallet(id,Reason.WALLET_READ_FAILED)).toList(),
            ReadState.FAILED,List.of(Reason.WALLET_READ_FAILED),null);}
        if(rows==null)throw invalid("INVALID_SUPPORT_FUNDS_WALLET_RESPONSE");
        // Scope is checked for the whole returned batch before identity/quality filtering.
        var scope=new HashSet<>(ids);
        if(rows.stream().anyMatch(r->r!=null && r.customerId()!=null && !scope.contains(r.customerId())))throw invalid("INVALID_SUPPORT_FUNDS_SCOPE");
        var byCustomer=new TreeMap<Long,WalletRow>();var byId=new TreeMap<Long,WalletRow>();LocalDateTime evaluated=null;
        for(var row:rows) {
            if(row==null || row.customerId()==null || row.customerId()<=0 || row.walletId()==null || row.walletId()<=0)throw invalid("INVALID_SUPPORT_FUNDS_WALLET_ROW");
            var prior=byCustomer.putIfAbsent(row.customerId(),row);var priorId=byId.putIfAbsent(row.walletId(),row);
            if(prior!=null && !prior.equals(row) || priorId!=null && !priorId.equals(row))throw invalid("CONFLICTING_SUPPORT_FUNDS_WALLET_ROW");
            evaluated=observation(evaluated,row.evaluatedDbAt());
        }
        var evidence=new ArrayList<WalletEvidence>();var reasons=new TreeSet<Reason>();
        for(long customer:ids) {
            var row=byCustomer.get(customer);
            if(row==null){evidence.add(unknownWallet(customer,Reason.WALLET_MISSING));reasons.add(Reason.WALLET_MISSING);continue;}
            var missing=new ArrayList<Reason>();
            if(row.version()==null || row.version()<0)missing.add(Reason.WALLET_IDENTITY_UNVERIFIED);
            if(!nonnegative(row.usdtAvailable()))missing.add(Reason.USDT_BALANCE_UNVERIFIED);
            if(!nonnegative(row.nexAvailable()))missing.add(Reason.NEX_BALANCE_UNVERIFIED);
            if(row.evaluatedDbAt()==null || row.updatedAt()==null || row.updatedAt().isAfter(row.evaluatedDbAt()))missing.add(Reason.SOURCE_OBSERVATION_UNVERIFIED);
            reasons.addAll(missing);evidence.add(new WalletEvidence(customer,row.walletId(),row.version(),row.usdtAvailable(),row.nexAvailable(),row.updatedAt(),
                missing.isEmpty()?EvidenceState.READY:EvidenceState.UNKNOWN,missing));
        }
        long known=evidence.stream().filter(e->e.state()==EvidenceState.READY).count();
        return new WalletRead(evidence,known==evidence.size()?ReadState.READY:known==0?ReadState.UNKNOWN:ReadState.PARTIAL,List.copyOf(reasons),evaluated);
    }

    private WithdrawalRead readWithdrawals(List<Long> ids) {
        List<WithdrawalRow> rows;
        try {rows=mapper.withdrawals(ids);}
        catch(DataAccessException ex) {return withdrawalRead(List.of(),ReadState.FAILED,List.of(Reason.WITHDRAWAL_READ_FAILED),null);}
        if(rows==null)throw invalid("INVALID_SUPPORT_FUNDS_WITHDRAWAL_RESPONSE");
        var scope=new HashSet<>(ids);
        if(rows.stream().anyMatch(r->r!=null && r.customerId()!=null && !scope.contains(r.customerId())))throw invalid("INVALID_SUPPORT_FUNDS_SCOPE");
        var unique=new TreeMap<Long,WithdrawalRow>();LocalDateTime evaluated=null;
        for(var row:rows) {
            if(row==null || row.customerId()==null || row.customerId()<=0 || row.withdrawalId()==null || row.withdrawalId()<=0)throw invalid("INVALID_SUPPORT_FUNDS_WITHDRAWAL_ROW");
            var prior=unique.putIfAbsent(row.withdrawalId(),row);
            if(prior!=null && !prior.equals(row))throw invalid("CONFLICTING_SUPPORT_FUNDS_WITHDRAWAL_ROW");
            evaluated=observation(evaluated,row.evaluatedDbAt());
        }
        var evidence=new ArrayList<WithdrawalEvidence>();var reasons=new TreeSet<Reason>();
        for(var row:unique.values()) {
            var missing=new ArrayList<Reason>();String canonical=D2WithdrawalStateMachine.canonical(row.status());
            WithdrawalState state=withdrawalState(canonical);
            if(state==WithdrawalState.UNKNOWN)missing.add(Reason.UNKNOWN_WITHDRAWAL_STATUS);
            if(row.currency()==null || !row.currency().matches("[A-Z][A-Z0-9_]{0,15}") || !nonnegative(row.principal()) || row.principal().signum()==0)
                missing.add(Reason.WITHDRAWAL_PRINCIPAL_UNVERIFIED);
            if(!nonnegative(row.actualFee()) || !nonnegative(row.net()) || row.principal()==null
                    || row.actualFee()!=null && row.net()!=null && row.actualFee().add(row.net()).compareTo(row.principal())!=0)
                missing.add(Reason.WITHDRAWAL_SETTLEMENT_UNVERIFIED);
            if(row.evaluatedDbAt()==null || row.updatedAt()==null || row.updatedAt().isAfter(row.evaluatedDbAt())
                    || row.completedAt()!=null && (row.completedAt().isAfter(row.updatedAt()) || row.completedAt().isAfter(row.evaluatedDbAt()))
                    || state==WithdrawalState.SUCCESS && row.completedAt()==null || state==WithdrawalState.PROCESSING && row.completedAt()!=null)
                missing.add(Reason.WITHDRAWAL_TIME_UNVERIFIED);
            reasons.addAll(missing);evidence.add(new WithdrawalEvidence(row.withdrawalId(),row.customerId(),row.currency(),row.principal(),row.actualFee(),row.net(),
                row.status(),canonical,missing.isEmpty()?state:WithdrawalState.UNKNOWN,row.completedAt(),row.updatedAt(),missing));
        }
        long known=evidence.stream().filter(e->e.state()!=WithdrawalState.UNKNOWN).count();
        return withdrawalRead(evidence,known==evidence.size()?ReadState.READY:known==0?ReadState.UNKNOWN:ReadState.PARTIAL,List.copyOf(reasons),evaluated);
    }

    private static WithdrawalState withdrawalState(String canonical) {
        // Reuse the canonical D2 states/aliases; the processing family follows existing supportTotals.
        return switch(canonical) {
            case D2WithdrawalStateMachine.CONFIRMED -> WithdrawalState.SUCCESS;
            case D2WithdrawalStateMachine.SUBMITTED,D2WithdrawalStateMachine.REVIEW_PENDING,D2WithdrawalStateMachine.EXTENDED_HOLD,
                D2WithdrawalStateMachine.REVIEW_PASSED,D2WithdrawalStateMachine.PROCESSING,D2WithdrawalStateMachine.SENT -> WithdrawalState.PROCESSING;
            case D2WithdrawalStateMachine.REVIEW_REJECTED,D2WithdrawalStateMachine.ADDRESS_INVALID,D2WithdrawalStateMachine.TX_FAILED,
                D2WithdrawalStateMachine.REFUNDED -> WithdrawalState.NOT_SUCCESSFUL;
            case D2WithdrawalStateMachine.FROZEN -> WithdrawalState.HELD;
            case D2WithdrawalStateMachine.TX_ORPHANED -> WithdrawalState.RECOVERY;
            default -> WithdrawalState.UNKNOWN;
        };
    }
    private static boolean nonnegative(BigDecimal value) {
        return value!=null && value.signum()>=0 && value.stripTrailingZeros().scale()<=6 && value.precision()-value.scale()<=12;
    }
    private static LocalDateTime observation(LocalDateTime previous,LocalDateTime next) {
        if(previous!=null && next!=null && !previous.equals(next))throw invalid("INVALID_SUPPORT_FUNDS_OBSERVATION_TIME");
        return previous==null?next:previous;
    }
    private static WalletEvidence unknownWallet(long customer,Reason reason) {
        return new WalletEvidence(customer,null,null,null,null,null,EvidenceState.UNKNOWN,List.of(reason));
    }
    private static WithdrawalRead withdrawalRead(List<WithdrawalEvidence> rows,ReadState state,List<Reason> reasons,LocalDateTime at) {
        return new WithdrawalRead(rows,state,reasons,at,CoverageState.UNKNOWN,CoverageState.UNKNOWN);
    }
    private static IllegalStateException invalid(String reason) {return new IllegalStateException(reason);}
}
