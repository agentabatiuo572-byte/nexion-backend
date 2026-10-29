package ffdd.opsconsole.content.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Bound SQL verifies the production query is bounded before projection, not an in-memory transcript slice. */
class ConversationHistoryMapperTest {
    final Configuration configuration=new Configuration();
    ConversationHistoryMapperTest(){configuration.addMapper(ConversationHistoryMapper.class);}
    String sql(String method,Map<String,Object> parameters){return configuration.getMappedStatement(ConversationHistoryMapper.class.getName()+"."+method).getBoundSql(parameters).getSql().replaceAll("\\s+"," ").trim();}
    @Test void firstWindowIsLimitedInsideTheCustomerQueryBeforeAuthorAndAttachmentProjection(){
        var params=new HashMap<String,Object>();params.put("customerId",42L);params.put("beforeId",null);
        var sql=sql("page",params);
        assertThat(sql).contains("c.user_id=?","c.is_deleted=0","m.is_deleted=0","c.conversation_type IN ('advisor','support')","ORDER BY m.created_at DESC,m.id DESC LIMIT 11","ORDER BY msg.created_at DESC,msg.id DESC");
        assertThat(sql).doesNotContain("OFFSET","m.created_at <");
        assertThat(sql.indexOf("LIMIT 11")).isLessThan(sql.indexOf("LEFT JOIN nx_support_human_message"));
        assertThat(sql).contains("senderName","authorConfidence","attachmentId","senderAvatarAssetId","receiptStatus","targetAvailability","linkTargetJson");
    }
    @Test void cursorUsesTheOriginalCustomerScopedTupleAndStrictlyOlderBoundary(){
        var params=new HashMap<String,Object>();params.put("customerId",42L);params.put("beforeId",10L);params.put("beforeAt",LocalDateTime.of(2026,10,29,0,0,0,123456000));
        assertThat(sql("cursor",Map.of("customerId",42L,"messageId",10L))).contains("c.user_id=?","m.id=?","m.created_at createdAt","m.is_deleted=0");
        assertThat(sql("page",params)).contains("m.created_at < ? OR (m.created_at=? AND m.id < ?)","LIMIT 11").doesNotContain("OFFSET");
    }
    @Test void commandMetadataDoesNotDownloadOldBodiesAndReceiptContextOnlyUsesSeenIds(){
        assertThat(sql("replyTargets",Map.of("customerId",42L))).contains("MAX(m.id) throughMessageId","m.id>COALESCE(r.through_message_id,0)","m.sender_type='user'","c.user_id=?").doesNotContain("m.content","&gt;","LIMIT 10");
        assertThat(sql("readContexts",Map.of("customerId",42L,"ids",List.of(10L,12L)))).contains("c.status,c.version","MAX(m.id) lastSeenMessageId","m.id IN","alreadyRead","c.user_id=?").doesNotContain("m.content");
        assertThat(sql("identity",Map.of("customerId",42L))).contains("userNo","phoneMasked","sandbox=0").doesNotContain("wallet","balance");
    }
    @Test void readGrantIsCurrentExactApiPermissionAndBypassesMybatisLocalCache(){
        var statement=configuration.getMappedStatement(ConversationHistoryMapper.class.getName()+".currentReadGrants");
        assertThat(sql("currentReadGrants",Map.of("actorId",7L))).contains("p.permission_code='service_m3_read'","p.resource_type='API'","r.status=1","p.status=1","FOR SHARE");
        assertThat(statement.isUseCache()).isFalse();assertThat(statement.isFlushCacheRequired()).isTrue();
    }
}
