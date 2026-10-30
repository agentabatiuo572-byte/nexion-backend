package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Narrow TEST-only persistence. Every mutation is called under the existing user/device/task locks. */
@Mapper
public interface TestCloudExecutorMapper extends BaseMapper<Object> {
    // Shared by dispatch exclusion and the executor: paid ownership is authoritative, not a client assertion.
    String PAID_FLEET_FROM = """
          FROM nx_user_device d
          JOIN nx_user u ON u.id=d.user_id AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=0
          JOIN nx_order o ON o.order_no=d.source_order_no AND o.user_id=d.user_id
          JOIN nx_product p ON p.id=d.product_id AND p.product_no=d.product_code
         WHERE d.is_deleted=0 AND d.source_environment='PRODUCTION' AND d.run_id=''
           AND UPPER(d.ownership_status)='OWNED'
           AND UPPER(d.device_type) NOT IN ('PHONE','MOBILE')
           AND d.deactivated_at IS NULL
           AND o.is_deleted=0 AND o.payment_status='PAID' AND o.order_status='COMPLETED'
           AND o.subtotal_usdt>0 AND o.amount_usdt>=0
           AND ((d.source_channel='ORDER' AND o.activation_status='ACTIVATED'
           AND d.purchased_at>=#{cutoff} AND d.activated_at>=#{cutoff}
           AND (o.product_id=d.product_id OR EXISTS(SELECT 1 FROM nx_order_item oi
                 WHERE oi.order_no=o.order_no AND oi.product_id=d.product_id AND oi.is_deleted=0))
           AND (SELECT COUNT(*) FROM nx_user_device owned WHERE owned.source_order_no=o.order_no
                 AND owned.user_id=d.user_id AND owned.product_id=d.product_id AND owned.is_deleted=0
                 AND owned.source_channel='ORDER' AND owned.source_environment='PRODUCTION'
                 AND UPPER(owned.ownership_status)='OWNED')
               <= CASE WHEN o.order_type='BUNDLE' THEN (SELECT COALESCE(SUM(oi.quantity),0)
                    FROM nx_order_item oi WHERE oi.order_no=o.order_no AND oi.product_id=d.product_id AND oi.is_deleted=0)
                  ELSE o.quantity END)
           OR (d.source_channel='TRADE_IN' AND #{tradeinCutoff} IS NOT NULL
           AND d.purchased_at>=#{tradeinCutoff} AND d.activated_at>=#{tradeinCutoff}
           AND o.order_type='TRADE_IN' AND o.quantity=1 AND o.item_count=1
           AND o.activation_status IN ('ACTIVATED','INVENTORY')
           AND (SELECT COUNT(*) FROM nx_user_device delivered
                 WHERE delivered.source_order_no=o.order_no AND delivered.is_deleted=0)=1
           AND (SELECT COUNT(*) FROM nx_order_item oi WHERE oi.order_no=o.order_no AND oi.is_deleted=0)=1
           AND EXISTS(SELECT 1 FROM nx_order_item oi WHERE oi.order_no=o.order_no AND oi.is_deleted=0
                 AND oi.product_id=d.product_id AND oi.product_no=d.product_code AND oi.quantity=1
                 AND oi.unit_price_usdt=o.subtotal_usdt AND oi.line_amount_usdt=o.subtotal_usdt)
           AND (SELECT COUNT(*) FROM nx_tradein_application claimed WHERE claimed.is_deleted=0
                 AND (claimed.target_order_no=o.order_no OR claimed.target_device_id=d.id))=1
           AND EXISTS(SELECT 1 FROM nx_tradein_application a
                 JOIN nx_trade_in_order c ON c.trade_in_no=a.tradein_no AND c.user_id=a.user_id
                   AND c.source_device_id=a.source_device_id AND c.target_product_id=a.target_product_id
                 JOIN nx_user_device source ON source.id=a.source_device_id AND source.user_id=a.user_id
                   AND source.instance_no=a.source_instance_no AND source.product_id=a.source_product_id
                WHERE a.user_id=d.user_id AND a.target_device_id=d.id AND a.target_order_no=o.order_no
                  AND a.target_product_id=d.product_id AND o.product_id=d.product_id
                  AND a.source_device_id<>d.id AND a.status='COMPLETED' AND a.is_deleted=0
                  AND a.completed_at>=#{tradeinCutoff} AND a.submitted_at>=#{tradeinCutoff}
                  AND c.status='COMPLETED' AND c.is_deleted=0
                  AND source.is_deleted=0 AND source.source_environment='PRODUCTION' AND source.run_id=''
                  AND a.source_price_usdt>0 AND a.target_price_usdt=o.subtotal_usdt
                  AND a.target_price_usdt=d.price_usdt_snapshot AND a.tradein_discount_usdt>=0
                  AND a.tradein_discount_usdt=o.discount_usdt AND c.valuation_usdt=a.tradein_discount_usdt
                  AND a.wallet_debit_usdt=o.amount_usdt AND a.net_upgrade_cost_usdt=a.wallet_debit_usdt
                  AND a.tradein_discount_usdt+a.wallet_debit_usdt=a.target_price_usdt
                  AND ((o.order_no REGEXP '^TIO-[0-9A-F]{32}$'
                    AND a.tradein_no=CONCAT('TIN-',SUBSTRING(o.order_no,5))
                    AND d.instance_no=CONCAT('DEV-TI-',SUBSTRING(o.order_no,5))
                    AND UPPER(source.ownership_status)='RECYCLED' AND UPPER(source.status)='RECYCLED'
                    AND source.activated_at IS NULL AND source.deactivated_at IS NOT NULL)
                  OR (o.order_no REGEXP '^CPO-[0-9A-F]{32}$'
                    AND a.tradein_no=CONCAT('CPR-',SUBSTRING(o.order_no,5))
                    AND d.instance_no=CONCAT('DEV-CPR-',SUBSTRING(o.order_no,5))
                    AND a.tradein_discount_usdt=0)))))
        """;
    String FLEET_LIVE = """
           AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
           AND NOT EXISTS(SELECT 1 FROM nx_compute_dc_ops_state dc WHERE dc.dc_location=d.dc_location
                    AND dc.is_deleted=0 AND dc.dispatch_paused=1)
           AND NOT EXISTS(SELECT 1 FROM nx_user_device_runtime r WHERE r.user_device_id=d.id AND r.is_deleted=0
                    AND (COALESCE(r.paused_reason,'')<>'' OR (COALESCE(r.agent_version,'')<>''
                          AND r.agent_version<>CONCAT('test-cloud:fleet-',d.id))))
           AND NOT EXISTS(SELECT 1 FROM nx_compute_task t WHERE t.user_device_id=d.id AND t.user_id=d.user_id
                    AND t.is_deleted=0 AND t.source_environment='PRODUCTION' AND t.status IN ('CLAIMED','RUNNING')
                    AND NOT EXISTS(SELECT 1 FROM nx_test_cloud_execution e WHERE e.task_no=t.task_no))
        """;
    String BINDING_COLUMNS = "executor_id executorId,owner_id ownerId,device_id deviceId,instance_no instanceNo,"
            + "task_config_id taskConfigId,model_tag model,model_digest modelDigest";

