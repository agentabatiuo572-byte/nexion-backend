package ffdd.opsconsole.device.mapper;

/** A separate committed zero-credit fact can close only its exact original COMPLETE request. */
public final class LegacyZeroCreditResolutionSql {
    private LegacyZeroCreditResolutionSql() { }
    public static final String CASE_SET_SHA = "6140f926c7986c9da769c30bd58a5cd10ef36aaf7b01b0fe2bbff5b921a29260";

    /** Correlated to the original request alias r; task cancellation alone never closes UNKNOWN. */
    public static final String RESOLVED_REQUEST = """
        SELECT 1 FROM nx_test_cloud_zero_credit_resolution z
        JOIN nx_compute_task resolved_task ON resolved_task.task_no=z.task_no
          AND CAST(resolved_task.task_no AS BINARY)=CAST(z.task_no AS BINARY)
          AND resolved_task.user_id=z.owner_id AND resolved_task.user_device_id=z.device_id
          AND CAST(resolved_task.task_config_id AS BINARY)=CAST(z.task_config_id AS BINARY)
          AND resolved_task.is_deleted=0 AND resolved_task.source_environment='PRODUCTION'
          AND resolved_task.status='CANCELLED' AND resolved_task.completed_at IS NULL
        WHERE z.request_id=r.id AND z.request_generation=r.generation
          AND z.owner_id=r.owner_id AND z.device_id=r.device_id
          AND CAST(z.executor_id AS BINARY)=CAST(r.executor_id AS BINARY)
          AND CAST(z.operation AS BINARY)=CAST(r.operation AS BINARY) AND r.operation='COMPLETE'
          AND CAST(z.request_key_hash AS BINARY)=CAST(r.key_hash AS BINARY)
          AND CAST(z.request_body_hash AS BINARY)=CAST(r.body_hash AS BINARY)
          AND CAST(z.instance_no AS BINARY)=CAST(r.instance_no AS BINARY)
          AND CAST(z.task_config_id AS BINARY)=CAST(r.task_config_id AS BINARY)
          AND CAST(z.task_no AS BINARY)=CAST(r.task_no AS BINARY)
          AND z.deployment_scope='TEST' AND z.outcome='ZERO_CREDIT_CANCELLED' AND z.task_status='CANCELLED'
        """+" AND z.case_set_sha256='"+CASE_SET_SHA+"' "+"""
          AND CAST(z.incident_case_id AS BINARY)=CAST(CONCAT('V1-UNKNOWN-20261101-',r.device_id) AS BINARY)
        """;
}
