package com.ob.cln.kyc.si.chart;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts a W3 flat ownership graph into a W4 nested ownership tree.
 *
 * Reference stub — not compiled in this repo. Mirrors pattern of BatchAccumulatorTool.java.
 *
 * Rules implemented:
 *   W4.R1 — root finding (layer=0 node; fallback to layer-1 node with most incoming edges)
 *   W4.R2 — recursive tree building; multi-parent nodes duplicated naturally
 *   W4.R3 — cycle detection via ancestryPath; cycle-broken nodes get children=[], exceptions set
 *   W4.R4 — orphan handling; orphaned nodes attached to root with data_gaps message
 *   W4.R5 — cycles and conflicts passed through unchanged from W3
 */
@Slf4j
@Service
public class OrganisationChartBuilder {

    private static final String SCHEMA_VERSION = "v0";
    private static final String SCHEMA_NOTE =
            "Schema subject to change pending internal Wave 5 system spec.";
    private static final String ORPHAN_DATA_GAPS =
            "Orphaned node: no incoming edges. Attached to root.";

    /**
     * Builds a W4 output from a W3 flat graph.
     *
     * @param w3               the W3 merge + comparison output
     * @param clientEntityName the client entity name (used for metadata only; root is identified by layer=0)
     * @return W4Output containing a nested ownership_tree, plus passthrough cycles and conflicts
     */
    public W4Output build(W3Output w3, String clientEntityName) {
        W4Output output = new W4Output();
        output.setClientEntity(clientEntityName);
        output.setEntityTypeCategory(w3.getEntityTypeCategory());
        output.setMetadata(new W4Metadata(SCHEMA_VERSION, SCHEMA_NOTE));
        output.setCycles(w3.getCycles() != null ? w3.getCycles() : Collections.emptyList());
        output.setConflicts(w3.getConflicts() != null ? w3.getConflicts() : Collections.emptyList());

        List<W3Node> nodes = w3.getNodes();
        List<W3Edge> edges = w3.getEdges();

        // --- empty graph guard ---
        if (nodes == null || nodes.isEmpty()) {
            log.warn("W3 output has no nodes; returning minimal valid W4 output");
            output.setOwnershipTree(null);
            return output;
        }

        // --- pre-index ---
        Map<String, W3Node> nodeById = indexNodesById(nodes);
        Map<String, List<W3Edge>> edgesByOwned = indexEdgesByOwned(edges, nodeById);
        Set<String> nodesWithIncomingEdges = edgesByOwned.keySet();

        // --- W4.R1: find root ---
        String rootId = findRootId(nodes, edgesByOwned);
        if (rootId == null) {
            log.warn("Could not determine root node; returning minimal valid W4 output");
            output.setOwnershipTree(null);
            return output;
        }

        // --- W4.R4: identify orphans (excluding root itself) ---
        Set<String> orphanIds = nodes.stream()
                .map(W3Node::getId)
                .filter(id -> !id.equals(rootId))
                .filter(id -> !nodesWithIncomingEdges.contains(id))
                .collect(Collectors.toSet());

        if (!orphanIds.isEmpty()) {
            log.info("Orphaned nodes detected ({}); attaching to root: {}", orphanIds.size(), orphanIds);
        }

        // --- W4.R2: build tree recursively from root ---
        LinkedHashSet<String> ancestryPath = new LinkedHashSet<>();
        TreeNode root = buildNode(rootId, null, ancestryPath, nodeById, edgesByOwned);

        // --- W4.R4: attach orphans to root ---
        for (String orphanId : orphanIds) {
            if (!nodeById.containsKey(orphanId)) {
                log.warn("Orphan id '{}' not found in nodeById index; skipping", orphanId);
                continue;
            }
            LinkedHashSet<String> orphanAncestry = new LinkedHashSet<>();
            orphanAncestry.add(rootId);
            TreeNode orphanNode = buildNode(orphanId, null, orphanAncestry, nodeById, edgesByOwned);
            // mark orphan provenance in data_gaps
            String existingGaps = orphanNode.getDataGaps();
            orphanNode.setDataGaps(existingGaps != null && !existingGaps.isBlank()
                    ? existingGaps + " | " + ORPHAN_DATA_GAPS
                    : ORPHAN_DATA_GAPS);
            orphanNode.setOwnershipPercentageDirect(null);
            orphanNode.setControl(null);
            orphanNode.setSource(null);
            if (root.getChildren() == null) {
                root.setChildren(new ArrayList<>());
            }
            root.getChildren().add(orphanNode);
        }

        output.setOwnershipTree(root);
        return output;
    }

    // ---------------------------------------------------------------------------
    // Tree construction
    // ---------------------------------------------------------------------------

