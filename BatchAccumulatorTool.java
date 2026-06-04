package com.ob.cln.kyc.si.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class BatchAccumulatorTool {

    private static final java.util.regex.Pattern NUMERIC_ID_PATTERN = java.util.regex.Pattern.compile("^(\\d+|id-\\d+)$");

    private final ObjectMapper objectMapper;
    private final ThreadLocal<Integer> batchCount = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Integer> totalRecordCount = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<List<String>> batches = ThreadLocal.withInitial(ArrayList::new);

    public BatchAccumulatorTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return !batches.get().isEmpty();
    }

    public int getBatchCount() {
        return batchCount.get();
    }

    public int getTotalRecordCount() {
        return totalRecordCount.get();
    }

    @Tool(name = "submitBatch", value =
        "You MUST use this tool when your output would contain more than the mentioned number of maximum results " +
        "in one of the primary fields. To submit that, use the definition of too big previously mentioned. " +
        "The tool will split the final result of the items in the primary field identified as having too many results " +
        "into smaller batches having the given batch size when it is considered to be too big. " +
        "Use the same JSON schema as your normal output. The returned output should always be a valid JSON. " +
        "Maintain sequential ID numbering across batches for the primary field identified as having too many results. " +
        "After the last batch, return ONLY a text summary. NOT JSON.")
    public String submitBatch(
            @P("JSON object containing the batch of records, same schema as normal output") String jsonBatch,
            @P("Primary field in the output JSON having too many results") String primaryField) {

        log.info("submit batch tool required - field {} has too many results", primaryField);

        int currentBatch = batchCount.get() + 1;
        JsonNode jsonNode = parseJson(jsonBatch);

        if (jsonNode != null) {
            batchCount.set(currentBatch);
            batches.get().add(jsonBatch);

            int batchRecords = countRecords(jsonNode, primaryField);
            int runningTotal = totalRecordCount.get() + batchRecords;
            totalRecordCount.set(runningTotal);

            log.info("batch {} received: {} records (running total: {})", currentBatch, batchRecords, runningTotal);

            return String.format("Batch %d received: %d records (running total: %d)" +
                "\n If there are more records to extract, continue calling submitBatch." +
                "\n Give the next batch. Records sequential IDs (next ID starts at %d)." +
                "\n Or if all records have been submitted, return ONLY a text summary. NOT JSON.",
                currentBatch, batchRecords, runningTotal, runningTotal + 1);
        } else {
            return String.format("Invalid JSON received. Fix your output and call submitBatch again for same batch %d",
                currentBatch + 1);
        }
    }

    /**
     * Called by BatchAccumulatorPost AFTER the agent completes.
     * Merges all accumulated batches into a single JSON result.
     * Splices the primary array field in each batch, concatenates all arrays,
     * and optionally renumbers IDs sequentially.
     */
    public String getMergedResult() {
        List<String> currentBatches = batches.get();

        if (currentBatches.isEmpty()) {
            return "{}";
        }

        if (currentBatches.size() == 1) {
            return currentBatches.get(0);
        }

        try {
            return mergeBatches(currentBatches);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to merge {} batches - concatenating raw JSON", currentBatches.size(), e);
            throw new RuntimeException("Failed to merge batches", e);
        }
    }

    private String mergeBatches(List<String> batchList) throws JsonProcessingException {
        validateBatchConsistency(batchList);

        ObjectNode mergedResult = (ObjectNode) objectMapper.readTree(batchList.get(0)).deepCopy();

        for (int i = 1; i < batchList.size(); i++) {
            ObjectNode currentBatch = (ObjectNode) objectMapper.readTree(batchList.get(i));

            currentBatch.fieldNames().forEachRemaining(key -> {
                JsonNode value = currentBatch.get(key);

                if (value.isArray()) {
                    if (mergedResult.has(key)) {
                        JsonNode existingNode = mergedResult.get(key);
                        if (existingNode != null && existingNode.isArray()) {
                            ((ArrayNode) existingNode).addAll((ArrayNode) value);
                        }
                    }
                } else {
                    mergedResult.set(key, value.deepCopy());
                }
            });
        }

        if (shouldRenumberIds(mergedResult)) {
            mergedResult.fieldNames().forEachRemaining(key -> {
                if (mergedResult.get(key).isArray()) {
                    renumberIds((ArrayNode) mergedResult.get(key));
                }
            });
        }

        log.info("Merged {} batches", batchList.size());
        return objectMapper.writeValueAsString(mergedResult);
    }

    private void validateBatchConsistency(List<String> batchList) throws JsonProcessingException {
        if (batchList.size() <= 1) {
            return;
        }

        Set<String> firstKeyNames = getArrayKeyNames(objectMapper.readTree(batchList.get(0)));

        for (int i = 1; i < batchList.size(); i++) {
            Set<String> currentKeyNames = getArrayKeyNames(objectMapper.readTree(batchList.get(i)));

            if (!firstKeyNames.equals(currentKeyNames)) {
                log.error("Inconsistent JSON array fields detected between batches. Array keys in batch 0 are {} and in batch {} are {}",
                    firstKeyNames, i, currentKeyNames);
                throw new IllegalArgumentException(
                    "Inconsistent JSON array fields detected between batches. Cannot merge.");
            }
        }
    }

    private Set<String> getArrayKeyNames(JsonNode batch) {
        Set<String> arrayKeys = new HashSet<>();
        batch.fieldNames().forEachRemaining(key -> {
            if (batch.get(key).isArray()) {
                arrayKeys.add(key);
            }
        });
        return arrayKeys;
    }

    private boolean shouldRenumberIds(ObjectNode mergedResult) {
        java.util.Iterator<String> fields = mergedResult.fieldNames();
        while (fields.hasNext()) {
            JsonNode field = mergedResult.get(fields.next());
            if (field.isArray() && field.size() > 0) {
                JsonNode first = field.get(0);
                if (first.isObject() && first.has("id")) {
                    String id = first.get("id").asText();
                    if (!NUMERIC_ID_PATTERN.matcher(id).matches()) {
                        log.debug("Slug-based IDs detected (e.g. '{}'), skipping renumbering", id);
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void renumberIds(ArrayNode records) {
        for (int i = 0; i < records.size(); i++) {
            JsonNode record = records.get(i);
            if (record.isObject() && record.has("id")) {
                ((ObjectNode) record).put("id", "id-" + (i + 1));
            }
        }
    }

    private int countRecords(JsonNode jsonNode, String primaryField) {
        if (!jsonNode.isObject()) {
            throw new IllegalArgumentException("Batching not supported on array root nodes");
        }
        return jsonNode.get(primaryField).size();
    }

    private JsonNode parseJson(String jsonBatch) {
        try {
            return objectMapper.readTree(jsonBatch.replaceAll("^```json\\s*", "").replaceAll("\\s*```$", ""));
        } catch (Exception e) {
            log.error("Failed to parse JSON from batch. {}", jsonBatch, e);
            return null;
        }
    }

    public void reset() {
        batches.get().clear();
        batchCount.set(0);
        totalRecordCount.set(0);
    }
}
