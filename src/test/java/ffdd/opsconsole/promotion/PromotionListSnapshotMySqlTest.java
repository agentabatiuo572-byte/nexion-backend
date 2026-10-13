package ffdd.opsconsole.promotion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_CACHE_RUNTIME", matches="1")
class PromotionListSnapshotMySqlTest {
    @Test void compactFrozenPagesAndBoundedExpiryUseOnlyAnEmptyIsolatedSchema() throws Exception {
        var credentials=new HashMap<String,String>();
        for(String line:Files.readAllLines(Path.of("D:/CodexData/test-environments/workflow-runs/growth-promotions-20261007/mysql/client.private.ini"))){
            if(!line.contains("=")||line.strip().startsWith("#"))continue;
            var pair=line.split("=",2);credentials.put(pair[0].strip(),pair[1].strip().replaceAll("^\"|\"$",""));
        }
        String schema="growth_cache_"+UUID.randomUUID().toString().replace("-","");
        String url="jdbc:mysql://127.0.0.1:33339/growth_promotions_20261007?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=15000&serverTimezone=Asia/Shanghai&sessionVariables=time_zone='%2B08:00'";
        try(var connection=DriverManager.getConnection(url,credentials.get("user"),credentials.get("password"));var bootstrap=connection.createStatement()){
            bootstrap.setQueryTimeout(15);bootstrap.execute("CREATE DATABASE "+schema);
            connection.setCatalog(schema);
            try(var statement=connection.createStatement()){
                statement.setQueryTimeout(15);
                // LIKE copies structure only; no original activity, order or ledger rows are copied or changed.
                for(String table:List.of("nx_promotion","nx_promotion_version","nx_promotion_list_snapshot","nx_promotion_report_snapshot","nx_promotion_export_job","nx_promotion_reservation","nx_promotion_reward","nx_wallet_ledger","nx_promotion_device_receipt","nx_user_device","nx_promotion_budget"))
                    statement.execute("CREATE TABLE "+table+" LIKE growth_promotions_20261007."+table);
                for(var index:Map.of("nx_promotion_list_snapshot","idx_promotion_list_snapshot_expiry","nx_promotion_report_snapshot","idx_promotion_report_expiry").entrySet()){
                    try(var indexes=statement.executeQuery("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+index.getKey()+"' AND INDEX_NAME='"+index.getValue()+"'")){
                        indexes.next();if(indexes.getInt(1)>0)try(var alter=connection.createStatement()){alter.execute("ALTER TABLE "+index.getKey()+" DROP INDEX "+index.getValue());}
                    }
                }
                statement.execute("ALTER TABLE nx_promotion_export_job ADD CONSTRAINT fk_cache_test_report FOREIGN KEY(snapshot_id) REFERENCES nx_promotion_report_snapshot(snapshot_id)");
                var migration=new FileSystemResource("scripts/migrations/20261012_growth_promotions_snapshot_cleanup.sql");
                ScriptUtils.executeSqlScript(connection,migration);ScriptUtils.executeSqlScript(connection,migration);
                for(String index:List.of("idx_promotion_list_snapshot_expiry","idx_promotion_report_expiry"))try(var columns=statement.executeQuery("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND INDEX_NAME='"+index+"'")){
                    columns.next();assertEquals("expires_at,snapshot_id",columns.getString(1));
                }
                var configuration=new MybatisConfiguration(new Environment(schema,new SpringManagedTransactionFactory(),new SingleConnectionDataSource(connection,true)));
                configuration.setDefaultStatementTimeout(15);configuration.addMapper(PromotionMapper.class);
                try(var session=new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)){
                    var db=session.getMapper(PromotionMapper.class);
                    var validator=new PromotionContractValidator(new ObjectMapper().findAndRegisterModules());
                    var admin=new PromotionAdminService(db,validator,null,null,null,null,null,null,null,null,null,null,null,null);
                    authenticate(17,true);
                    String terms="terms ".repeat(600);
                    for(String id:List.of("A1","A2")){
                        var draft=values("name",id,"category","PROMOTION","template","SKU_GIFT","terms",values("zh",terms,"en",terms,"vi",terms));
                        validator.validate("Draft",draft);
                        db.write("INSERT INTO nx_promotion(activity_id,event_code,category,template,draft_version) VALUES(?,?,'PROMOTION','SKU_GIFT',1)",id,id);
                        db.write("INSERT INTO nx_promotion_version(activity_id,version,contract_json,content_hash) VALUES(?,1,?,?)",id,json(draft),hash(draft));
                    }
                    var filters=values("sort","NAME_ASC");var first=admin.page(filters,null,null,1);String id=text(first.get("querySnapshot"));
                    validator.validate("PromotionPage",first);assertEquals(2,number(first.get("total")));
                    assertEquals("A1",maps(first.get("items")).get(0).get("name"));
                    var detail=admin.get("A2");validator.validate("Promotion",detail);
                    assertEquals(terms,map(map(detail.get("current")).get("draft")).get("terms") instanceof Map<?,?> localized?localized.get("zh"):null);
                    assertTrue(map(detail.get("impact")).containsKey("reservedOrders"));
                    long bytes=number(db.requiredRow("SELECT OCTET_LENGTH(rows_json) bytes FROM nx_promotion_list_snapshot WHERE snapshot_id=?",id).get("bytes"));
                    assertTrue(bytes<4096,"A two-row list must not copy the 21 KB of localized terms: "+bytes);
                    db.write("UPDATE nx_promotion_version SET contract_json=JSON_SET(contract_json,'$.name','A2 changed') WHERE activity_id='A2'");
                    var second=admin.page(filters,id,"1",1);validator.validate("PromotionPage",second);
                    assertEquals("A2",maps(second.get("items")).get(0).get("name"));assertEquals(first.get("summary"),second.get("summary"));
                    assertEquals(first.get("asOf"),second.get("asOf"));assertEquals(false,second.get("hasMore"));
                    assertEquals("A2 changed",maps(admin.page(filters,null,null,10).get("items")).get(1).get("name"));
                    assertTrue(maps(admin.page(filters,id,"2",1).get("items")).isEmpty());
                    // Old unexpired snapshots have detail fields; reads project those fields away without rewriting history.
                    db.write("UPDATE nx_promotion_list_snapshot SET rows_json=JSON_SET(rows_json,'$[1].current',CAST(? AS JSON),'$[1].impact.reservedOrders',CAST(? AS JSON)) WHERE snapshot_id=?",json(detail.get("current")),"[{\"orderNo\":\"old\"}]",id);
                    assertEquals(second,admin.page(filters,id,"1",1));
                    assertEquals(409,assertThrows(BizException.class,()->admin.page(values("query","changed"),id,null,1)).getCode());
                    authenticate(18,true);assertEquals(404,assertThrows(BizException.class,()->admin.page(filters,id,null,1)).getCode());
                    authenticate(17,false);assertEquals(403,assertThrows(BizException.class,()->admin.page(filters,id,null,1)).getCode());authenticate(17,true);
                    var past=Instant.now().minusSeconds(3600);
                    for(int i=0;i<101;i++)db.write("INSERT INTO nx_promotion_list_snapshot(snapshot_id,actor_id,query_json,rows_json,summary_json,total,as_of,expires_at) VALUES(?,17,?,'[]','{}',0,?,?)","expired-"+i,json(filters),timestamp(past),timestamp(past.plusSeconds(1)));
                    for(int i=0;i<103;i++)db.write("INSERT INTO nx_promotion_report_snapshot(snapshot_id,activity_id,actor_id,query_json,metrics_json,source_watermarks_json,row_manifest_json,expires_at) VALUES(?,'A1',17,'{}','{}','{}','[]',?)","report-"+i,timestamp(i==102?Instant.now().plusSeconds(3600):past));
                    db.write("INSERT INTO nx_promotion_export_job(export_id,snapshot_id,actor_id,status) VALUES('export-1','report-101',17,'EXPIRED')");
                    assertEquals(409,assertThrows(BizException.class,()->admin.page(filters,"expired-0",null,1)).getCode());
                    var plan=db.list("EXPLAIN DELETE FROM nx_promotion_list_snapshot WHERE expires_at<=? ORDER BY expires_at,snapshot_id LIMIT 100",timestamp(Instant.now()));
                    assertEquals("idx_promotion_list_snapshot_expiry",plan.get(0).get("key"));
                    var cleanup=new PromotionSnapshotCleanup(db);cleanup.cleanupExpired();
                    assertEquals(1,db.count("SELECT COUNT(*) FROM nx_promotion_list_snapshot WHERE snapshot_id LIKE 'expired-%'"));
                    assertEquals(3,db.count("SELECT COUNT(*) FROM nx_promotion_report_snapshot"));
                    cleanup.cleanupExpired();assertEquals(0,db.count("SELECT COUNT(*) FROM nx_promotion_list_snapshot WHERE snapshot_id LIKE 'expired-%'"));
                    assertEquals(Set.of("report-101","report-102"),new HashSet<>(db.list("SELECT snapshot_id FROM nx_promotion_report_snapshot").stream().map(row->text(row.get("snapshot_id"))).toList()));
                    assertEquals(1,db.count("SELECT COUNT(*) FROM nx_promotion_export_job WHERE export_id='export-1'"));
                    assertEquals(404,assertThrows(BizException.class,()->admin.page(filters,"expired-0",null,1)).getCode());
                    assertEquals(second,admin.page(filters,id,"1",1));
                    System.out.println("CACHE_RUNTIME compactBytes="+bytes+" fullDetailBytes="+json(detail).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+" expiredListsRemoved=101 expiredReportsRemoved=101 validListsPreserved=2 referencedAndValidReportsPreserved=2");
                }
            }finally{
                SecurityContextHolder.clearContext();connection.setCatalog("growth_promotions_20261007");bootstrap.execute("DROP DATABASE "+schema);
            }
        }
    }
    private static void authenticate(long actor,boolean read){
        var auth=new UsernamePasswordAuthenticationToken(actor,"",read?List.of(new SimpleGrantedAuthority("growth_promotion_read")):List.of());
        auth.setDetails(values("subjectType","ADMIN"));SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
