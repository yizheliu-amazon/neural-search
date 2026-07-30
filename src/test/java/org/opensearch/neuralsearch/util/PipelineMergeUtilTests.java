/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.util;

import org.opensearch.test.OpenSearchTestCase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.opensearch.neuralsearch.util.PipelineMergeUtil.ASE_MANAGED_TAG;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.FIELD_MAP_KEY;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.NEURAL_SPARSE_TWO_PHASE_PROCESSOR;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.PROCESSORS_KEY;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.REQUEST_PROCESSORS_KEY;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.SEMANTIC_SEARCH_REWRITE_PROCESSOR;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.SPARSE_ENCODING_PROCESSOR;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.TAG_KEY;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.checkEmbeddingFieldInUse;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.mergeIngestPipeline;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.mergeSearchPipeline;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.scanIngestConflicts;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.MergeResult;

public class PipelineMergeUtilTests extends OpenSearchTestCase {

    private static final String MODEL_ID = "test-model-id";
    private static final String ANALYZER = "bert-uncased";
    private static final Set<String> SOURCE_FIELDS = Set.of("text");

    // --- Helper methods ---

    private static Map<String, Object> proc(String type, Map<String, Object> config) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put(type, new LinkedHashMap<>(config));
        return wrapper;
    }

    private static Map<String, Object> ingestPipeline(List<Map<String, Object>> processors) {
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("description", "test pipeline");
        pipeline.put(PROCESSORS_KEY, new ArrayList<>(processors));
        return pipeline;
    }

    private static Map<String, Object> searchPipeline(List<Map<String, Object>> requestProcessors) {
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("description", "test search pipeline");
        pipeline.put(REQUEST_PROCESSORS_KEY, new ArrayList<>(requestProcessors));
        return pipeline;
    }

    private static Map<String, Object> aseIngestProcessor() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(TAG_KEY, ASE_MANAGED_TAG);
        config.put("model_id", MODEL_ID);
        config.put(FIELD_MAP_KEY, Map.of("text", "text_sparse"));
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put(SPARSE_ENCODING_PROCESSOR, config);
        return wrapper;
    }

    private static List<Map<String, Object>> aseSearchProcessors() {
        List<Map<String, Object>> procs = new ArrayList<>();
        Map<String, Object> rewrite = new LinkedHashMap<>();
        rewrite.put(
            SEMANTIC_SEARCH_REWRITE_PROCESSOR,
            Map.of(TAG_KEY, ASE_MANAGED_TAG, "analyzer", ANALYZER, FIELD_MAP_KEY, Map.of("text", "text_sparse"))
        );
        procs.add(rewrite);
        Map<String, Object> twoPhase = new LinkedHashMap<>();
        twoPhase.put(NEURAL_SPARSE_TWO_PHASE_PROCESSOR, Map.of(TAG_KEY, ASE_MANAGED_TAG, "enabled", true));
        procs.add(twoPhase);
        return procs;
    }

    // =========================================================================
    // INGEST MERGE - SAFE CASES
    // =========================================================================

    public void testIngestMerge_emptyPipeline() {
        Map<String, Object> pipeline = ingestPipeline(List.of());
        MergeResult result = mergeIngestPipeline(pipeline, SOURCE_FIELDS, aseIngestProcessor());

        assertTrue(result.canMerge());
        assertTrue(result.getConflicts().isEmpty());
        List<Map<String, Object>> procs = (List<Map<String, Object>>) result.getMergedPipeline().get(PROCESSORS_KEY);
        assertEquals(1, procs.size());
        assertTrue(procs.get(0).containsKey(SPARSE_ENCODING_PROCESSOR));
    }

    public void testIngestMerge_lowercaseProcessor() {
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("lowercase", Map.of("field", "text"))));
        MergeResult result = mergeIngestPipeline(pipeline, SOURCE_FIELDS, aseIngestProcessor());

        assertTrue(result.canMerge());
        List<Map<String, Object>> procs = (List<Map<String, Object>>) result.getMergedPipeline().get(PROCESSORS_KEY);
        assertEquals(2, procs.size());
        assertTrue(procs.get(0).containsKey("lowercase"));
        assertTrue(procs.get(1).containsKey(SPARSE_ENCODING_PROCESSOR));
    }

    public void testIngestMerge_removeUnrelatedField() {
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("remove", Map.of("field", "internal_id"))));
        MergeResult result = mergeIngestPipeline(pipeline, SOURCE_FIELDS, aseIngestProcessor());

        assertTrue(result.canMerge());
    }

    public void testIngestMerge_renameIntoSourceField() {
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("rename", Map.of("field", "body", "target_field", "text"))));
        MergeResult result = mergeIngestPipeline(pipeline, SOURCE_FIELDS, aseIngestProcessor());

        assertTrue(result.canMerge());
    }

    public void testIngestMerge_multipleProcessors() {
        Map<String, Object> pipeline = ingestPipeline(
            List.of(
                proc("lowercase", Map.of("field", "text")),
                proc("trim", Map.of("field", "text")),
                proc("set", Map.of("field", "processed", "value", true))
            )
        );
        MergeResult result = mergeIngestPipeline(pipeline, SOURCE_FIELDS, aseIngestProcessor());

        assertTrue(result.canMerge());
        List<Map<String, Object>> procs = (List<Map<String, Object>>) result.getMergedPipeline().get(PROCESSORS_KEY);
        assertEquals(4, procs.size());
        assertTrue(procs.get(3).containsKey(SPARSE_ENCODING_PROCESSOR));
    }

    // =========================================================================
    // INGEST MERGE - CONFLICT CASES
    // =========================================================================

    public void testIngestMerge_removeSourceField_blocked() {
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("remove", Map.of("field", "text"))));
        MergeResult result = mergeIngestPipeline(pipeline, SOURCE_FIELDS, aseIngestProcessor());

        assertFalse(result.canMerge());
        assertEquals(1, result.getConflicts().size());
        assertTrue(result.getConflicts().get(0).contains("removes field 'text'"));
    }

    public void testIngestMerge_removeSourceFieldFromList_blocked() {
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("remove", Map.of("field", List.of("text", "other")))));
        MergeResult result = mergeIngestPipeline(pipeline, SOURCE_FIELDS, aseIngestProcessor());

        assertFalse(result.canMerge());
        assertEquals(1, result.getConflicts().size());
    }

    public void testIngestMerge_renameAway_blocked() {
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("rename", Map.of("field", "text", "target_field", "original_text"))));
        MergeResult result = mergeIngestPipeline(pipeline, SOURCE_FIELDS, aseIngestProcessor());

        assertFalse(result.canMerge());
        assertEquals(1, result.getConflicts().size());
        assertTrue(result.getConflicts().get(0).contains("renames 'text'"));
    }

    public void testIngestMerge_textChunkingOverwritesSource_blocked() {
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("text_chunking", Map.of("field_map", Map.of("title", "text")))));
        MergeResult result = mergeIngestPipeline(pipeline, SOURCE_FIELDS, aseIngestProcessor());

        assertFalse(result.canMerge());
        assertEquals(1, result.getConflicts().size());
        assertTrue(result.getConflicts().get(0).contains("text_chunking"));
    }

    public void testIngestMerge_textChunkingDifferentOutput_safe() {
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("text_chunking", Map.of("field_map", Map.of("text", "text_chunks")))));
        MergeResult result = mergeIngestPipeline(pipeline, SOURCE_FIELDS, aseIngestProcessor());

        assertTrue(result.canMerge());
    }

    public void testIngestMerge_existingSparseEncoding_blocked() {
        Map<String, Object> pipeline = ingestPipeline(
            List.of(proc(SPARSE_ENCODING_PROCESSOR, Map.of("model_id", "other-model", "field_map", Map.of("x", "y"))))
        );
        MergeResult result = mergeIngestPipeline(pipeline, SOURCE_FIELDS, aseIngestProcessor());

        assertFalse(result.canMerge());
        assertTrue(result.getConflicts().get(0).contains("sparse_encoding"));
    }

    public void testIngestMerge_aseAlreadyEnabled_blocked() {
        Map<String, Object> pipeline = ingestPipeline(
            List.of(proc(SPARSE_ENCODING_PROCESSOR, Map.of(TAG_KEY, ASE_MANAGED_TAG, "model_id", MODEL_ID)))
        );
        MergeResult result = mergeIngestPipeline(pipeline, SOURCE_FIELDS, aseIngestProcessor());

        assertFalse(result.canMerge());
        assertTrue(result.getConflicts().stream().anyMatch(c -> c.contains("ASE-managed")));
    }

    // =========================================================================
    // INGEST MERGE - SUB-PIPELINE
    // =========================================================================

    public void testIngestConflicts_subPipelineWithRemove_detected() {
        List<Map<String, Object>> processors = List.of(proc("pipeline", Map.of("name", "sub-cleanup")));

        Map<String, Object> subPipeline = ingestPipeline(List.of(proc("remove", Map.of("field", "text"))));

        Function<String, Map<String, Object>> resolver = name -> "sub-cleanup".equals(name) ? subPipeline : null;

        List<String> conflicts = scanIngestConflicts(processors, SOURCE_FIELDS, resolver, new HashSet<>());

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).contains("sub-pipeline 'sub-cleanup'"));
        assertTrue(conflicts.get(0).contains("removes field 'text'"));
    }

    public void testIngestConflicts_nestedSubPipeline_detected() {
        Map<String, Object> leaf = ingestPipeline(List.of(proc("remove", Map.of("field", "text"))));
        Map<String, Object> mid = ingestPipeline(List.of(proc("pipeline", Map.of("name", "leaf"))));

        List<Map<String, Object>> topProcessors = List.of(proc("pipeline", Map.of("name", "mid")));

        Function<String, Map<String, Object>> resolver = name -> {
            if ("mid".equals(name)) return mid;
            if ("leaf".equals(name)) return leaf;
            return null;
        };

        List<String> conflicts = scanIngestConflicts(topProcessors, SOURCE_FIELDS, resolver, new HashSet<>());

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).contains("leaf"));
    }

    public void testIngestConflicts_circularSubPipeline_noCrash() {
        Map<String, Object> circular = ingestPipeline(List.of(proc("pipeline", Map.of("name", "self"))));

        Function<String, Map<String, Object>> resolver = name -> "self".equals(name) ? circular : null;

        List<Map<String, Object>> processors = List.of(proc("pipeline", Map.of("name", "self")));

        // Should not infinite loop - visited set prevents re-entry
        List<String> conflicts = scanIngestConflicts(processors, SOURCE_FIELDS, resolver, new HashSet<>());
        // No conflict from the circular ref itself (just no remove in it)
        assertTrue(conflicts.isEmpty());
    }

    public void testIngestConflicts_safeSubPipeline_noConflict() {
        Map<String, Object> safeSub = ingestPipeline(List.of(proc("lowercase", Map.of("field", "text"))));

        Function<String, Map<String, Object>> resolver = name -> "safe-sub".equals(name) ? safeSub : null;

        List<Map<String, Object>> processors = List.of(proc("pipeline", Map.of("name", "safe-sub")));
        List<String> conflicts = scanIngestConflicts(processors, SOURCE_FIELDS, resolver, new HashSet<>());

        assertTrue(conflicts.isEmpty());
    }

    // =========================================================================
    // SEARCH MERGE
    // =========================================================================

    public void testSearchMerge_emptyPipeline() {
        Map<String, Object> pipeline = searchPipeline(List.of());
        MergeResult result = mergeSearchPipeline(pipeline, aseSearchProcessors());

        assertTrue(result.canMerge());
        List<Map<String, Object>> procs = (List<Map<String, Object>>) result.getMergedPipeline().get(REQUEST_PROCESSORS_KEY);
        assertEquals(2, procs.size());
        assertTrue(procs.get(0).containsKey(SEMANTIC_SEARCH_REWRITE_PROCESSOR));
        assertTrue(procs.get(1).containsKey(NEURAL_SPARSE_TWO_PHASE_PROCESSOR));
    }

    public void testSearchMerge_customerFilterQuery_asePrepended() {
        Map<String, Object> pipeline = searchPipeline(
            List.of(proc("filter_query", Map.of("query", Map.of("term", Map.of("status", "active")))))
        );
        MergeResult result = mergeSearchPipeline(pipeline, aseSearchProcessors());

        assertTrue(result.canMerge());
        List<Map<String, Object>> procs = (List<Map<String, Object>>) result.getMergedPipeline().get(REQUEST_PROCESSORS_KEY);
        assertEquals(3, procs.size());
        assertTrue(procs.get(0).containsKey(SEMANTIC_SEARCH_REWRITE_PROCESSOR));
        assertTrue(procs.get(1).containsKey(NEURAL_SPARSE_TWO_PHASE_PROCESSOR));
        assertTrue(procs.get(2).containsKey("filter_query"));
    }

    public void testSearchMerge_preservesOtherSections() {
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("description", "full pipeline");
        pipeline.put(
            REQUEST_PROCESSORS_KEY,
            new ArrayList<>(List.of(proc("filter_query", Map.of("query", Map.of("term", Map.of("x", "y"))))))
        );
        pipeline.put("response_processors", List.of(Map.of("rename_field", Map.of("field", "a", "target_field", "b"))));
        pipeline.put("phase_results_processors", List.of(Map.of("normalization", Map.of("technique", "min_max"))));

        MergeResult result = mergeSearchPipeline(pipeline, aseSearchProcessors());

        assertTrue(result.canMerge());
        assertTrue(result.getMergedPipeline().containsKey("response_processors"));
        assertTrue(result.getMergedPipeline().containsKey("phase_results_processors"));
    }

    public void testSearchMerge_aseAlreadyEnabled_blocked() {
        Map<String, Object> pipeline = searchPipeline(
            List.of(proc(NEURAL_SPARSE_TWO_PHASE_PROCESSOR, Map.of(TAG_KEY, ASE_MANAGED_TAG, "enabled", true)))
        );
        MergeResult result = mergeSearchPipeline(pipeline, aseSearchProcessors());

        assertFalse(result.canMerge());
        assertTrue(result.getConflicts().stream().anyMatch(c -> c.contains("ASE-managed")));
    }

    public void testSearchMerge_existingNonAseRewrite_blocked() {
        Map<String, Object> pipeline = searchPipeline(
            List.of(proc(SEMANTIC_SEARCH_REWRITE_PROCESSOR, Map.of("analyzer", "custom", FIELD_MAP_KEY, Map.of("x", "y"))))
        );
        MergeResult result = mergeSearchPipeline(pipeline, aseSearchProcessors());

        assertFalse(result.canMerge());
        assertTrue(result.getConflicts().get(0).contains("double-rewriting"));
    }

    public void testSearchMerge_existingNonAseTwoPhase_blocked() {
        Map<String, Object> pipeline = searchPipeline(List.of(proc(NEURAL_SPARSE_TWO_PHASE_PROCESSOR, Map.of("enabled", true))));
        MergeResult result = mergeSearchPipeline(pipeline, aseSearchProcessors());

        assertFalse(result.canMerge());
        assertTrue(result.getConflicts().get(0).contains("neural_sparse_two_phase_processor"));
    }

    public void testSearchMerge_neuralQueryEnricher_safe() {
        Map<String, Object> pipeline = searchPipeline(List.of(proc("neural_query_enricher", Map.of("default_model_id", "some-model"))));
        MergeResult result = mergeSearchPipeline(pipeline, aseSearchProcessors());

        assertTrue(result.canMerge());
    }

    // =========================================================================
    // EMBEDDING FIELD IN USE
    // =========================================================================

    public void testEmbeddingFieldInUse_noConflict() {
        List<Map<String, Object>> processors = List.of(proc("lowercase", Map.of("field", "text")));
        List<String> conflicts = checkEmbeddingFieldInUse(processors, Set.of("text_sparse"));

        assertTrue(conflicts.isEmpty());
    }

    public void testEmbeddingFieldInUse_sparseEncodingSameTarget_conflict() {
        List<Map<String, Object>> processors = List.of(
            proc(SPARSE_ENCODING_PROCESSOR, Map.of("model_id", "x", FIELD_MAP_KEY, Map.of("title", "text_sparse")))
        );
        List<String> conflicts = checkEmbeddingFieldInUse(processors, Set.of("text_sparse"));

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).contains("text_sparse"));
    }

    public void testEmbeddingFieldInUse_textEmbeddingSameTarget_conflict() {
        List<Map<String, Object>> processors = List.of(
            proc("text_embedding", Map.of("model_id", "x", FIELD_MAP_KEY, Map.of("title", "title_embedding")))
        );
        List<String> conflicts = checkEmbeddingFieldInUse(processors, Set.of("title_embedding"));

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).contains("text_embedding"));
    }

    public void testEmbeddingFieldInUse_differentTarget_safe() {
        List<Map<String, Object>> processors = List.of(
            proc(SPARSE_ENCODING_PROCESSOR, Map.of("model_id", "x", FIELD_MAP_KEY, Map.of("desc", "desc_sparse")))
        );
        List<String> conflicts = checkEmbeddingFieldInUse(processors, Set.of("text_sparse"));

        assertTrue(conflicts.isEmpty());
    }
}
