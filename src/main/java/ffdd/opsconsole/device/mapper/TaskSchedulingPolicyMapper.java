package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.device.domain.TaskSchedulingSettings;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TaskSchedulingPolicyMapper extends BaseMapper<Object> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_compute_task_policy_v2 (
              task_id VARCHAR(64) NOT NULL PRIMARY KEY,
              selection_weight INT NOT NULL DEFAULT 1,
              expected_duration_min_sec INT NOT NULL,
              expected_duration_max_sec INT NOT NULL,
              execution_timeout_sec INT NOT NULL,
              runtime_key VARCHAR(96) NOT NULL DEFAULT '',
              validator_key VARCHAR(96) NOT NULL DEFAULT '',
              workload_config_json JSON NOT NULL,
              revision BIGINT NOT NULL DEFAULT 1,
              CONSTRAINT chk_compute_v2_policy_weight CHECK(selection_weight BETWEEN 1 AND 1000000),
              CONSTRAINT chk_compute_v2_policy_duration CHECK(expected_duration_min_sec BETWEEN 1 AND 86400
                AND expected_duration_max_sec BETWEEN expected_duration_min_sec AND 86400
                AND execution_timeout_sec BETWEEN expected_duration_max_sec AND 172800)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void ensureSchema();

    String COLUMNS = "task_id AS taskId, selection_weight AS selectionWeight, expected_duration_min_sec AS expectedDurationMinSec, "
            + "expected_duration_max_sec AS expectedDurationMaxSec, execution_timeout_sec AS executionTimeoutSec, "
            + "runtime_key AS runtimeKey, validator_key AS validatorKey, workload_config_json AS workloadConfigJson, revision";

    @Select("SELECT " + COLUMNS + " FROM nx_compute_task_policy_v2 WHERE task_id=#{taskId}")
    Policy find(@Param("taskId") String taskId);

    @Select("<script>SELECT " + COLUMNS + " FROM nx_compute_task_policy_v2 WHERE task_id IN "
            + "<foreach collection='taskIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Policy> findMany(@Param("taskIds") List<String> taskIds);

    @Insert("""
            INSERT INTO nx_compute_task_policy_v2(task_id,selection_weight,expected_duration_min_sec,
              expected_duration_max_sec,execution_timeout_sec,runtime_key,validator_key,workload_config_json,revision)
            VALUES(#{taskId},#{s.selectionWeight},#{s.expectedDurationMinSec},#{s.expectedDurationMaxSec},
              #{s.executionTimeoutSec},#{s.runtimeKey},#{s.validatorKey},#{s.workloadConfigJson},1)
            ON DUPLICATE KEY UPDATE selection_weight=VALUES(selection_weight),
              expected_duration_min_sec=VALUES(expected_duration_min_sec),expected_duration_max_sec=VALUES(expected_duration_max_sec),
              execution_timeout_sec=VALUES(execution_timeout_sec),runtime_key=VALUES(runtime_key),
              validator_key=VALUES(validator_key),workload_config_json=VALUES(workload_config_json),revision=revision+1
            """)
    int save(@Param("taskId") String taskId, @Param("s") TaskSchedulingSettings settings);

    record Policy(String taskId,int selectionWeight,int expectedDurationMinSec,int expectedDurationMaxSec,
            int executionTimeoutSec,String runtimeKey,String validatorKey,String workloadConfigJson,long revision) {
        public TaskSchedulingSettings settings() { return new TaskSchedulingSettings(selectionWeight,expectedDurationMinSec,
                expectedDurationMaxSec,executionTimeoutSec,runtimeKey,validatorKey,workloadConfigJson); }
    }
}
