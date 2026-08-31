package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import ffdd.opsconsole.content.domain.LearningProgressRow;
import ffdd.opsconsole.content.domain.LearningSandboxIdempotencyRow;
import ffdd.opsconsole.content.domain.LearningQuizReceipt;
import ffdd.opsconsole.content.domain.LearningSandboxObservationWindow;
import ffdd.opsconsole.content.domain.LearningSandboxCourseRow;
import ffdd.opsconsole.content.infrastructure.HelpArticleEntity;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AppLearningMapper extends BaseMapper<HelpArticleEntity> {
    @Select("""
            SELECT COUNT(*)
              FROM nx_user
             WHERE id=#{userId} AND sandbox=1 AND status='ACTIVE' AND is_deleted=0
            """)
    int developmentUserScope(@Param("userId") Long userId);

    @Select("""
            SELECT course_id courseId,course_version version,status,title_zh titleZh,title_en titleEn,title_vi titleVi,
                   body_zh bodyZh,body_en bodyEn,body_vi bodyVi,category,format,level,reward_nex rewardNex,duration,
                   featured,quiz_json quizJson,pass_score passScore,retry_limit retryLimit,completion_condition completionCondition,
                   reward_event rewardEvent,revision
              FROM nx_learning_sandbox_course
             WHERE run_id=#{runId} AND status='PUBLISHED' AND is_deleted=0 ORDER BY course_id,revision DESC,updated_at DESC
            """)
    List<LearningSandboxCourseRow> listSandboxPublishedCourses(@Param("runId") String runId);

    @Select("""
            SELECT course_id courseId,course_version version,status,title_zh titleZh,title_en titleEn,title_vi titleVi,
                   body_zh bodyZh,body_en bodyEn,body_vi bodyVi,category,format,level,reward_nex rewardNex,duration,
                   featured,quiz_json quizJson,pass_score passScore,retry_limit retryLimit,completion_condition completionCondition,
                   reward_event rewardEvent,revision
              FROM nx_learning_sandbox_course
             WHERE run_id=#{runId} AND course_id=#{courseId} AND status='PUBLISHED' AND is_deleted=0 ORDER BY revision DESC,updated_at DESC LIMIT 1
            """)
    LearningSandboxCourseRow findSandboxPublishedCourse(@Param("runId") String runId, @Param("courseId") String courseId);

    @Select("""
            SELECT course_id courseId,course_version version,status,title_zh titleZh,title_en titleEn,title_vi titleVi,
                   body_zh bodyZh,body_en bodyEn,body_vi bodyVi,category,format,level,reward_nex rewardNex,duration,
                   featured,quiz_json quizJson,pass_score passScore,retry_limit retryLimit,completion_condition completionCondition,
                   reward_event rewardEvent,revision
              FROM nx_learning_sandbox_course
             WHERE run_id=#{runId} AND is_deleted=0 ORDER BY updated_at DESC
            """)
    List<LearningSandboxCourseRow> listSandboxCourses(@Param("runId") String runId);

    @Insert("""
            INSERT INTO nx_learning_sandbox_course (run_id,course_id,course_version,status,title_zh,title_en,title_vi,body_zh,body_en,body_vi,category,format,level,reward_nex,duration,featured,quiz_json,pass_score,retry_limit,completion_condition,reward_event,source,source_environment,revision,created_at,updated_at,is_deleted)
            VALUES (#{runId},#{row.courseId},#{row.version},'DRAFT',#{row.titleZh},#{row.titleEn},#{row.titleVi},#{row.bodyZh},#{row.bodyEn},#{row.bodyVi},#{row.category},#{row.format},#{row.level},#{row.rewardNex},#{row.duration},#{row.featured},#{row.quizJson},#{row.passScore},#{row.retryLimit},#{row.completionCondition},#{row.rewardEvent},'mock','SANDBOX',0,NOW(),NOW(),0)
            """)
    int insertSandboxCourse(@Param("runId") String runId, @Param("row") LearningSandboxCourseRow row);

    @Update("""
            UPDATE nx_learning_sandbox_course SET title_zh=#{row.titleZh},title_en=#{row.titleEn},title_vi=#{row.titleVi},body_zh=#{row.bodyZh},body_en=#{row.bodyEn},body_vi=#{row.bodyVi},category=#{row.category},format=#{row.format},level=#{row.level},reward_nex=#{row.rewardNex},duration=#{row.duration},featured=#{row.featured},quiz_json=#{row.quizJson},pass_score=#{row.passScore},retry_limit=#{row.retryLimit},completion_condition=#{row.completionCondition},reward_event=#{row.rewardEvent},revision=revision+1,updated_at=NOW()
             WHERE run_id=#{runId} AND course_id=#{row.courseId} AND course_version=#{row.version} AND revision=#{row.revision} AND status='DRAFT' AND is_deleted=0
            """)
    int updateSandboxCourseDraft(@Param("runId") String runId, @Param("row") LearningSandboxCourseRow row);

    @Update("UPDATE nx_learning_sandbox_course SET status='PUBLISHED', revision=revision+1,updated_at=NOW() WHERE run_id=#{runId} AND course_id=#{courseId} AND course_version=#{version} AND revision=#{expectedRevision} AND status='DRAFT' AND is_deleted=0 AND NOT EXISTS (SELECT 1 FROM (SELECT course_id,status,is_deleted FROM nx_learning_sandbox_course WHERE run_id=#{runId} AND course_id=#{courseId}) published_authority WHERE published_authority.course_id=#{courseId} AND published_authority.status='PUBLISHED' AND published_authority.is_deleted=0)")
    int publishSandboxCourse(@Param("runId") String runId,@Param("courseId") String courseId,@Param("version") String version,@Param("expectedRevision") long expectedRevision);
    @Update("UPDATE nx_learning_sandbox_course SET is_deleted=1,revision=revision+1,updated_at=NOW() WHERE run_id=#{runId} AND course_id=#{courseId} AND course_version=#{version} AND revision=#{expectedRevision} AND status='DRAFT' AND is_deleted=0")
    int deleteSandboxCourse(@Param("runId") String runId,@Param("courseId") String courseId,@Param("version") String version,@Param("expectedRevision") long expectedRevision);
    @Insert("""
            INSERT IGNORE INTO nx_learning_sandbox_admin_idempotency
                (run_id,command_scope,idempotency_key,request_hash,status,source,source_environment,created_at,updated_at,is_deleted)
            VALUES (#{runId},#{commandScope},#{idempotencyKey},#{requestHash},'PENDING','mock','SANDBOX',NOW(),NOW(),0)
            """)
    int claimSandboxCatalogIdempotency(@Param("runId") String runId, @Param("commandScope") String commandScope,
                                       @Param("idempotencyKey") String idempotencyKey, @Param("requestHash") String requestHash);
    @Select("SELECT request_hash AS requestHash,status,result_json AS resultJson FROM nx_learning_sandbox_admin_idempotency WHERE run_id=#{runId} AND command_scope=#{commandScope} AND idempotency_key=#{idempotencyKey} LIMIT 1 FOR UPDATE")
    LearningSandboxIdempotencyRow lockSandboxCatalogIdempotency(@Param("runId") String runId, @Param("commandScope") String commandScope,
                                                                 @Param("idempotencyKey") String idempotencyKey);
    @Update("UPDATE nx_learning_sandbox_admin_idempotency SET status='COMPLETED',result_json=#{resultJson},updated_at=NOW() WHERE run_id=#{runId} AND command_scope=#{commandScope} AND idempotency_key=#{idempotencyKey} AND status='PENDING'")
    int completeSandboxCatalogIdempotency(@Param("runId") String runId, @Param("commandScope") String commandScope,
                                          @Param("idempotencyKey") String idempotencyKey, @Param("resultJson") String resultJson);
    @Select("SELECT request_hash AS requestHash,status,result_json AS resultJson FROM nx_learning_sandbox_admin_idempotency WHERE run_id=#{runId} AND command_scope=#{commandScope} AND idempotency_key=#{idempotencyKey} LIMIT 1")
    LearningSandboxIdempotencyRow findSandboxCatalogCommandResult(@Param("runId") String runId, @Param("commandScope") String commandScope, @Param("idempotencyKey") String idempotencyKey);
    @Select("SELECT command_scope FROM nx_learning_sandbox_admin_idempotency WHERE run_id=#{runId} AND idempotency_key=#{idempotencyKey} LIMIT 1")
    String findSandboxCatalogCommandScopeByKey(@Param("runId") String runId, @Param("idempotencyKey") String idempotencyKey);
    @Insert("""
            INSERT IGNORE INTO nx_learning_event (
                user_id, course_id, course_version, event_type, event_payload,
                created_at, updated_at, is_deleted
            ) VALUES (#{userId}, #{courseId}, #{courseVersion}, #{eventType}, #{payload}, NOW(), NOW(), 0)
            """)
    int insertLearningEvent(@Param("userId") Long userId,
                            @Param("courseId") String courseId,
                            @Param("courseVersion") String courseVersion,
                            @Param("eventType") String eventType,
                            @Param("payload") String payload);


    @Select("""
            SELECT course_id AS courseId, course_version AS courseVersion,
                   progress_pct AS progressPct, attempts, last_score AS lastScore, completed_at AS completedAt
              FROM nx_learning_progress
             WHERE user_id = #{userId} AND is_deleted = 0
            """)
    List<LearningProgressRow> listProgress(@Param("userId") Long userId);

    @Select("""
            SELECT course_id AS courseId, course_version AS courseVersion,
                   progress_pct AS progressPct, attempts, last_score AS lastScore, completed_at AS completedAt
              FROM nx_learning_progress
             WHERE user_id = #{userId} AND course_id = #{courseId}
               AND course_version = #{courseVersion} AND is_deleted = 0
             LIMIT 1
            """)
    LearningProgressRow findProgress(@Param("userId") Long userId,
                                     @Param("courseId") String courseId,
                                     @Param("courseVersion") String courseVersion);

    @Select("""
            SELECT CASE
                     WHEN u.sandbox = 1 AND w.sandbox = 1 THEN 'SANDBOX'
                     WHEN u.sandbox = 0 AND w.sandbox = 0 THEN 'PRODUCTION'
                     ELSE 'UNKNOWN'
                   END
              FROM nx_user u
              JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
             WHERE u.id = #{userId} AND u.is_deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    String lockRewardEnvironment(@Param("userId") Long userId);

    /** Read-only counterpart for the list/detail projection. Commands lock. */
    @Select("""
            SELECT CASE
                     WHEN u.sandbox = 1 AND w.sandbox = 1 THEN 'SANDBOX'
                     WHEN u.sandbox = 0 AND w.sandbox = 0 THEN 'PRODUCTION'
                     ELSE 'UNKNOWN'
                   END
              FROM nx_user u
              JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
             WHERE u.id = #{userId} AND u.is_deleted = 0
             LIMIT 1
            """)
    String readRewardEnvironment(@Param("userId") Long userId);

    @Insert("""
            INSERT IGNORE INTO nx_learning_progress (
                user_id, course_id, course_version, progress_pct, attempts,
                last_score, started_at, created_at, updated_at, is_deleted
            ) VALUES (#{userId}, #{courseId}, #{courseVersion}, 1, 0, 0, NOW(), NOW(), NOW(), 0)
            """)
    int startCourse(@Param("userId") Long userId,
                    @Param("courseId") String courseId,
                    @Param("courseVersion") String courseVersion);

    @Select("""
            SELECT course_id AS courseId, course_version AS courseVersion,
                   progress_pct AS progressPct, attempts, last_score AS lastScore, completed_at AS completedAt
              FROM nx_learning_progress
             WHERE user_id = #{userId} AND course_id = #{courseId}
               AND course_version = #{courseVersion} AND is_deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    LearningProgressRow lockProgress(@Param("userId") Long userId,
                                     @Param("courseId") String courseId,
                                     @Param("courseVersion") String courseVersion);

    @Insert("""
            INSERT INTO nx_learning_progress (
                user_id, course_id, course_version, progress_pct, attempts,
                last_score, started_at, completed_at, created_at, updated_at, is_deleted
            ) VALUES (
                #{userId}, #{courseId}, #{courseVersion}, #{progressPct}, 1,
                #{score}, NOW(), IF(#{progressPct} = 100, NOW(), NULL), NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                progress_pct = GREATEST(progress_pct, VALUES(progress_pct)),
                attempts = attempts + 1,
                last_score = VALUES(last_score),
                completed_at = IF(VALUES(progress_pct) = 100, COALESCE(completed_at, NOW()), completed_at),
                updated_at = NOW(), is_deleted = 0
            """)
    int recordQuiz(@Param("userId") Long userId,
                   @Param("courseId") String courseId,
                   @Param("courseVersion") String courseVersion,
                   @Param("score") int score,
                   @Param("progressPct") int progressPct);

    @Insert("""
            INSERT IGNORE INTO nx_learning_sandbox_progress (run_id, user_id, course_id, course_version, progress_pct, attempts, last_score, started_at, source, source_environment, created_at, updated_at, is_deleted)
            VALUES (#{runId}, #{userId}, #{courseId}, #{courseVersion}, 1, 0, 0, NOW(), 'mock', 'SANDBOX', NOW(), NOW(), 0)
            """)
    int startSandboxCourse(@Param("runId") String runId, @Param("userId") Long userId, @Param("courseId") String courseId, @Param("courseVersion") String courseVersion);

    @Insert("""
            INSERT INTO nx_learning_sandbox_progress (run_id, user_id, course_id, course_version, progress_pct, attempts, last_score, started_at, completed_at, source, source_environment, created_at, updated_at, is_deleted)
            VALUES (#{runId}, #{userId}, #{courseId}, #{courseVersion}, #{progressPct}, 1, #{score}, NOW(), IF(#{progressPct}=100,NOW(),NULL), 'mock', 'SANDBOX', NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE progress_pct=GREATEST(progress_pct, VALUES(progress_pct)), attempts=attempts+1, last_score=VALUES(last_score), completed_at=IF(VALUES(progress_pct)=100, COALESCE(completed_at,NOW()), completed_at), updated_at=NOW(), is_deleted=0
            """)
    int recordSandboxQuiz(@Param("runId") String runId, @Param("userId") Long userId, @Param("courseId") String courseId, @Param("courseVersion") String courseVersion, @Param("score") int score, @Param("progressPct") int progressPct);

    @Select("""
            SELECT course_id AS courseId, course_version AS courseVersion, progress_pct AS progressPct, attempts, last_score AS lastScore, completed_at AS completedAt
              FROM nx_learning_sandbox_progress WHERE run_id=#{runId} AND user_id=#{userId} AND course_id=#{courseId} AND course_version=#{courseVersion} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    LearningProgressRow lockSandboxProgress(@Param("runId") String runId, @Param("userId") Long userId, @Param("courseId") String courseId, @Param("courseVersion") String courseVersion);

    @Select("""
            SELECT course_id AS courseId, course_version AS courseVersion, progress_pct AS progressPct, attempts, last_score AS lastScore, completed_at AS completedAt
              FROM nx_learning_sandbox_progress WHERE run_id=#{runId} AND user_id=#{userId} AND is_deleted=0
            """)
    List<LearningProgressRow> listSandboxProgress(@Param("runId") String runId, @Param("userId") Long userId);

    @Select("""
            SELECT course_id AS courseId, course_version AS courseVersion, progress_pct AS progressPct, attempts, last_score AS lastScore, completed_at AS completedAt
              FROM nx_learning_sandbox_progress WHERE run_id=#{runId} AND user_id=#{userId} AND course_id=#{courseId} AND course_version=#{courseVersion} AND is_deleted=0 LIMIT 1
            """)
    LearningProgressRow findSandboxProgress(@Param("runId") String runId, @Param("userId") Long userId, @Param("courseId") String courseId, @Param("courseVersion") String courseVersion);

    @Insert("""
            INSERT IGNORE INTO nx_learning_sandbox_event (run_id, user_id, course_id, course_version, event_type, event_payload, source, source_environment, created_at, updated_at, is_deleted)
            VALUES (#{runId}, #{userId}, #{courseId}, #{courseVersion}, #{eventType}, #{payload}, 'mock', 'SANDBOX', NOW(), NOW(), 0)
            """)
    int insertSandboxLearningEvent(@Param("runId") String runId, @Param("userId") Long userId, @Param("courseId") String courseId, @Param("courseVersion") String courseVersion, @Param("eventType") String eventType, @Param("payload") String payload);

    @Select("""
            SELECT COUNT(*) FROM nx_learning_sandbox_reward_ledger WHERE run_id=#{runId} AND user_id=#{userId} AND course_id=#{courseId} AND course_version=#{courseVersion} AND status='GRANTED' AND is_deleted=0
            """)
    int countSandboxGrantedReward(@Param("runId") String runId, @Param("userId") Long userId, @Param("courseId") String courseId, @Param("courseVersion") String courseVersion);

    @Insert("""
            INSERT IGNORE INTO nx_learning_sandbox_reward_ledger (reward_no, run_id, user_id, course_id, course_version, amount_nex, status, source, source_environment, created_at, updated_at, is_deleted)
            VALUES (#{rewardNo}, #{runId}, #{userId}, #{courseId}, #{courseVersion}, #{amount}, 'GRANTED', 'mock', 'SANDBOX', NOW(), NOW(), 0)
            """)
    int grantSandboxReward(@Param("rewardNo") String rewardNo, @Param("runId") String runId, @Param("userId") Long userId, @Param("courseId") String courseId, @Param("courseVersion") String courseVersion, @Param("amount") BigDecimal amount);

    @Select("""
            SELECT COALESCE(SUM(amount_nex),0) FROM nx_learning_sandbox_reward_ledger WHERE run_id=#{runId} AND user_id=#{userId} AND status='GRANTED' AND is_deleted=0
            """)
    BigDecimal sumSandboxGrantedReward(@Param("runId") String runId, @Param("userId") Long userId);

    @Insert("""
            INSERT IGNORE INTO nx_learning_sandbox_idempotency (
                run_id, user_id, course_id, course_version, idempotency_key, request_hash,
                status, source, source_environment, created_at, updated_at, is_deleted
            ) VALUES (#{runId}, #{userId}, #{courseId}, #{courseVersion}, #{idempotencyKey}, #{requestHash},
                'PENDING', 'mock', 'SANDBOX', NOW(), NOW(), 0)
            """)
    int claimSandboxQuizIdempotency(@Param("runId") String runId, @Param("userId") Long userId, @Param("courseId") String courseId,
                                    @Param("courseVersion") String courseVersion,
                                    @Param("idempotencyKey") String idempotencyKey,
                                    @Param("requestHash") String requestHash);

    @Select("""
            SELECT request_hash AS requestHash, status, result_json AS resultJson
              FROM nx_learning_sandbox_idempotency
             WHERE run_id=#{runId} AND user_id=#{userId} AND course_id=#{courseId} AND course_version=#{courseVersion}
               AND idempotency_key=#{idempotencyKey} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    LearningSandboxIdempotencyRow lockSandboxQuizIdempotency(@Param("runId") String runId, @Param("userId") Long userId,
                                                              @Param("courseId") String courseId,
                                                              @Param("courseVersion") String courseVersion,
                                                              @Param("idempotencyKey") String idempotencyKey);

    @Update("""
            UPDATE nx_learning_sandbox_idempotency
               SET status=#{status}, result_json=#{resultJson}, updated_at=NOW()
             WHERE run_id=#{runId} AND user_id=#{userId} AND course_id=#{courseId} AND course_version=#{courseVersion}
               AND idempotency_key=#{idempotencyKey} AND status='PENDING' AND is_deleted=0
            """)
    int completeSandboxQuizIdempotency(@Param("runId") String runId, @Param("userId") Long userId, @Param("courseId") String courseId,
                                       @Param("courseVersion") String courseVersion,
                                       @Param("idempotencyKey") String idempotencyKey,
                                       @Param("status") String status, @Param("resultJson") String resultJson);

    @Select("""
            SELECT request_hash AS requestHash, status, response_json AS resultJson
              FROM nx_admin_idempotency_record
             WHERE scope=#{scope} AND idempotency_key=#{idempotencyKey}
               AND is_deleted=0
             LIMIT 1
            """)
    LearningSandboxIdempotencyRow findProductionQuizReceipt(@Param("scope") String scope,
                                                            @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT course_id courseId,course_version courseVersion,progress_pct progress,attempts,last_score lastScore,completed_at completedAt FROM nx_learning_sandbox_progress WHERE run_id=#{runId} AND source='mock' AND source_environment='SANDBOX' ORDER BY updated_at")
    List<Map<String,Object>> sandboxObservationProgress(@Param("runId") String runId);
    @Select("SELECT reward_no rewardNo,user_id userId,course_id courseId,course_version courseVersion,amount_nex amount,status FROM nx_learning_sandbox_reward_ledger WHERE run_id=#{runId} AND source='mock' AND source_environment='SANDBOX' ORDER BY updated_at")
    List<Map<String,Object>> sandboxObservationRewards(@Param("runId") String runId);
    @Select("SELECT user_id userId,course_id courseId,course_version courseVersion,idempotency_key idempotencyKey,status,request_hash requestHash FROM nx_learning_sandbox_idempotency WHERE run_id=#{runId} AND source='mock' AND source_environment='SANDBOX' ORDER BY updated_at")
    List<Map<String,Object>> sandboxObservationIdempotency(@Param("runId") String runId);
    @Select("""
            SELECT COUNT(DISTINCT user_id) userCount,
                   DATE_SUB(MIN(created_at),INTERVAL 1 MINUTE) fromAt,
                   DATE_ADD(MAX(updated_at),INTERVAL 1 MINUTE) toAt
              FROM (SELECT user_id,created_at,updated_at FROM nx_learning_sandbox_progress WHERE run_id=#{runId}
                    UNION ALL SELECT user_id,created_at,updated_at FROM nx_learning_sandbox_event WHERE run_id=#{runId}
                    UNION ALL SELECT user_id,created_at,updated_at FROM nx_learning_sandbox_reward_ledger WHERE run_id=#{runId}
                    UNION ALL SELECT user_id,created_at,updated_at FROM nx_learning_sandbox_idempotency WHERE run_id=#{runId}
                    UNION ALL SELECT NULL user_id,created_at,updated_at FROM nx_learning_sandbox_course WHERE run_id=#{runId}
                    UNION ALL SELECT NULL user_id,created_at,updated_at FROM nx_learning_sandbox_admin_idempotency WHERE run_id=#{runId}) run_facts
            """)
    LearningSandboxObservationWindow sandboxObservationWindow(@Param("runId") String runId);
    @Select("""
            SELECT COUNT(*) FROM nx_learning_progress p WHERE COALESCE(p.updated_at,p.created_at) BETWEEN #{fromAt} AND #{toAt}
              AND EXISTS (SELECT 1 FROM (SELECT user_id,course_id,course_version FROM nx_learning_sandbox_progress WHERE run_id=#{runId}
                                         UNION SELECT user_id,course_id,course_version FROM nx_learning_sandbox_event WHERE run_id=#{runId}
                                         UNION SELECT user_id,course_id,course_version FROM nx_learning_sandbox_reward_ledger WHERE run_id=#{runId}
                                         UNION SELECT user_id,course_id,course_version FROM nx_learning_sandbox_idempotency WHERE run_id=#{runId}) acceptance_fact
                          WHERE acceptance_fact.user_id=p.user_id AND acceptance_fact.course_id=p.course_id AND acceptance_fact.course_version=p.course_version)
            """) int productionLearningProgressDelta(@Param("runId") String runId, @Param("fromAt") java.time.LocalDateTime fromAt, @Param("toAt") java.time.LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_learning_event e WHERE COALESCE(e.updated_at,e.created_at) BETWEEN #{fromAt} AND #{toAt}
              AND EXISTS (SELECT 1 FROM (SELECT user_id,course_id,course_version FROM nx_learning_sandbox_progress WHERE run_id=#{runId}
                                         UNION SELECT user_id,course_id,course_version FROM nx_learning_sandbox_event WHERE run_id=#{runId}
                                         UNION SELECT user_id,course_id,course_version FROM nx_learning_sandbox_reward_ledger WHERE run_id=#{runId}
                                         UNION SELECT user_id,course_id,course_version FROM nx_learning_sandbox_idempotency WHERE run_id=#{runId}) acceptance_fact
                          WHERE acceptance_fact.user_id=e.user_id AND acceptance_fact.course_id=e.course_id AND acceptance_fact.course_version=e.course_version)
            """) int productionLearningEventDelta(@Param("runId") String runId, @Param("fromAt") java.time.LocalDateTime fromAt, @Param("toAt") java.time.LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_learning_reward_ledger r WHERE COALESCE(r.updated_at,r.created_at) BETWEEN #{fromAt} AND #{toAt}
              AND EXISTS (SELECT 1 FROM (SELECT user_id,course_id,course_version FROM nx_learning_sandbox_progress WHERE run_id=#{runId}
                                         UNION SELECT user_id,course_id,course_version FROM nx_learning_sandbox_event WHERE run_id=#{runId}
                                         UNION SELECT user_id,course_id,course_version FROM nx_learning_sandbox_reward_ledger WHERE run_id=#{runId}
                                         UNION SELECT user_id,course_id,course_version FROM nx_learning_sandbox_idempotency WHERE run_id=#{runId}) acceptance_fact
                          WHERE acceptance_fact.user_id=r.user_id AND acceptance_fact.course_id=r.course_id AND acceptance_fact.course_version=r.course_version)
            """) int productionLearningRewardDelta(@Param("runId") String runId, @Param("fromAt") java.time.LocalDateTime fromAt, @Param("toAt") java.time.LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_earnings_release_entry e WHERE COALESCE(e.updated_at,e.created_at) BETWEEN #{fromAt} AND #{toAt}
              AND e.source_ref LIKE 'LEARN:%'
              AND EXISTS (SELECT 1 FROM (SELECT user_id FROM nx_learning_sandbox_progress WHERE run_id=#{runId}
                                         UNION SELECT user_id FROM nx_learning_sandbox_event WHERE run_id=#{runId}
                                         UNION SELECT user_id FROM nx_learning_sandbox_reward_ledger WHERE run_id=#{runId}
                                         UNION SELECT user_id FROM nx_learning_sandbox_idempotency WHERE run_id=#{runId}) acceptance_user
                          WHERE acceptance_user.user_id=e.user_id)
            """) int productionLearningEarningsReleaseDelta(@Param("runId") String runId, @Param("fromAt") java.time.LocalDateTime fromAt, @Param("toAt") java.time.LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_wallet_ledger w WHERE COALESCE(w.updated_at,w.created_at) BETWEEN #{fromAt} AND #{toAt}
              AND w.biz_no LIKE 'LEARN:%'
              AND EXISTS (SELECT 1 FROM (SELECT user_id FROM nx_learning_sandbox_progress WHERE run_id=#{runId}
                                         UNION SELECT user_id FROM nx_learning_sandbox_event WHERE run_id=#{runId}
                                         UNION SELECT user_id FROM nx_learning_sandbox_reward_ledger WHERE run_id=#{runId}
                                         UNION SELECT user_id FROM nx_learning_sandbox_idempotency WHERE run_id=#{runId}) acceptance_user
                          WHERE acceptance_user.user_id=w.user_id)
            """) int productionLearningWalletLedgerDelta(@Param("runId") String runId, @Param("fromAt") java.time.LocalDateTime fromAt, @Param("toAt") java.time.LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_event_outbox o WHERE COALESCE(o.updated_at,o.created_at) BETWEEN #{fromAt} AND #{toAt}
              AND EXISTS (SELECT 1 FROM (SELECT user_id FROM nx_learning_sandbox_progress WHERE run_id=#{runId}
                                         UNION SELECT user_id FROM nx_learning_sandbox_event WHERE run_id=#{runId}
                                         UNION SELECT user_id FROM nx_learning_sandbox_reward_ledger WHERE run_id=#{runId}
                                         UNION SELECT user_id FROM nx_learning_sandbox_idempotency WHERE run_id=#{runId}) acceptance_user
                          WHERE o.aggregate_id LIKE CONCAT(acceptance_user.user_id, ':%'))
    """) int productionLearningOutboxDelta(@Param("runId") String runId, @Param("fromAt") java.time.LocalDateTime fromAt, @Param("toAt") java.time.LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_admin_idempotency_record a
             WHERE COALESCE(a.updated_at,a.created_at) BETWEEN #{fromAt} AND #{toAt}
               AND EXISTS (SELECT 1 FROM nx_learning_sandbox_idempotency s
                            WHERE s.run_id=#{runId}
                              AND a.scope=CONCAT('APP_LEARNING_QUIZ:',SHA2(CONCAT(s.user_id,'|',s.course_id,'|',s.course_version),256)))
            """)
    int productionLearningAdminIdempotencyDelta(@Param("runId") String runId, @Param("fromAt") java.time.LocalDateTime fromAt, @Param("toAt") java.time.LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_learning_course_version v
             WHERE COALESCE(v.updated_at,v.created_at) BETWEEN #{fromAt} AND #{toAt}
               AND EXISTS (SELECT 1 FROM nx_learning_sandbox_course c WHERE c.run_id=#{runId}
                           AND c.course_id=v.course_id AND c.course_version=v.version_label)
            """)
    int productionLearningCatalogVersionDelta(@Param("runId") String runId, @Param("fromAt") java.time.LocalDateTime fromAt, @Param("toAt") java.time.LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_admin_idempotency_record a
             WHERE COALESCE(a.updated_at,a.created_at) BETWEEN #{fromAt} AND #{toAt} AND a.scope LIKE 'I7_COURSE%'
               AND EXISTS (SELECT 1 FROM nx_learning_sandbox_course c WHERE c.run_id=#{runId}
                           AND a.scope LIKE CONCAT('%:',c.course_id,'%'))
            """)
    int productionLearningCatalogAdminIdempotencyDelta(@Param("runId") String runId, @Param("fromAt") java.time.LocalDateTime fromAt, @Param("toAt") java.time.LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_audit_log a
             WHERE a.created_at BETWEEN #{fromAt} AND #{toAt} AND a.action LIKE 'I7_LEARNING_COURSE%'
               AND EXISTS (SELECT 1 FROM nx_learning_sandbox_course c WHERE c.run_id=#{runId}
                           AND (a.resource_id=c.course_id OR a.resource_id=CONCAT(c.course_id,':',c.course_version)))
            """)
    int productionLearningCatalogAuditDelta(@Param("runId") String runId, @Param("fromAt") java.time.LocalDateTime fromAt, @Param("toAt") java.time.LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_event_outbox o
             WHERE COALESCE(o.updated_at,o.created_at) BETWEEN #{fromAt} AND #{toAt}
               AND EXISTS (SELECT 1 FROM nx_learning_sandbox_course c WHERE c.run_id=#{runId}
                           AND (o.aggregate_id=c.course_id OR o.aggregate_id=CONCAT(c.course_id,':',c.course_version)
                                OR o.payload LIKE CONCAT('%',c.course_id,'%')))
            """)
    int productionLearningCatalogOutboxDelta(@Param("runId") String runId, @Param("fromAt") java.time.LocalDateTime fromAt, @Param("toAt") java.time.LocalDateTime toAt);

    @Select("""
            SELECT COUNT(*) FROM nx_learning_reward_ledger
             WHERE user_id = #{userId} AND course_id = #{courseId}
               AND course_version = #{courseVersion} AND status = 'GRANTED' AND is_deleted = 0
            """)
    int countGrantedReward(@Param("userId") Long userId,
                           @Param("courseId") String courseId,
                           @Param("courseVersion") String courseVersion);

    @Insert("""
            INSERT IGNORE INTO nx_learning_reward_ledger (
                reward_no, user_id, course_id, course_version, amount_nex, status,
                created_at, updated_at, is_deleted
            ) VALUES (#{rewardNo}, #{userId}, #{courseId}, #{courseVersion}, #{amount}, 'GRANTED', NOW(), NOW(), 0)
            """)
    int grantReward(@Param("rewardNo") String rewardNo,
                    @Param("userId") Long userId,
                    @Param("courseId") String courseId,
                    @Param("courseVersion") String courseVersion,
                    @Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO nx_user_wallet (
                user_id, usdt_available, nex_available, pending_withdraw, lifetime_earned,
                version, created_at, updated_at, is_deleted
            ) VALUES (#{userId}, 0, #{amount}, 0, #{amount}, 0, NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE
                nex_available = nex_available + VALUES(nex_available),
                lifetime_earned = lifetime_earned + VALUES(lifetime_earned),
                version = version + 1, updated_at = NOW(), is_deleted = 0
            """)
    int creditWallet(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Select("""
            SELECT COALESCE(SUM(amount_nex), 0) FROM nx_learning_reward_ledger
             WHERE user_id = #{userId} AND status = 'GRANTED' AND is_deleted = 0
            """)
    BigDecimal sumGrantedReward(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(r.amount_nex), 0)
              FROM nx_learning_reward_ledger r
              JOIN nx_user u ON u.id = r.user_id AND u.is_deleted = 0 AND u.sandbox = 0
              JOIN nx_user_wallet w ON w.user_id = r.user_id AND w.is_deleted = 0 AND w.sandbox = 0
             WHERE r.status = 'GRANTED' AND r.is_deleted = 0
               AND r.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
            """)
    BigDecimal sumGrantedRewardThisWeek();
}
