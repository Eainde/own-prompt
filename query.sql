WITH bounds AS (
  SELECT TO_DATE('2026-08-01','YYYY-MM-DD')          AS from_dt,
         TO_DATE('2026-09-21','YYYY-MM-DD') + 1      AS to_dt   -- end date is exclusive
  FROM   dual
)
SELECT e.agent_name,
       COUNT(*)                                          AS executions,
       COUNT(DISTINCT e.run_id)                          AS runs,
       -- totals
       SUM(NVL(e.input_tokens,0))                        AS total_input_tokens,
       SUM(NVL(e.output_tokens,0))                       AS total_output_tokens,
       SUM(NVL(e.total_tokens,0))                        AS total_tokens,
       SUM(NVL(e.cached_tokens,0))                       AS total_cached_tokens,
       SUM(NVL(e.thought_tokens,0))                      AS total_thought_tokens,
       -- average per execution
       ROUND(AVG(NVL(e.input_tokens,0)))                 AS avg_input_per_exec,
       ROUND(AVG(NVL(e.output_tokens,0)))                AS avg_output_per_exec,
       ROUND(AVG(NVL(e.total_tokens,0)))                 AS avg_tokens_per_exec,
       ROUND(AVG(NVL(e.cached_tokens,0)))                AS avg_cached_per_exec,
       ROUND(AVG(NVL(e.thought_tokens,0)))               AS avg_thought_per_exec,
       -- average per run
       ROUND(COUNT(*)                        / NULLIF(COUNT(DISTINCT e.run_id),0), 2) AS agent_execs_per_run,
       ROUND(SUM(NVL(e.input_tokens,0))      / NULLIF(COUNT(DISTINCT e.run_id),0))    AS avg_input_per_run,
       ROUND(SUM(NVL(e.output_tokens,0))     / NULLIF(COUNT(DISTINCT e.run_id),0))    AS avg_output_per_run,
       ROUND(SUM(NVL(e.total_tokens,0))      / NULLIF(COUNT(DISTINCT e.run_id),0))    AS avg_tokens_per_run,
       ROUND(SUM(NVL(e.cached_tokens,0))     / NULLIF(COUNT(DISTINCT e.run_id),0))    AS avg_cached_per_run,
       ROUND(SUM(NVL(e.thought_tokens,0))    / NULLIF(COUNT(DISTINCT e.run_id),0))    AS avg_thought_per_run,
       LISTAGG(DISTINCT e.model, ', ') WITHIN GROUP (ORDER BY e.model)                AS model
FROM   kyc_data_owner.nexus_ai_agent_executions e
CROSS  JOIN bounds b
WHERE  e.completed_at >= b.from_dt
AND    e.completed_at <  b.to_dt
GROUP  BY e.agent_name
ORDER  BY total_tokens DESC;

Notes
- Change the two dates in bounds. The end date is exclusive, so + 1 includes all of 21-Sep.
- For one agent, add AND e.agent_name = 'CLASSIFIER_V2' to the WHERE.
- For a grand total row, change GROUP BY e.agent_name to GROUP BY ROLLUP(e.agent_name). The row with a null agent name is the total.
- Column names are guesses for cached_tokens, thought_tokens, model, run_id and agent_name. Check with:
SELECT column_name, data_type FROM all_tab_columns
WHERE owner='KYC_DATA_OWNER' AND table_name='NEXUS_AI_AGENT_EXECUTIONS' ORDER BY column_id;
- If LISTAGG(DISTINCT ...) errors on an older Oracle version, swap that line for MAX(e.model) AS model, or COUNT(DISTINCT e.model) AS model_count.
- runs counts distinct run_id values for that agent. An agent that doesn't run in every case will show a different run count than other agents, which is what makes agent_execs_per_run vary in your slide (15.89 vs 1.00).