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
              FROM nx_growth_quest_event_binding b
              JOIN nx_mission m ON m.mission_code=b.quest_code AND m.status=1 AND m.is_deleted=0
             WHERE b.event_type=#{eventType} AND b.status=1 AND b.is_deleted=0
               AND (b.quest_code NOT IN ('weekly_t2_browse_store','weekly_t2_invite_friend',
                                         'weekly_t2_nex_swap','weekly_t2_ai_jobs_50','weekly_t2_genesis_browse')
                    OR (b.producer='SYSTEM' AND b.user_id_field='user_id'
                        AND b.event_type=CASE b.quest_code
                          WHEN 'weekly_t2_browse_store' THEN 'H3_STOREFRONT_THREE_PRODUCTS_VIEWED'
                          WHEN 'weekly_t2_invite_friend' THEN 'H3_REFERRAL_REGISTERED'
                          WHEN 'weekly_t2_nex_swap' THEN 'H3_EXCHANGE_COMPLETED'
                          WHEN 'weekly_t2_ai_jobs_50' THEN 'H3_COMPUTE_COMPLETED_50'
                          WHEN 'weekly_t2_genesis_browse' THEN 'H3_GENESIS_SECONDARY_MARKET_VIEWED' END))
            """)
    int countActiveBindings(@Param("eventType") String eventType);

    @Select("""
            SELECT binding_code bindingCode,producer,event_type eventType,
                   quest_code questCode,user_id_field userIdField,m.mission_type missionType
              FROM nx_growth_quest_event_binding b
              JOIN nx_mission m ON m.mission_code=b.quest_code AND m.status=1 AND m.is_deleted=0
             WHERE b.event_type=#{eventType} AND b.status=1 AND b.is_deleted=0
               AND (b.quest_code NOT IN ('weekly_t2_browse_store','weekly_t2_invite_friend',
                                         'weekly_t2_nex_swap','weekly_t2_ai_jobs_50','weekly_t2_genesis_browse')
                    OR (b.producer='SYSTEM' AND b.user_id_field='user_id'
                        AND b.event_type=CASE b.quest_code
                          WHEN 'weekly_t2_browse_store' THEN 'H3_STOREFRONT_THREE_PRODUCTS_VIEWED'
                          WHEN 'weekly_t2_invite_friend' THEN 'H3_REFERRAL_REGISTERED'
                          WHEN 'weekly_t2_nex_swap' THEN 'H3_EXCHANGE_COMPLETED'
                          WHEN 'weekly_t2_ai_jobs_50' THEN 'H3_COMPUTE_COMPLETED_50'
                          WHEN 'weekly_t2_genesis_browse' THEN 'H3_GENESIS_SECONDARY_MARKET_VIEWED' END))
             ORDER BY b.id
            """)
    List<CanonicalQuestEventBinding> listActiveBindings(@Param("eventType") String eventType);

    record CanonicalQuestEventBinding(
            String bindingCode,
            String producer,
            String eventType,
            String questCode,
            String userIdField,
            String missionType) {
        public CanonicalQuestEventBinding(
                String bindingCode,
                String producer,
                String eventType,
                String questCode,
                String userIdField) {
            this(bindingCode, producer, eventType, questCode, userIdField, null);
        }
    }
}
