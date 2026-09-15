1. Headline: peak minute for tokens and for LLM calls

WITH per_minute AS (
  SELECT TRUNC(e.completed_at, 'MI')       AS minute_start,
         SUM(NVL(e.total_tokens, 0))       AS total_tokens,
         SUM(NVL(e.input_tokens, 0))       AS input_tokens,
         SUM(NVL(e.output_tokens, 0))      AS output_tokens,
         SUM(NVL(e.llm_call_count, 0))     AS llm_calls,
         COUNT(*)                          AS executions
  FROM   kyc_data_owner.nexus_ai_agent_executions e
  WHERE  e.started_at   >= SYSDATE - 91
  AND    e.completed_at >= SYSDATE - 90
  GROUP  BY TRUNC(e.completed_at, 'MI')
),
ranked AS (
  SELECT p.*,
         ROW_NUMBER() OVER (ORDER BY total_tokens DESC, minute_start) AS rn_tokens,
         ROW_NUMBER() OVER (ORDER BY llm_calls    DESC, minute_start) AS rn_calls
  FROM   per_minute p
)
SELECT 'Max tokens in one minute'    AS metric,
       total_tokens                  AS peak_value,
       TO_CHAR(minute_start, 'YYYY-MM-DD HH24:MI:SS')                       AS window_start,
       TO_CHAR(minute_start + INTERVAL '59' SECOND, 'YYYY-MM-DD HH24:MI:SS') AS window_end,
       total_tokens, input_tokens, output_tokens, llm_calls, executions
FROM   ranked WHERE rn_tokens = 1
UNION ALL
SELECT 'Max LLM calls in one minute',
       llm_calls,
       TO_CHAR(minute_start, 'YYYY-MM-DD HH24:MI:SS'),
       TO_CHAR(minute_start + INTERVAL '59' SECOND, 'YYYY-MM-DD HH24:MI:SS'),
       total_tokens, input_tokens, output_tokens, llm_calls, executions
FROM   ranked WHERE rn_calls = 1;

2. Distribution: what normal load looks like

WITH per_minute AS (
  SELECT TRUNC(e.completed_at, 'MI')   AS minute_start,
         SUM(NVL(e.total_tokens, 0))   AS total_tokens,
         SUM(NVL(e.llm_call_count, 0)) AS llm_calls,
         SUM(CASE WHEN e.llm_call_count IS NULL THEN 1 ELSE 0 END) AS rows_missing_call_count
  FROM   kyc_data_owner.nexus_ai_agent_executions e
  WHERE  e.started_at   >= SYSDATE - 91
  AND    e.completed_at >= SYSDATE - 90
  GROUP  BY TRUNC(e.completed_at, 'MI')
)
SELECT TO_CHAR(MIN(minute_start), 'YYYY-MM-DD HH24:MI') AS first_minute,
       TO_CHAR(MAX(minute_start), 'YYYY-MM-DD HH24:MI') AS last_minute,
       COUNT(*)                                                             AS active_minutes,
       ROUND(AVG(total_tokens))                                             AS avg_tokens_per_min,
       ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY total_tokens))    AS p95_tokens_per_min,
       ROUND(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY total_tokens))    AS p99_tokens_per_min,
       MAX(total_tokens)                                                    AS max_tokens_per_min,
       ROUND(AVG(llm_calls))                                                AS avg_calls_per_min,
       ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY llm_calls))       AS p95_calls_per_min,
       ROUND(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY llm_calls))       AS p99_calls_per_min,
       MAX(llm_calls)                                                       AS max_calls_per_min,
       SUM(rows_missing_call_count)                                         AS rows_missing_call_count
FROM   per_minute;

If rows_missing_call_count is above 0, some rows have no call count, so the call numbers are too low. Check that before sending the report.

3. Top 100 detail (tokens)

