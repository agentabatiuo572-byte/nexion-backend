package ffdd.opsconsole.growth.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Data-owned routing from canonical cross-domain facts to H3 mission codes. */
@Mapper
// Statement-only canonical-event routing projection with no mutable CRUD entity.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface QuestCanonicalEventBindingMapper {

    @Select("""
            SELECT COUNT(*)
              FROM nx_growth_quest_event_binding
             WHERE event_type=#{eventType} AND status=1 AND is_deleted=0
            """)
    int countActiveBindings(@Param("eventType") String eventType);

    @Select("""
            SELECT binding_code bindingCode,producer,event_type eventType,
                   quest_code questCode,user_id_field userIdField
              FROM nx_growth_quest_event_binding
             WHERE event_type=#{eventType} AND status=1 AND is_deleted=0
             ORDER BY id
            """)
    List<CanonicalQuestEventBinding> listActiveBindings(@Param("eventType") String eventType);

    record CanonicalQuestEventBinding(
            String bindingCode,
            String producer,
            String eventType,
            String questCode,
            String userIdField) {
    }
}