    @Select("SELECT " + BINDING_COLUMNS + " FROM nx_test_cloud_binding WHERE executor_id=#{executorId}")
    Binding binding(@Param("executorId") String executorId);

    @Select("SELECT d.user_id ownerId,d.id deviceId,d.instance_no instanceNo " + PAID_FLEET_FROM
            + FLEET_LIVE + """
             AND (d.pending_deactivate=0 OR EXISTS(SELECT 1 FROM nx_compute_task t
                   JOIN nx_test_cloud_execution e ON e.task_no=t.task_no AND e.executor_id=CONCAT('fleet-',d.id)
                  WHERE t.user_device_id=d.id AND t.user_id=d.user_id AND t.source_environment='PRODUCTION'
                    AND t.is_deleted=0 AND t.status IN ('CLAIMED','RUNNING')))
             AND NOT EXISTS(SELECT 1 FROM nx_test_cloud_binding b WHERE b.device_id=d.id
                   AND (b.owner_id<>d.user_id OR b.instance_no<>d.instance_no OR b.executor_id<>CONCAT('fleet-',d.id)))
             AND d.id>#{afterDeviceId} AND d.id<>#{legacyDeviceId} ORDER BY d.id LIMIT #{limit}
            """)
    List<FleetCandidate> fleetCandidates(@Param("cutoff") LocalDateTime cutoff,@Param("tradeinCutoff") LocalDateTime tradeinCutoff,@Param("afterDeviceId") long afterDeviceId,
                                        @Param("legacyDeviceId") long legacyDeviceId,@Param("limit") int limit);