    /**
     * Recursively builds a TreeNode.
     *
     * @param nodeId       id of the node to build
     * @param parentEdge   the edge leading into this node (null for root/orphan attachment)
     * @param ancestryPath nodes visited on the current path from the root (not including nodeId yet)
     * @param nodeById     pre-built id → W3Node index
     * @param edgesByOwned pre-built owned-id → incoming edges index
     * @return populated TreeNode
     */
    private TreeNode buildNode(
            String nodeId,
            W3Edge parentEdge,
            LinkedHashSet<String> ancestryPath,
            Map<String, W3Node> nodeById,
            Map<String, List<W3Edge>> edgesByOwned) {

        W3Node w3Node = nodeById.get(nodeId);
        TreeNode node = new TreeNode();

        // copy identity fields from W3 node
        node.setId(w3Node.getId());
        node.setName(w3Node.getName());
        node.setType(w3Node.getType());
        node.setLayer(w3Node.getLayer());
        node.setListingProof(w3Node.getListingProof());
        node.setDataGaps(w3Node.getDataGaps());
        node.setExceptions(w3Node.getExceptions());

        // relationship fields from incoming edge (null for root and orphan attachment points)
        if (parentEdge != null) {
            node.setOwnershipPercentageDirect(parentEdge.getOwnershipPercentageDirect());
            node.setControl(parentEdge.getControl());
            node.setSource(parentEdge.getSource());
        } else {
            node.setOwnershipPercentageDirect(null);
            node.setControl(null);
            node.setSource(null);
        }

        // --- W4.R3: cycle detection ---
        if (ancestryPath.contains(nodeId)) {
            String cyclePath = buildCyclePathDescription(ancestryPath, nodeId);
            log.warn("Cycle detected at node '{}'; path: {}", nodeId, cyclePath);
            node.setChildren(Collections.emptyList());
            String existingExceptions = node.getExceptions();
            node.setExceptions(existingExceptions != null && !existingExceptions.isBlank()
                    ? existingExceptions + " | Cycle detected: " + cyclePath
                    : "Cycle detected: " + cyclePath);
            return node;
        }

        // add self to path for children traversal
        LinkedHashSet<String> childAncestry = new LinkedHashSet<>(ancestryPath);
        childAncestry.add(nodeId);

        // --- W4.R2: children are the OWNERS of this node ---
        // "For each node X, its children are all nodes Y where edge { owner: Y, owned: X } exists"
        List<W3Edge> childEdges = edgesByOwned.getOrDefault(nodeId, Collections.emptyList());

        if (childEdges.isEmpty()) {
            node.setChildren(Collections.emptyList());
        } else {
            List<TreeNode> children = new ArrayList<>();
            for (W3Edge edge : childEdges) {
                String childId = edge.getOwner();
                if (!nodeById.containsKey(childId)) {
                    log.warn("Edge references non-existent owner node '{}'; skipping", childId);
                    continue;
                }
                TreeNode child = buildNode(childId, edge, childAncestry, nodeById, edgesByOwned);
                children.add(child);
            }
            node.setChildren(children);
        }

        return node;
    }

    // ---------------------------------------------------------------------------
    // Root finding (W4.R1)
    // ---------------------------------------------------------------------------

    private String findRootId(List<W3Node> nodes, Map<String, List<W3Edge>> edgesByOwned) {
        // primary: layer == 0
        Optional<W3Node> layer0 = nodes.stream()
                .filter(n -> n.getLayer() != null && n.getLayer() == 0)
                .findFirst();
        if (layer0.isPresent()) {
            log.debug("Root identified by layer=0: '{}'", layer0.get().getId());
            return layer0.get().getId();
        }

        // fallback: layer-1 node with most incoming edges
        log.warn("No layer=0 node found; falling back to layer-1 node with most incoming edges");
        return nodes.stream()
                .filter(n -> n.getLayer() != null && n.getLayer() == 1)
                .max(Comparator.comparingInt(n -> edgesByOwned.getOrDefault(n.getId(), Collections.emptyList()).size()))
                .map(W3Node::getId)
                .orElseGet(() -> {
                    log.warn("No layer-1 node found either; cannot determine root");
                    return null;
                });
    }

    // ---------------------------------------------------------------------------
    // Pre-indexing helpers
    // ---------------------------------------------------------------------------

    private Map<String, W3Node> indexNodesById(List<W3Node> nodes) {
        Map<String, W3Node> index = new LinkedHashMap<>();
        for (W3Node node : nodes) {
            if (node.getId() == null) {
                log.warn("Encountered W3Node with null id; skipping");
                continue;
            }
            index.put(node.getId(), node);
        }
        return index;
    }

    /**
     * Builds a map of owned-node-id → list of edges pointing to that node.
     * Edges referencing non-existent nodes are logged and skipped (dangling edge guard).
     */
    private Map<String, List<W3Edge>> indexEdgesByOwned(List<W3Edge> edges, Map<String, W3Node> nodeById) {
        Map<String, List<W3Edge>> index = new HashMap<>();
        if (edges == null) {
            return index;
        }
        for (W3Edge edge : edges) {
            if (edge.getOwned() == null || edge.getOwner() == null) {
                log.warn("Edge with null owner or owned field encountered; skipping: {}", edge);
                continue;
            }
            if (!nodeById.containsKey(edge.getOwned())) {
                log.warn("Edge references non-existent owned node '{}'; skipping (dangling edge guard)", edge.getOwned());
                continue;
            }
            if (!nodeById.containsKey(edge.getOwner())) {
                log.warn("Edge references non-existent owner node '{}'; skipping (dangling edge guard)", edge.getOwner());
                continue;
            }
            index.computeIfAbsent(edge.getOwned(), k -> new ArrayList<>()).add(edge);
        }
        return index;
    }

    // ---------------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------------

    private String buildCyclePathDescription(LinkedHashSet<String> ancestryPath, String repeated) {
        List<String> path = new ArrayList<>(ancestryPath);
        int idx = path.indexOf(repeated);
        List<String> cycle = path.subList(idx, path.size());
        cycle.add(repeated); // close the loop
        return String.join(" → ", cycle);
    }
}
