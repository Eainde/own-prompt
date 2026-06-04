package com.ob.cln.kyc.si.chart;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Standalone reference stub — not compiled in this repo.
 *
 * Dual-run orchestrator for Wave 4. Runs the LLM agent and the code-only builder
 * concurrently, picks the primary result based on a feature flag, and compares
 * outputs asynchronously so comparison never blocks the pipeline.
 */
@Slf4j
@Service
public class W4DualRunService {

    // TODO: adapt W4LlmAgent to the actual LLM agent interface/class name used in the project
    private final W4LlmAgent llmAgent;
    private final OrganisationChartBuilder codeBuilder;
    private final W4OutputComparator comparator;
    private final String primaryStrategy;

    private static final long CODE_TIMEOUT_SECONDS = 30;
    private static final long LLM_TIMEOUT_SECONDS  = 120;

    public W4DualRunService(
            W4LlmAgent llmAgent,
            OrganisationChartBuilder codeBuilder,
            W4OutputComparator comparator,
            @Value("${w4.primary-strategy:llm}") String primaryStrategy) {
        this.llmAgent        = llmAgent;
        this.codeBuilder     = codeBuilder;
        this.comparator      = comparator;
        this.primaryStrategy = primaryStrategy;
    }

    /**
     * Runs both W4 strategies concurrently, picks the primary based on
     * {@code w4.primary-strategy} ("llm" or "code"), and triggers an async
     * comparison for observability.
     *
     * @param w3               the W3 deduplication output
     * @param clientEntityName the client entity name
     * @return the W4Output from the primary strategy, falling back to the shadow
     *         if the primary fails; throws if both fail
     */
    public W4Output execute(W3Output w3, String clientEntityName) {

        // --- 1. Run both paths concurrently ---
        CompletableFuture<W4Output> codeFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return codeBuilder.build(w3, clientEntityName);
            } catch (Exception e) {
                log.error("W4 code-path failed [client={}]: {}", clientEntityName, e.getMessage(), e);
                return null;
            }
        });

        CompletableFuture<W4Output> llmFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return llmAgent.run(w3, clientEntityName);
            } catch (Exception e) {
                log.error("W4 LLM-path failed [client={}]: {}", clientEntityName, e.getMessage(), e);
                return null;
            }
        });

        // --- 2. Wait with per-path timeouts ---
        W4Output codeResult = getQuietly(codeFuture, CODE_TIMEOUT_SECONDS, "code", clientEntityName);
        W4Output llmResult  = getQuietly(llmFuture,  LLM_TIMEOUT_SECONDS,  "llm",  clientEntityName);

        // --- 3. Async comparison (never blocks pipeline) ---
        final W4Output codeSnap = codeResult;
        final W4Output llmSnap  = llmResult;
        CompletableFuture.runAsync(() -> logComparison(clientEntityName, codeSnap, llmSnap));

        // --- 4. Pick primary ---
        boolean useLlm = "llm".equalsIgnoreCase(primaryStrategy);
        W4Output primary = useLlm ? llmResult  : codeResult;
        W4Output shadow  = useLlm ? codeResult : llmResult;
        String primaryName = useLlm ? "llm" : "code";
        String shadowName  = useLlm ? "code" : "llm";

        if (primary != null) {
            return primary;
        }

        if (shadow != null) {
            log.warn("W4 primary strategy '{}' returned null [client={}]; falling back to '{}' result",
                    primaryName, clientEntityName, shadowName);
            return shadow;
        }

        throw new RuntimeException("Both W4 strategies failed for client: " + clientEntityName);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Gets the future result within the given timeout; returns null on any failure. */
    private W4Output getQuietly(CompletableFuture<W4Output> future, long timeoutSeconds,
                                String label, String clientEntityName) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("W4 {}-path timed out after {}s [client={}]", label, timeoutSeconds, clientEntityName);
            future.cancel(true);
        } catch (Exception e) {
            log.error("W4 {}-path threw during get [client={}]: {}", label, clientEntityName, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Compares code and LLM outputs and logs the result.
     * Wrapped in try-catch so comparison failures never crash anything.
     *
     * Log format: "W4 dual-run [client={}]: status={} detail={}"
     */
    private void logComparison(String clientEntityName, W4Output codeResult, W4Output llmResult) {
        try {
            if (codeResult == null && llmResult == null) {
                log.warn("W4 dual-run [client={}]: status={} detail={}",
                        clientEntityName, W4ComparisonResult.Status.MISMATCH, "Both failed");
                return;
            }
            if (codeResult == null) {
                log.warn("W4 dual-run [client={}]: status={} detail={}",
                        clientEntityName, W4ComparisonResult.Status.CODE_FAILED, "Code path returned null");
                return;
            }
            if (llmResult == null) {
                log.warn("W4 dual-run [client={}]: status={} detail={}",
                        clientEntityName, W4ComparisonResult.Status.LLM_FAILED, "LLM path returned null");
                return;
            }
            W4ComparisonResult result = comparator.compare(codeResult, llmResult);
            log.info("W4 dual-run [client={}]: status={} detail={}",
                    clientEntityName, result.status(), result.detail());
        } catch (Exception e) {
            log.error("W4 dual-run comparison threw unexpectedly [client={}]: {}", clientEntityName, e.getMessage(), e);
        }
    }
}
