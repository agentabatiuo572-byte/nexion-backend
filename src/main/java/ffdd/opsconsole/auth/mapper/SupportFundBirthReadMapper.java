package ffdd.opsconsole.auth.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper @SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportFundBirthReadMapper {
    String IDS="<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>";
    @Select("<script>SELECT id,created_at createdAt,sandbox,is_deleted isDeleted,status FROM nx_user WHERE id IN "+IDS+" ORDER BY id</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE) List<Map<String,Object>> customers(@Param("ids") List<Long> ids);
    @Select("<script>SELECT id,event_id eventId,aggregate_type aggregateType,aggregate_id aggregateId,event_type eventType,"
        +"event_name eventName,event_ts eventTs,is_server_authoritative serverAuthoritative,schema_registered schemaRegistered,"
        +"analytics_event analyticsEvent,schema_revision schemaRevision,payload,is_deleted isDeleted FROM nx_event_outbox"
        +" WHERE aggregate_type='USER_REGISTRATION' AND aggregate_id IN "
        +"<foreach collection='ids' item='id' open='(' separator=',' close=')'>CAST(#{id} AS CHAR)</foreach> ORDER BY id</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE) List<Map<String,Object>> events(@Param("ids") List<Long> ids);
}
