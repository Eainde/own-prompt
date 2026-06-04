package com.ob.cln.kyc.si.chart;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Standalone reference stub — not compiled in this repo.
 * Deep-comparator for two W4Output objects (code-path vs LLM output).
 */
@Component
public class W4OutputComparator {

    public W4ComparisonResult compare(W4Output code, W4Output llm) {
        List<String> diffs = new ArrayList<>();

        // 1. Top-level scalar fields
        compareText("client_entity", code.getClientEntity(), llm.getClientEntity(), diffs);
        compareText("entity_type_category", code.getEntityTypeCategory(), llm.getEntityTypeCategory(), diffs);

        // 2. Ownership tree (recursive)
        compareTreeNode("ownership_tree", code.getOwnershipTree(), llm.getOwnershipTree(), diffs);

        // 3. Array sizes
        int codeCycles   = code.getCycles()    == null ? 0 : code.getCycles().size();
        int llmCycles    = llm.getCycles()     == null ? 0 : llm.getCycles().size();
        int codeConflicts = code.getConflicts() == null ? 0 : code.getConflicts().size();
        int llmConflicts  = llm.getConflicts()  == null ? 0 : llm.getConflicts().size();

        if (codeCycles != llmCycles) {
            diffs.add("cycles.size: " + codeCycles + " vs " + llmCycles);
        }
        if (codeConflicts != llmConflicts) {
            diffs.add("conflicts.size: " + codeConflicts + " vs " + llmConflicts);
        }

        if (diffs.isEmpty()) {
            return new W4ComparisonResult(W4ComparisonResult.Status.MATCH, null);
        }
        return new W4ComparisonResult(W4ComparisonResult.Status.MISMATCH, String.join("; ", diffs));
    }

    // -----------------------------------------------------------------------
    // Tree recursion
    // -----------------------------------------------------------------------

    private void compareTreeNode(String path, TreeNode code, TreeNode llm, List<String> diffs) {
        if (code == null && llm == null) return;
        if (code == null || llm == null) {
            diffs.add(path + ": one side is null");
            return;
        }

        compareText(path + ".id",    code.getId(),   llm.getId(),   diffs);
        compareText(path + ".name",  code.getName(), llm.getName(), diffs);
        compareText(path + ".type",  code.getType(), llm.getType(), diffs);

        compareScalar(path + ".layer", code.getLayer(), llm.getLayer(), diffs);

        comparePercentage(path + ".ownership_percentage_direct",
                code.getOwnershipPercentageDirect(), llm.getOwnershipPercentageDirect(), diffs);

        compareText(path + ".control", code.getControl(), llm.getControl(), diffs);
        compareText(path + ".source",  code.getSource(),  llm.getSource(),  diffs);

        compareNormalizedText(path + ".data_gaps",   code.getDataGaps(),   llm.getDataGaps(),   diffs);
        compareNormalizedText(path + ".exceptions",  code.getExceptions(), llm.getExceptions(), diffs);

        // Children — sort by id before comparing
        List<TreeNode> codeChildren = sortedChildren(code.getChildren());
        List<TreeNode> llmChildren  = sortedChildren(llm.getChildren());

        int maxLen = Math.max(codeChildren.size(), llmChildren.size());
        for (int i = 0; i < maxLen; i++) {
            TreeNode codeChild = i < codeChildren.size() ? codeChildren.get(i) : null;
            TreeNode llmChild  = i < llmChildren.size()  ? llmChildren.get(i)  : null;
            compareTreeNode(path + ".children[" + i + "]", codeChild, llmChild, diffs);
        }
    }

    // -----------------------------------------------------------------------
    // Field-level comparators
    // -----------------------------------------------------------------------

    /** Plain text: null == "" equivalence. */
    private void compareText(String path, String code, String llm, List<String> diffs) {
        String a = normalizeNull(code);
        String b = normalizeNull(llm);
        if (!a.equals(b)) {
            diffs.add(path + ": \"" + code + "\" vs \"" + llm + "\"");
        }
    }

    /** Trim + lowercase for free-text fields. */
    private void compareNormalizedText(String path, String code, String llm, List<String> diffs) {
        String a = normalizeNull(code).trim().toLowerCase();
        String b = normalizeNull(llm).trim().toLowerCase();
        if (!a.equals(b)) {
            diffs.add(path + ": \"" + code + "\" vs \"" + llm + "\"");
        }
    }

    /**
     * Percentage: strip %, parse as double if possible; otherwise lowercase compare.
     * "25%" == "25.0" == "25"
     */
    private void comparePercentage(String path, String code, String llm, List<String> diffs) {
        String rawA = normalizeNull(code);
        String rawB = normalizeNull(llm);

        Double numA = parsePercentage(rawA);
        Double numB = parsePercentage(rawB);

        boolean match;
        if (numA != null && numB != null) {
            match = Double.compare(numA, numB) == 0;
        } else {
            // fall back to lowercase string compare
            match = rawA.toLowerCase().equals(rawB.toLowerCase());
        }

        if (!match) {
            diffs.add(path + ": \"" + code + "\" vs \"" + llm + "\"");
        }
    }

    /** Generic scalar (Integer / Object). */
    private void compareScalar(String path, Object code, Object llm, List<String> diffs) {
        if (!Objects.equals(code, llm)) {
            diffs.add(path + ": " + code + " vs " + llm);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String normalizeNull(String s) {
        return s == null ? "" : s;
    }

    /** Returns null if string is not numeric after stripping %. */
    private Double parsePercentage(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String stripped = raw.trim().replace("%", "").trim();
        try {
            return Double.parseDouble(stripped);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<TreeNode> sortedChildren(List<TreeNode> children) {
        if (children == null) return List.of();
        List<TreeNode> copy = new ArrayList<>(children);
        copy.sort(Comparator.comparing(n -> normalizeNull(n.getId())));
        return copy;
    }
}
