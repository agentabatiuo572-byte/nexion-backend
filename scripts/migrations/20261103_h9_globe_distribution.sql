-- Convert each published legacy globe in place. Keep its city order, nodes and latency
-- for older clients. A globe without saved settings uses the same defaults in Java.
CREATE TEMPORARY TABLE h9_globe_shares (id BIGINT PRIMARY KEY, old_centers JSON, new_centers JSON);
START TRANSACTION;
INSERT INTO h9_globe_shares (id, old_centers, new_centers)
WITH cities AS (
    SELECT c.id, j.ord, j.city, j.nodes, j.has_share,
           JSON_EXTRACT(c.config_value, '$.globeDisplay.centers') old_centers
    FROM nx_config_item c
    JOIN JSON_TABLE(IF(JSON_VALID(c.config_value), c.config_value, '{}'), '$.globeDisplay.centers[*]'
         COLUMNS (ord FOR ORDINALITY, city VARCHAR(12) PATH '$.id', nodes BIGINT PATH '$.nodes',
                  has_share INT EXISTS PATH '$.sharePct')) j
    WHERE c.is_deleted = 0 AND (c.config_key = 'growth.public_stats.values' OR c.config_key LIKE 'h9.sb.%.data')
), legacy AS (
    SELECT id, SUM(nodes) node_total FROM cities GROUP BY id
    HAVING COUNT(*) = 8 AND COUNT(DISTINCT city) = 8 AND MAX(has_share) = 0
       AND SUM(city IN ('sg','jp','vn','uk','fr','de','us-ny','us-sj') AND nodes BETWEEN 0 AND 100000000) = 8
), portions AS (
    SELECT cities.*, IF(node_total = 0, 1250, (nodes * 10000) DIV node_total) cents,
           IF(node_total = 0, 0, MOD(nodes * 10000, node_total)) remainder_weight
    FROM cities JOIN legacy USING (id)
), ranked AS (
    SELECT portions.*, 10000 - SUM(cents) OVER (PARTITION BY id) remaining,
           ROW_NUMBER() OVER (PARTITION BY id ORDER BY remainder_weight DESC, ord) remainder_rank
    FROM portions
), assembled AS (
    SELECT id, ord, old_centers,
           JSON_ARRAYAGG(JSON_SET(JSON_EXTRACT(old_centers, CONCAT('$[',ord-1,']')), '$.sharePct',
                         CAST((cents + (remainder_rank <= remaining)) / 100 AS DECIMAL(5,2))))
               OVER (PARTITION BY id ORDER BY ord ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) new_centers
    FROM ranked
)
SELECT id, old_centers, new_centers FROM assembled WHERE ord = 1;

UPDATE nx_config_item c
JOIN h9_globe_shares m ON m.id = c.id
JOIN nx_config_item v ON v.config_key = IF(c.config_key = 'growth.public_stats.values', 'growth.public_stats.version',
                                          CONCAT(LEFT(c.config_key, CHAR_LENGTH(c.config_key)-4), 'v'))
                     AND v.is_deleted = 0
SET c.config_value = JSON_SET(c.config_value, '$.globeDisplay.centers', m.new_centers), c.updated_at = NOW(),
    v.config_value = CAST(v.config_value AS UNSIGNED) + 1, v.updated_at = NOW()
WHERE JSON_EXTRACT(c.config_value, '$.globeDisplay.centers') = m.old_centers;
COMMIT;
DROP TEMPORARY TABLE h9_globe_shares;