    @Select("SELECT d.user_id ownerId,d.id deviceId,d.instance_no instanceNo " + PAID_FLEET_FROM
            + " AND d.id=#{deviceId} AND d.id<>#{legacyDeviceId}")
    FleetCandidate fleetCandidate(@Param("cutoff") LocalDateTime cutoff,@Param("tradeinCutoff") LocalDateTime tradeinCutoff,@Param("deviceId") long deviceId,
                                  @Param("legacyDeviceId") long legacyDeviceId);

    @Select("SELECT 1 " + PAID_FLEET_FROM + FLEET_LIVE
            + " AND (#{completing} OR d.pending_deactivate=0) AND d.id=#{deviceId} AND d.user_id=#{ownerId} AND d.instance_no=#{instanceNo} FOR UPDATE OF o,d")
    Integer liveFleetBinding(@Param("cutoff") LocalDateTime cutoff,@Param("tradeinCutoff") LocalDateTime tradeinCutoff,@Param("ownerId") long ownerId,
                         @Param("deviceId") long deviceId,@Param("instanceNo") String instanceNo,@Param("completing") boolean completing);

    @Insert("""
        INSERT IGNORE INTO nx_test_cloud_binding(executor_id,owner_id,device_id,instance_no,task_config_id,model_tag,model_digest,created_at)
        VALUES(#{b.executorId},#{b.ownerId},#{b.deviceId},#{b.instanceNo},#{b.taskConfigId},#{b.model},#{b.modelDigest},#{now})
        """)
    int register(@Param("b") Binding binding,@Param("now") LocalDateTime now);

    @Select("SELECT " + BINDING_COLUMNS + " FROM nx_test_cloud_binding ORDER BY device_id")
    List<Binding> bindings();

    record FleetCandidate(long ownerId,long deviceId,String instanceNo) { }
    record Binding(String executorId,long ownerId,long deviceId,String instanceNo,String taskConfigId,String model,String modelDigest) { }

    @Select("""
        SELECT t.status,t.task_config_id taskConfigId,t.model_name modelName,t.client_name clientName,
               r.receipt_no receiptNo,r.proof_hash proofHash,r.earning_status earningStatus
          FROM nx_compute_task t
          LEFT JOIN nx_compute_receipt r ON r.task_no=t.task_no AND r.user_id=t.user_id
             AND r.user_device_id=t.user_device_id AND r.source_environment='PRODUCTION' AND r.is_deleted=0
             AND r.completed_at=t.completed_at AND r.task_type=t.task_type
         WHERE t.task_no=#{taskNo} AND t.user_id=#{ownerId} AND t.user_device_id=#{deviceId}
           AND t.source_environment='PRODUCTION' AND t.is_deleted=0
        """)
    HistoricalReceipt historicalReceipt(@Param("taskNo") String taskNo,@Param("ownerId") long ownerId,@Param("deviceId") long deviceId);
    record HistoricalReceipt(String status,String taskConfigId,String modelName,String clientName,String receiptNo,String proofHash,String earningStatus) { }
    String COLUMNS = "task_no taskNo,executor_id executorId,owner_id ownerId,device_id deviceId,instance_no instanceNo,"
            + "task_config_id taskConfigId,next_key_hash nextKeyHash,claim_token claimToken,input_json inputJson,"
            + "input_hash inputHash,model_tag modelTag,model_digest modelDigest,proof_nonce proofNonce,"
            + "result_hash resultHash,result_artifact resultArtifact,complete_key_hash completeKeyHash,"
            + "complete_body_hash completeBodyHash,created_at createdAt";

