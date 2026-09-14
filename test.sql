SELECT TRUNC(e.started_at, 'MI')          AS minute_bucket,
       SUM(NVL(e.total_tokens, 0))        AS total_tokens,
       SUM(NVL(e.input_tokens, 0))        AS input_tokens,
       SUM(NVL(e.output_tokens, 0))       AS output_tokens,
       SUM(NVL(e.llm_call_count, 0))      AS llm_calls,
       COUNT(*)                           AS executions,
       COUNT(DISTINCT e.run_id)           AS runs
FROM   kyc_data_owner.nexus_ai_agent_executions e
WHERE  e.started_at >= SYSDATE - 90
GROUP  BY TRUNC(e.started_at, 'MI')
ORDER  BY total_tokens DESC
FETCH FIRST 100 ROWS ONLY;

2. Top 100 minutes by LLM calls (last 90 days)

SELECT TRUNC(e.started_at, 'MI')          AS minute_bucket,
       SUM(NVL(e.llm_call_count, 0))      AS llm_calls,
       SUM(NVL(e.total_tokens, 0))        AS total_tokens,
       COUNT(*)                           AS executions,
       COUNT(DISTINCT e.run_id)           AS runs
FROM   kyc_data_owner.nexus_ai_agent_executions e
WHERE  e.started_at >= SYSDATE - 90
GROUP  BY TRUNC(e.started_at, 'MI')
ORDER  BY llm_calls DESC
FETCH FIRST 100 ROWS ONLY;

3. Optional: rolling 60-second window
Queries 1 and 2 use fixed clock minutes, so a burst from 10:00:40 to 10:01:20 gets split across two rows. Provider rate limits usually count over a rolling window, and this query catches that peak:

SELECT started_at AS window_end, tokens_last_60s, calls_last_60s
FROM (
  SELECT e.started_at,
         SUM(NVL(e.total_tokens, 0))   OVER (ORDER BY e.started_at
              RANGE BETWEEN INTERVAL '59.999999' SECOND PRECEDING AND CURRENT ROW) AS tokens_last_60s,
         SUM(NVL(e.llm_call_count, 0)) OVER (ORDER BY e.started_at
              RANGE BETWEEN INTERVAL '59.999999' SECOND PRECEDING AND CURRENT ROW) AS calls_last_60s
  FROM   kyc_data_owner.nexus_ai_agent_executions e
  WHERE  e.started_at >= SYSDATE - 90
)
ORDER BY tokens_last_60s DESC     -- swap to calls_last_60s for the call peak
FETCH FIRST 100 ROWS ONLY;