SELECT TO_CHAR(TRUNC(e.completed_at, 'MI'), 'YYYY-MM-DD HH24:MI:SS')                        AS window_start,
       TO_CHAR(TRUNC(e.completed_at, 'MI') + INTERVAL '59' SECOND, 'YYYY-MM-DD HH24:MI:SS') AS window_end,
       SUM(NVL(e.total_tokens, 0))    AS total_tokens,
       SUM(NVL(e.input_tokens, 0))    AS input_tokens,
       SUM(NVL(e.output_tokens, 0))   AS output_tokens,
       SUM(NVL(e.llm_call_count, 0))  AS llm_calls,
       COUNT(*)                       AS executions,
       COUNT(DISTINCT e.run_id)       AS runs
FROM   kyc_data_owner.nexus_ai_agent_executions e
WHERE  e.started_at   >= SYSDATE - 91
AND    e.completed_at >= SYSDATE - 90
GROUP  BY TRUNC(e.completed_at, 'MI')
ORDER  BY total_tokens DESC
FETCH FIRST 100 ROWS ONLY;

4. Rolling 60-second peak. This matters most, because if it comes out above 20M the 1.5x isn't enough:
WITH r AS (
  SELECT SUM(NVL(total_tokens,0))   OVER (ORDER BY completed_at RANGE BETWEEN INTERVAL '59' SECOND PRECEDING AND CURRENT ROW) tok_60s,
         SUM(NVL(llm_call_count,0)) OVER (ORDER BY completed_at RANGE BETWEEN INTERVAL '59' SECOND PRECEDING AND CURRENT ROW) calls_60s
  FROM kyc_data_owner.nexus_ai_agent_executions
  WHERE completed_at >= SYSDATE - 90)
SELECT MAX(tok_60s), MAX(calls_60s) FROM r;
Final ask = rolling max × 1.5.

5. Growth trend: peak minute per week. This shows whether load is rising, flat or falling:
WITH pm AS (
  SELECT TRUNC(completed_at,'MI')    m,
         SUM(NVL(total_tokens,0))    tok,
         SUM(NVL(llm_call_count,0))  calls
  FROM   kyc_data_owner.nexus_ai_agent_executions
  WHERE  completed_at >= SYSDATE - 90
  GROUP  BY TRUNC(completed_at,'MI'))
SELECT TRUNC(m,'IW')                                                      week,
       MAX(tok)                                                           max_tok,
       TO_CHAR(MIN(m) KEEP (DENSE_RANK LAST ORDER BY tok),  'YYYY-MM-DD HH24:MI') max_tok_minute,
       MAX(calls)                                                         max_calls,
       TO_CHAR(MIN(m) KEEP (DENSE_RANK LAST ORDER BY calls),'YYYY-MM-DD HH24:MI') max_calls_minute,
       ROUND(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY tok))           p99_tok
FROM   pm
GROUP  BY TRUNC(m,'IW')
ORDER  BY week;

6. Daily peak per minutes
WITH pm AS (
  SELECT TRUNC(completed_at,'MI')    m,
         SUM(NVL(total_tokens,0))    tok,
         SUM(NVL(llm_call_count,0))  calls
  FROM   kyc_data_owner.nexus_ai_agent_executions
  WHERE  completed_at >= SYSDATE - 90
  GROUP  BY TRUNC(completed_at,'MI'))
SELECT TO_CHAR(TRUNC(m),'YYYY-MM-DD DY')                                  day,
       COUNT(*)                                                           active_minutes,
       MAX(tok)                                                           max_tok,
       TO_CHAR(MIN(m) KEEP (DENSE_RANK LAST ORDER BY tok),  'HH24:MI')    max_tok_minute,
       ROUND(PERCENTILE_CONT(0.90) WITHIN GROUP (ORDER BY tok))           p90_tok,
       ROUND(AVG(tok))                                                    avg_tok,
       MAX(calls)                                                         max_calls,
       TO_CHAR(MIN(m) KEEP (DENSE_RANK LAST ORDER BY calls),'HH24:MI')    max_calls_minute,
       ROUND(PERCENTILE_CONT(0.90) WITHIN GROUP (ORDER BY calls))         p90_calls,
       ROUND(AVG(calls))                                                  avg_calls
FROM   pm
GROUP  BY TRUNC(m)
ORDER  BY TRUNC(m);

