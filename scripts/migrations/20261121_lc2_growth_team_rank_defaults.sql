-- B82: seed only an empty F1 catalog. Run ahead of the historical LC2 seed so
-- existing databases and operator settings stay intact on upgrade or restart.
SET @b82_f1_seed = (
  NOT EXISTS (SELECT 1 FROM nx_v_rank_config)
  AND NOT EXISTS (SELECT 1 FROM nx_v_rank_reward_rule)
  AND NOT EXISTS (SELECT 1 FROM nx_audit_object_lock WHERE target_domain = 'F'
    AND target_type = 'vrank_bootstrap' AND target_id = 'current' AND is_deleted = 0)
);

INSERT INTO nx_v_rank_config (
  rank_code, title_cn, title_en, self_compute_points, direct_refs, team_compute_points,
  required_downline_rank, required_downline_count, unilevel_depth, peer_bonus_rate,
  leadership_votes, sort_order, status, is_deleted
)
SELECT seed.rank_code, seed.rank_code, seed.rank_code, IF(seed.sort_order = 0, 0, 1200),
       seed.direct_refs, seed.team_points, seed.branch_rank, seed.branch_count,
       NULL, 0, 0, seed.sort_order, 1, 0
  FROM (
    SELECT 'V0' AS rank_code, 0 AS direct_refs, 0 AS team_points, CAST(NULL AS CHAR(2)) AS branch_rank,
           0 AS branch_count, 0 AS sort_order
    UNION ALL SELECT 'V1', 0, 0, NULL, 0, 1
    UNION ALL SELECT 'V2', 2, 3000, NULL, 0, 2
    UNION ALL SELECT 'V3', 3, 10000, 'V1', 2, 3
    UNION ALL SELECT 'V4', 4, 30000, 'V2', 2, 4
    UNION ALL SELECT 'V5', 5, 100000, 'V3', 2, 5
    UNION ALL SELECT 'V6', 6, 300000, 'V4', 3, 6
    UNION ALL SELECT 'V7', 8, 1000000, 'V5', 3, 7
    UNION ALL SELECT 'V8', 10, 3000000, 'V6', 3, 8
  ) seed
 WHERE @b82_f1_seed
 ORDER BY seed.sort_order;
