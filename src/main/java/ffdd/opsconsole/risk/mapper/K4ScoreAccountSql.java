package ffdd.opsconsole.risk.mapper;

public final class K4ScoreAccountSql {
    private K4ScoreAccountSql() { }

    private static final String CLASSIFIED_SCORES = """
            , scores AS (
                SELECT s.user_no,s.model_score,s.model_version AS score_model_version,s.as_of,
                       COALESCE(o.override_score,s.model_score) AS effective_score,
                       o.id IS NOT NULL AS overridden,
                       CASE
                           WHEN s.id IS NULL THEN NULL
                           WHEN s.model_version<>m.model_version OR s.as_of IS NULL
                                OR s.as_of<DATE_SUB(m.evaluated_at, INTERVAL 1 DAY) THEN 'STALE'
                           WHEN COALESCE(o.override_score,s.model_score)>=m.auto_escalate_score THEN 'AUTO_REPORT'
                           WHEN COALESCE(o.override_score,s.model_score)>=m.band_high_min THEN 'HIGH_BELOW_AUTO'
                           WHEN COALESCE(o.override_score,s.model_score)>=m.band_low_max THEN 'MEDIUM'
                           ELSE 'LOW'
                       END AS band
                  FROM active_model m
                  LEFT JOIN (
                      nx_admin_risk_score_user s
                      JOIN nx_user u
                        ON CONCAT('U',LPAD(u.id,GREATEST(8,CHAR_LENGTH(CAST(u.id AS CHAR))),'0'))=s.user_no
                       AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0
                  ) ON s.is_deleted=0
                  LEFT JOIN nx_admin_risk_score_override o ON o.active_user_key=s.user_no
            )
            """;

    public static final String SNAPSHOT = """
            WITH active_model AS (
                SELECT CONCAT('k4-v',model_version) AS model_version,band_low_max,band_high_min,
                       auto_escalate_score,NOW() AS evaluated_at
                  FROM nx_admin_risk_score_model
                 WHERE state='active' AND is_deleted=0
                 ORDER BY model_version DESC
                 LIMIT 1
            )
            """ + CLASSIFIED_SCORES + """
            SELECT m.model_version AS modelVersion,m.band_low_max AS bandLowMax,
                   m.band_high_min AS bandHighMin,m.auto_escalate_score AS autoEscalateScore,
                   DATE_FORMAT(m.evaluated_at,'%Y-%m-%d %H:%i:%s') AS evaluatedAt,
                   COUNT(CASE WHEN band<>'STALE' THEN 1 END) AS totalUsers,
                   COUNT(CASE WHEN band='AUTO_REPORT' THEN 1 END) AS autoEscalated,
                   COUNT(CASE WHEN band='HIGH_BELOW_AUTO' THEN 1 END) AS highBelowAuto,
                   COUNT(CASE WHEN band IN ('AUTO_REPORT','HIGH_BELOW_AUTO') THEN 1 END) AS highRisk,
                   COUNT(CASE WHEN band='MEDIUM' THEN 1 END) AS mediumRisk,
                   COUNT(CASE WHEN band='LOW' THEN 1 END) AS lowRisk,
                   COUNT(CASE WHEN band IN ('AUTO_REPORT','HIGH_BELOW_AUTO','MEDIUM') THEN 1 END) AS flaggedAccounts,
                   COUNT(CASE WHEN band<>'STALE' AND overridden THEN 1 END) AS activeOverrides,
                   COUNT(CASE WHEN band='STALE' THEN 1 END) AS staleScoreUsers
              FROM active_model m CROSS JOIN scores
             GROUP BY m.model_version,m.band_low_max,m.band_high_min,m.auto_escalate_score,m.evaluated_at
            """;

    static final String PAGE = """
            WITH active_model AS (
                SELECT #{modelVersion} AS model_version,#{bandLowMax} AS band_low_max,
                       #{bandHighMin} AS band_high_min,#{autoEscalateScore} AS auto_escalate_score,
                       CAST(#{evaluatedAt} AS DATETIME) AS evaluated_at
            )
            """ + CLASSIFIED_SCORES + """
            SELECT user_no AS userNo,model_score AS modelScore,effective_score AS effectiveScore,
                   overridden,score_model_version AS modelVersion,as_of AS asOf
              FROM scores
             WHERE band=#{band} OR (#{band}='HIGH' AND band IN ('AUTO_REPORT','HIGH_BELOW_AUTO'))
             ORDER BY effective_score DESC,user_no ASC
             LIMIT #{limit} OFFSET #{offset}
            """;
}
