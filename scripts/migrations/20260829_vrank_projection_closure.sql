-- Reconciles historical V-Rank projections with the engine's canonical
-- nx_team_member self-loop row. Future promotions keep all projections in one
-- transaction through MybatisTeamCommissionRepository.updateMemberVRank.
SET NAMES utf8mb4;

UPDATE nx_user u
JOIN (
  SELECT self_row.user_id,self_row.v_rank
    FROM nx_team_member self_row
    JOIN (
      SELECT user_id,MAX(id) latest_id
        FROM nx_team_member
       WHERE user_id=member_user_id AND is_deleted=0
       GROUP BY user_id
    ) latest ON latest.latest_id=self_row.id
) canonical ON canonical.user_id=u.id
   SET u.v_rank=canonical.v_rank,
       u.updated_at=NOW()
 WHERE u.is_deleted=0;

UPDATE nx_team_member projection
JOIN (
  SELECT self_row.user_id,self_row.v_rank
    FROM nx_team_member self_row
    JOIN (
      SELECT user_id,MAX(id) latest_id
        FROM nx_team_member
       WHERE user_id=member_user_id AND is_deleted=0
       GROUP BY user_id
    ) latest ON latest.latest_id=self_row.id
) canonical ON canonical.user_id=projection.member_user_id
   SET projection.v_rank=canonical.v_rank,
       projection.updated_at=NOW()
 WHERE projection.is_deleted=0;
