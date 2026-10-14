package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.auth.facade.SupportFundBirthReadFacade;
import ffdd.opsconsole.auth.mapper.SupportFundBirthReadMapper;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ApplicationService
public class SupportFundBirthReadService implements SupportFundBirthReadFacade {
    private final SupportFundBirthReadMapper mapper;
    public SupportFundBirthReadService(SupportFundBirthReadMapper mapper) {this.mapper=mapper;}
    @Override public Evidence read(List<Long> ids) {
        if(ids==null || ids.stream().anyMatch(id -> id==null || id<=0))throw new IllegalArgumentException("Explicit positive scope required");
        if(!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
            throw new IllegalStateException("AUTH_FUND_READ_SNAPSHOT_REQUIRED");
        return ids.isEmpty()?new Evidence(List.of(),List.of()):new Evidence(mapper.customers(ids),mapper.events(ids));
    }
}