7.
WITH pm AS (
  SELECT TRUNC(completed_at,'MI')    m,
         SUM(NVL(total_tokens,0))    tok,
         SUM(NVL(llm_call_count,0))  calls
  FROM   kyc_data_owner.nexus_ai_agent_executions
  WHERE  completed_at >= SYSDATE - 90
  GROUP  BY TRUNC(completed_at,'MI'))
SELECT TO_CHAR(TRUNC(m),'YYYY-MM-DD DY')                                day,
       COUNT(*)                                                         active_minutes,
       SUM(CASE WHEN calls > 200                    THEN 1 ELSE 0 END)  mins_over_200_calls,
       SUM(CASE WHEN tok   > 3000000                THEN 1 ELSE 0 END)  mins_over_3m_tok,
       SUM(CASE WHEN calls > 200 AND tok > 3000000  THEN 1 ELSE 0 END)  mins_over_both,
       SUM(CASE WHEN calls > 200 OR  tok > 3000000  THEN 1 ELSE 0 END)  mins_over_either,
       MAX(calls)                                                       max_calls,
       MAX(tok)                                                         max_tok
FROM   pm
GROUP  BY TRUNC(m)
ORDER  BY TRUNC(m);

8.

WITH pm AS (
  SELECT TRUNC(completed_at,'MI')    m,
         SUM(NVL(input_tokens,0))    in_tok,
         SUM(NVL(output_tokens,0))   out_tok,
         SUM(NVL(total_tokens,0))    tok,
         SUM(NVL(llm_call_count,0))  calls
  FROM   kyc_data_owner.nexus_ai_agent_executions
  WHERE  completed_at >= SYSDATE - 90
  GROUP  BY TRUNC(completed_at,'MI'))
SELECT 'input' metric,
       ROUND(PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY in_tok)) p50,
       ROUND(PERCENTILE_CONT(0.90) WITHIN GROUP (ORDER BY in_tok)) p90,
       ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY in_tok)) p95,
       ROUND(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY in_tok)) p99,
       MAX(in_tok) max_val FROM pm
UNION ALL
SELECT 'output',
       ROUND(PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY out_tok)),
       ROUND(PERCENTILE_CONT(0.90) WITHIN GROUP (ORDER BY out_tok)),
       ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY out_tok)),
       ROUND(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY out_tok)),
       MAX(out_tok) FROM pm
UNION ALL
SELECT 'total',
       ROUND(PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY tok)),
       ROUND(PERCENTILE_CONT(0.90) WITHIN GROUP (ORDER BY tok)),
       ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY tok)),
       ROUND(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY tok)),
       MAX(tok) FROM pm
UNION ALL
SELECT 'calls',
       ROUND(PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY calls)),
       ROUND(PERCENTILE_CONT(0.90) WITHIN GROUP (ORDER BY calls)),
       ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY calls)),
       ROUND(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY calls)),
       MAX(calls) FROM pm;

2. Per-minute export for the delay simulation. Save it as CSV in Downloads (about 47K rows):
SELECT TO_CHAR(TRUNC(completed_at,'MI'),'YYYY-MM-DD HH24:MI') minute,
       SUM(NVL(input_tokens,0))   in_tok,
       SUM(NVL(output_tokens,0))  out_tok,
       SUM(NVL(total_tokens,0))   tok,
       SUM(NVL(llm_call_count,0)) calls
FROM   kyc_data_owner.nexus_ai_agent_executions
WHERE  completed_at >= SYSDATE - 90
GROUP  BY TRUNC(completed_at,'MI')
ORDER  BY 1;

--------------
WITH params AS (SELECT 1 AS out_w FROM dual),      -- output token weight; set to model's PT rate (e.g. 4)
caps AS (
  SELECT 3000000 cap FROM dual UNION ALL
  SELECT 4000000     FROM dual UNION ALL
  SELECT 5000000     FROM dual UNION ALL
  SELECT 6000000     FROM dual UNION ALL
  SELECT 8000000     FROM dual),
bounds AS (SELECT TRUNC(SYSDATE-90,'MI') start_m, TRUNC(SYSDATE,'MI') end_m FROM dual),
minutes AS (
  SELECT start_m + (LEVEL-1)/1440 m
  FROM   bounds
  CONNECT BY LEVEL <= (end_m - start_m)*1440 + 1),