    @Select("SELECT " + COLUMNS + " FROM nx_test_cloud_execution WHERE task_no=#{taskNo} FOR UPDATE")
    Execution lock(@Param("taskNo") String taskNo);
    @Select("SELECT " + COLUMNS + " FROM nx_test_cloud_execution WHERE executor_id=#{executor} AND next_key_hash=#{keyHash} FOR UPDATE")
    Execution byNextKey(@Param("executor") String executor, @Param("keyHash") String keyHash);

    @Insert("""
        INSERT INTO nx_test_cloud_execution(task_no,executor_id,owner_id,device_id,instance_no,task_config_id,
          next_key_hash,claim_token,input_json,input_hash,model_tag,model_digest,proof_nonce,created_at)
        VALUES(#{e.taskNo},#{e.executorId},#{e.ownerId},#{e.deviceId},#{e.instanceNo},#{e.taskConfigId},
          #{e.nextKeyHash},#{e.claimToken},#{e.inputJson},#{e.inputHash},#{e.modelTag},#{e.modelDigest},#{e.proofNonce},#{e.createdAt})
        """)
    int insert(@Param("e") Execution e);

    @Update("""
        UPDATE nx_test_cloud_execution SET result_hash=#{hash},result_artifact=#{artifact},
          complete_key_hash=#{keyHash},complete_body_hash=#{bodyHash}
         WHERE task_no=#{taskNo} AND result_hash IS NULL AND result_artifact IS NULL
        """)
    int saveResult(@Param("taskNo") String taskNo,@Param("hash") String hash,@Param("artifact") byte[] artifact,
                   @Param("keyHash") String keyHash,@Param("bodyHash") String bodyHash);

    @Select("""
        SELECT COUNT(*) FROM nx_user_device WHERE id=#{deviceId} AND user_id=#{ownerId} AND instance_no=#{instanceNo}
          AND pending_deactivate=0 AND is_deleted=0 AND source_environment='PRODUCTION' AND run_id=''
          AND source_channel='ORDER' AND ownership_status='OWNED' AND activated_at IS NOT NULL AND deactivated_at IS NULL
        """)
    int eligibleBinding(@Param("ownerId") long ownerId,@Param("deviceId") long deviceId,@Param("instanceNo") String instanceNo);

    @Update("""
        UPDATE nx_compute_task t SET t.status='CANCELLED',t.last_error='TEST_CLOUD_UNEXECUTED_REPLACED',t.updated_at=#{now}
         WHERE t.user_id=#{ownerId} AND t.user_device_id=#{deviceId} AND t.task_no=#{taskNo}
           AND t.task_config_id=#{config} AND t.model_name='BGE-M3' AND t.client_name='UVEL App'
           AND t.status IN ('CLAIMED','RUNNING') AND t.source_environment='PRODUCTION' AND t.is_deleted=0
           AND t.proof_consumed_at IS NULL AND t.completed_at IS NULL AND COALESCE(t.last_error,'')=''
           AND NOT EXISTS(SELECT 1 FROM nx_test_cloud_execution e WHERE e.task_no=t.task_no)
           AND NOT EXISTS(SELECT 1 FROM nx_compute_receipt r WHERE r.task_no=t.task_no)
           AND NOT EXISTS(SELECT 1 FROM nx_wallet_ledger l WHERE l.biz_no=t.task_no)
           AND NOT EXISTS(SELECT 1 FROM nx_audit_log a WHERE a.resource_id=t.task_no
                            AND a.action<>'TASK_ASSIGNMENT_CLAIMED')
           AND NOT EXISTS(SELECT 1 FROM nx_user_device_runtime r WHERE r.user_device_id=t.user_device_id
                            AND (COALESCE(r.agent_version,'')<>'' OR COALESCE(r.client_name,'') NOT IN ('','UVEL App')))
        """)
    int cancelUnexecuted(@Param("ownerId") long ownerId,@Param("deviceId") long deviceId,@Param("taskNo") String taskNo,
                         @Param("config") String config,@Param("now") LocalDateTime now);

