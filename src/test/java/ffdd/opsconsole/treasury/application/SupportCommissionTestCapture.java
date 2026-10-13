package ffdd.opsconsole.treasury.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.SupportFundJournalBindingService;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import ffdd.opsconsole.treasury.facade.SupportCommissionCaptureFacade;
import ffdd.opsconsole.treasury.mapper.SupportCommissionReadMapper;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Native fixtures use real capture dependencies; missing native schema remains a visible failure. */
public final class SupportCommissionTestCapture {
    private SupportCommissionTestCapture() {}
    public static SupportCommissionCaptureFacade real(SqlSessionTemplate session,DataSource source,ObjectMapper json){
        var config=session.getConfiguration();
        for(var type:java.util.List.of(SupportCommissionReadMapper.class,SupportFundJournalMapper.class))if(!config.hasMapper(type))config.addMapper(type);
        var mapper=session.getMapper(SupportCommissionReadMapper.class);
        var binder=new SupportFundJournalBindingService(session.getMapper(SupportFundJournalMapper.class),source,json);
        return proxy(new SupportCommissionFundAdapter(proxy(binder,source),mapper,new SupportCommissionReadService(mapper),json),source);
    }
    @SuppressWarnings("unchecked") private static <T>T proxy(T target,DataSource source){var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();}
}