pm AS (
  SELECT TRUNC(e.completed_at,'MI') m,
         SUM(NVL(e.total_tokens,0) + (p.out_w-1)*NVL(e.output_tokens,0)) tok
  FROM   kyc_data_owner.nexus_ai_agent_executions e CROSS JOIN params p
  WHERE  e.completed_at >= TRUNC(SYSDATE-90,'MI')
  GROUP  BY TRUNC(e.completed_at,'MI')),
dense AS (
  SELECT mi.m, NVL(pm.tok,0) tok
  FROM   minutes mi LEFT JOIN pm ON pm.m = mi.m),
sim AS (
  SELECT c.cap, d.m,
         SUM(d.tok - c.cap) OVER (PARTITION BY c.cap ORDER BY d.m ROWS UNBOUNDED PRECEDING) s
  FROM   dense d CROSS JOIN caps c),
bl AS (
  SELECT cap, m,
         s - LEAST(0, MIN(s) OVER (PARTITION BY cap ORDER BY m ROWS UNBOUNDED PRECEDING)) backlog
  FROM   sim),
islands AS (
  SELECT cap, m, backlog,
         ROW_NUMBER() OVER (PARTITION BY cap ORDER BY m)
       - ROW_NUMBER() OVER (PARTITION BY cap, SIGN(backlog) ORDER BY m) grp
  FROM   bl),
episodes AS (
  SELECT cap, grp, COUNT(*) mins, MIN(m) st
  FROM   islands WHERE backlog > 0
  GROUP  BY cap, grp),
agg AS (
  SELECT cap,
         SUM(CASE WHEN backlog > 0 THEN 1 ELSE 0 END)          mins_behind,
         COUNT(DISTINCT CASE WHEN backlog > 0 THEN TRUNC(m) END) days_affected,
         MAX(backlog)                                           max_backlog_tok,
         ROUND(MAX(backlog)/cap,1)                              max_wait_min,
         MIN(m) KEEP (DENSE_RANK LAST ORDER BY backlog)         worst_at
  FROM   bl GROUP BY cap),
ep AS (
  SELECT cap, COUNT(*) episodes, MAX(mins) longest_episode_min,
         MIN(st) KEEP (DENSE_RANK LAST ORDER BY mins) longest_episode_start
  FROM   episodes GROUP BY cap)
SELECT a.cap,
       a.mins_behind,
       e.episodes,
       a.days_affected,
       a.max_backlog_tok,
       a.max_wait_min,
       TO_CHAR(a.worst_at,'YYYY-MM-DD HH24:MI')              worst_at,
       e.longest_episode_min,
       TO_CHAR(e.longest_episode_start,'YYYY-MM-DD HH24:MI') longest_episode_start
FROM   agg a LEFT JOIN ep e ON e.cap = a.cap
ORDER  BY a.cap;

1.1 % for output tokens
WITH per_minute AS (
  SELECT TRUNC(e.completed_at, 'MI')    AS minute_start,
         SUM(NVL(e.output_tokens, 0))   AS output_tokens,
         SUM(CASE WHEN e.output_tokens IS NULL THEN 1 ELSE 0 END) AS rows_missing_output_tokens
  FROM   kyc_data_owner.nexus_ai_agent_executions e
  WHERE  e.started_at   >= SYSDATE - 91
  AND    e.completed_at >= SYSDATE - 90
  GROUP  BY TRUNC(e.completed_at, 'MI')
)
SELECT TO_CHAR(MIN(minute_start), 'YYYY-MM-DD HH24:MI')                     AS first_minute,
       TO_CHAR(MAX(minute_start), 'YYYY-MM-DD HH24:MI')                     AS last_minute,
       COUNT(*)                                                             AS active_minutes,
       ROUND(AVG(output_tokens))                                            AS avg_output_tokens_per_min,
       ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY output_tokens))   AS p95_output_tokens_per_min,
       ROUND(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY output_tokens))   AS p99_output_tokens_per_min,
       MAX(output_tokens)                                                   AS max_output_tokens_per_min,
       SUM(rows_missing_output_tokens)                                      AS rows_missing_output_tokens
FROM   per_minute;