    @Insert("""
        INSERT IGNORE INTO nx_user_device_runtime(user_device_id,online_status,client_name,agent_version,heartbeat_at,
          created_at,updated_at,is_deleted) VALUES(#{deviceId},'OFFLINE','UVEL TEST BGE-M3',#{marker},#{now},#{now},#{now},0)
        """)
    int ensureRuntime(@Param("deviceId") long deviceId,@Param("marker") String marker,@Param("now") LocalDateTime now);

    @Update("""
        UPDATE nx_user_device_runtime SET online_status='ONLINE',client_name='UVEL TEST BGE-M3',agent_version=#{marker},
          active_task_no=#{taskNo},heartbeat_at=#{now},updated_at=#{now}
         WHERE user_device_id=#{deviceId} AND is_deleted=0 AND COALESCE(paused_reason,'')=''
           AND online_status IN ('ONLINE','OFFLINE')
           AND (COALESCE(agent_version,'')='' OR agent_version=#{marker})
           AND (COALESCE(active_task_no,'')='' OR active_task_no=#{taskNo})
        """)
    int heartbeat(@Param("deviceId") long deviceId,@Param("taskNo") String taskNo,@Param("marker") String marker,
                  @Param("now") LocalDateTime now);

    @Update("""
        UPDATE nx_compute_task SET task_name='UVEL TEST BGE-M3 inference',model_name='TEST_BGE_M3_V1',
          client_name='UVEL TEST BGE-M3',worker_ack_at=#{now},updated_at=#{now}
         WHERE task_no=#{taskNo} AND user_id=#{ownerId} AND user_device_id=#{deviceId}
           AND task_config_id=#{config} AND model_name='BGE-M3' AND status='RUNNING'
           AND source_environment='PRODUCTION' AND is_deleted=0 AND proof_consumed_at IS NULL AND completed_at IS NULL
        """)
    int acknowledge(@Param("ownerId") long ownerId,@Param("deviceId") long deviceId,@Param("taskNo") String taskNo,
                    @Param("config") String config,@Param("now") LocalDateTime now);

    @Update("""
        UPDATE nx_user_device_runtime r JOIN nx_user_device d ON d.id=r.user_device_id
           SET r.online_status='OFFLINE',r.updated_at=#{now}
         WHERE d.id=#{deviceId} AND d.user_id=#{ownerId} AND d.instance_no=#{instanceNo}
           AND d.source_environment='PRODUCTION' AND d.run_id='' AND d.is_deleted=0
           AND r.agent_version=#{marker} AND r.client_name='UVEL TEST BGE-M3'
           AND r.heartbeat_at<#{cutoff} AND r.is_deleted=0 AND COALESCE(r.paused_reason,'')=''
           AND r.online_status='ONLINE'
        """)
    int expireHeartbeat(@Param("ownerId") long ownerId,@Param("deviceId") long deviceId,@Param("instanceNo") String instanceNo,@Param("marker") String marker,
                        @Param("now") LocalDateTime now,@Param("cutoff") LocalDateTime cutoff);

    record Execution(String taskNo,String executorId,long ownerId,long deviceId,String instanceNo,String taskConfigId,
                     String nextKeyHash,String claimToken,String inputJson,String inputHash,String modelTag,String modelDigest,
                     String proofNonce,String resultHash,byte[] resultArtifact,String completeKeyHash,String completeBodyHash,
                     LocalDateTime createdAt) { }
}
