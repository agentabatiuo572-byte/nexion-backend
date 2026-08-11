package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventSchemaRegistration;
import ffdd.opsconsole.platform.infrastructure.EventSchemaRegistryEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface EventGovernanceMapper extends BaseMapper<EventSchemaRegistryEntity> {
    @Select("SELECT GET_LOCK('nexion:a4:event-retention',0)")
    Integer tryAcquireRetentionLock();

    @Select("SELECT RELEASE_LOCK('nexion:a4:event-retention')")
    Integer releaseRetentionLock();

    @Delete("""
            DELETE FROM nx_event_outbox
             WHERE is_deleted=0 AND analytics_event=1
               AND status IN ('PUBLISHED','DEAD')
               AND event_ts < #{cutoff}
             ORDER BY event_ts,id
             LIMIT #{limit}
            """)
    int deleteTerminalAnalyticsEventsBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
              FROM nx_event_outbox
             WHERE is_deleted=0 AND analytics_event=1 AND schema_registered=1
               AND event_ts >= #{startAt}
            """)
    long countEventsSince(@Param("startAt") LocalDateTime startAt);

    @Select("""
            SELECT family_key AS familyKey, COUNT(*) AS eventCount
              FROM nx_event_outbox
             WHERE is_deleted=0 AND analytics_event=1 AND schema_registered=1
               AND event_ts >= #{startAt}
             GROUP BY family_key
            """)
    List<EventFamilyCount> countEventsByFamilySince(@Param("startAt") LocalDateTime startAt);

    @Select("SELECT current_revision FROM nx_event_schema_revision WHERE id=1")
    Integer currentRevision();

    @Select("SELECT current_revision FROM nx_event_schema_revision WHERE id=1 FOR UPDATE")
    Integer lockCurrentRevision();

    @Update("""
            UPDATE nx_event_schema_revision
               SET current_revision=#{nextRevision}, updated_at=NOW()
             WHERE id=1 AND current_revision=#{expectedRevision}
            """)
    int advanceRevision(@Param("expectedRevision") int expectedRevision,
                        @Param("nextRevision") int nextRevision);

    @Select("""
            SELECT id, event_name AS eventName, owner_domain AS ownerDomain, family_key AS familyKey,
                   producer, consumers, is_server_authoritative AS serverAuthoritative,
                   sampling_policy AS samplingPolicy, current_revision AS currentRevision
              FROM nx_event_schema_registry
             WHERE event_name=#{eventName} AND status='ACTIVE' AND is_deleted=0
             LIMIT 1
            """)
    EventSchemaRecord findSchema(@Param("eventName") String eventName);

    @Select("""
            SELECT COUNT(*) FROM nx_event_schema_property
             WHERE schema_id=#{schemaId} AND property_name=#{propertyName} AND is_deleted=0
            """)
    int countProperty(@Param("schemaId") long schemaId, @Param("propertyName") String propertyName);

    @Insert("""
            INSERT INTO nx_event_schema_registry
              (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
               sampling_policy, current_revision, status, created_by, reason, created_at, updated_at, is_deleted)
            VALUES
              (#{eventName}, #{ownerDomain}, #{familyKey}, #{producer}, #{consumers}, #{serverAuthoritative},
               #{samplingPolicy}, #{revision}, 'ACTIVE', #{actor}, #{reason}, NOW(), NOW(), 0)
            """)
    int insertSchema(@Param("eventName") String eventName,
                     @Param("ownerDomain") String ownerDomain,
                     @Param("familyKey") String familyKey,
                     @Param("producer") String producer,
                     @Param("consumers") String consumers,
                     @Param("serverAuthoritative") boolean serverAuthoritative,
                     @Param("samplingPolicy") String samplingPolicy,
                     @Param("revision") int revision,
                     @Param("actor") String actor,
                     @Param("reason") String reason);

    @Update("""
            UPDATE nx_event_schema_registry
               SET current_revision=#{revision}, updated_by=#{actor}, reason=#{reason}, updated_at=NOW()
             WHERE id=#{schemaId} AND status='ACTIVE' AND is_deleted=0
            """)
    int updateSchemaRevision(@Param("schemaId") long schemaId,
                             @Param("revision") int revision,
                             @Param("actor") String actor,
                             @Param("reason") String reason);

    @Insert("""
            INSERT INTO nx_event_schema_property
              (schema_id, property_name, property_type, pii, required_field, registry_revision,
               created_at, updated_at, is_deleted)
            VALUES
              (#{schemaId}, #{propertyName}, #{propertyType}, 0, 1, #{revision}, NOW(), NOW(), 0)
            """)
    int insertProperty(@Param("schemaId") long schemaId,
                       @Param("propertyName") String propertyName,
                       @Param("propertyType") String propertyType,
                       @Param("revision") int revision);

    @Select("""
            SELECT s.event_name AS eventName, s.owner_domain AS ownerDomain, s.family_key AS familyKey,
                   s.producer, s.consumers,
                   GROUP_CONCAT(CONCAT(p.property_name, ':', p.property_type) ORDER BY p.id SEPARATOR ', ') AS properties,
                   s.is_server_authoritative AS serverAuthoritative,
                   s.sampling_policy AS samplingPolicy,
                   CONCAT('v', s.current_revision) AS version,
                   DATE_FORMAT(s.updated_at, '%Y-%m-%d %H:%i:%s') AS updatedAt,
                   COALESCE(l.lifecycle_state, 'new') AS lifecycleState,
                   COALESCE(l.version, 0) AS lifecycleVersion
              FROM nx_event_schema_registry s
              JOIN nx_event_schema_property p ON p.schema_id=s.id AND p.is_deleted=0
              LEFT JOIN nx_admin_event_lifecycle l ON l.event_name=s.event_name AND l.is_deleted=0
             WHERE s.status='ACTIVE' AND s.is_deleted=0
             GROUP BY s.id, s.event_name, s.owner_domain, s.family_key, s.producer, s.consumers,
                      s.is_server_authoritative, s.sampling_policy, s.current_revision, s.updated_at,
                      l.lifecycle_state, l.version
             ORDER BY s.updated_at DESC, s.id DESC
             LIMIT #{limit}
            """)
    List<EventSchemaRegistration> listSchemas(@Param("limit") int limit);

    @Select("""
            SELECT event_name AS eventName, lifecycle_state AS state, version,
                   DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updatedAt
              FROM nx_admin_event_lifecycle
             WHERE event_name=#{eventName} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    EventLifecycleRecord lockLifecycle(@Param("eventName") String eventName);

    @Insert("""
            INSERT IGNORE INTO nx_admin_event_lifecycle
              (event_name, lifecycle_state, version, changed_by, reason, created_at, updated_at, is_deleted)
            VALUES (#{eventName}, 'new', 0, #{actor}, #{reason}, NOW(), NOW(), 0)
            """)
    int insertLifecycle(@Param("eventName") String eventName,
                        @Param("actor") String actor,
                        @Param("reason") String reason);

    @Update("""
            UPDATE nx_admin_event_lifecycle
               SET lifecycle_state=#{targetState}, version=version+1,
                   changed_by=#{actor}, reason=#{reason}, updated_at=NOW()
             WHERE event_name=#{eventName} AND lifecycle_state=#{expectedState}
               AND version=#{expectedVersion} AND is_deleted=0
            """)
    int transitionLifecycle(@Param("eventName") String eventName,
                            @Param("targetState") String targetState,
                            @Param("expectedState") String expectedState,
                            @Param("expectedVersion") long expectedVersion,
                            @Param("actor") String actor,
                            @Param("reason") String reason);

    @Select("""
            SELECT id, domain_name AS domainName, event_name AS eventName, producer, consumer, status
              FROM nx_event_domain_extension
             WHERE is_deleted=0
             ORDER BY updated_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<DomainExtensionRecord> listDomainExtensions(@Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM nx_event_domain_extension
             WHERE domain_name=#{domainName} AND event_name=#{eventName} AND is_deleted=0
            """)
    int countDomainExtension(@Param("domainName") String domainName, @Param("eventName") String eventName);

    @Select("""
            SELECT COUNT(*) FROM nx_event_domain_extension
             WHERE domain_name=#{domainName} AND event_name=#{eventName}
               AND status='REGISTERED' AND is_deleted=0
            """)
    int countRegistrableDomainExtension(@Param("domainName") String domainName,
                                        @Param("eventName") String eventName);

    @Update("""
            UPDATE nx_event_domain_extension
               SET status='DONE', updated_at=NOW()
             WHERE domain_name=#{domainName} AND event_name=#{eventName}
               AND status='REGISTERED' AND is_deleted=0
            """)
    int completeDomainExtension(@Param("domainName") String domainName,
                                @Param("eventName") String eventName);

    @Insert("""
            INSERT INTO nx_event_domain_extension
              (domain_name, event_name, producer, consumer, status, created_by, reason,
               created_at, updated_at, is_deleted)
            VALUES
              (#{domainName}, #{eventName}, #{producer}, #{consumer}, 'REGISTERED', #{actor}, #{reason},
               NOW(), NOW(), 0)
            """)
    int insertDomainExtension(@Param("domainName") String domainName,
                              @Param("eventName") String eventName,
                              @Param("producer") String producer,
                              @Param("consumer") String consumer,
                              @Param("actor") String actor,
                              @Param("reason") String reason);

    record EventFamilyCount(String familyKey, long eventCount) {
    }

    record EventSchemaRecord(
            long id,
            String eventName,
            String ownerDomain,
            String familyKey,
            String producer,
            String consumers,
            boolean serverAuthoritative,
            String samplingPolicy,
            int currentRevision) {
    }

    record DomainExtensionRecord(
            long id,
            String domainName,
            String eventName,
            String producer,
            String consumer,
            String status) {
    }

    record EventLifecycleRecord(String eventName, String state, long version, String updatedAt) {
    }
}
