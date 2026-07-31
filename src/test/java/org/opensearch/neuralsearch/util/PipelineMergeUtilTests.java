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

    // =========================================================================
    // SEARCH MERGE - ADDITIONAL SAFE PROCESSORS (per CONFLICT-REFERENCE.md)
    // =========================================================================

    public void testSearchMerge_oversampleAndTruncateHits_safe() {
        Map<String, Object> pipeline = searchPipeline(
            List.of(proc("oversample", Map.of("sample_factor", 2.0)), proc("truncate_hits", Map.of("target_size", 10)))
        );
        MergeResult result = mergeSearchPipeline(pipeline, aseSearchProcessors());

        assertTrue(result.canMerge());
        List<Map<String, Object>> procs = (List<Map<String, Object>>) result.getMergedPipeline().get(REQUEST_PROCESSORS_KEY);
        assertEquals(4, procs.size());
        // ASE processors prepended
        assertTrue(procs.get(0).containsKey(SEMANTIC_SEARCH_REWRITE_PROCESSOR));
        assertTrue(procs.get(1).containsKey(NEURAL_SPARSE_TWO_PHASE_PROCESSOR));
        // Customer processors preserved in order
        assertTrue(procs.get(2).containsKey("oversample"));
        assertTrue(procs.get(3).containsKey("truncate_hits"));
    }

    public void testSearchMerge_scriptProcessor_safe() {
        Map<String, Object> pipeline = searchPipeline(
            List.of(proc("script", Map.of("lang", "painless", "source", "ctx._source.size = 20")))
        );
        MergeResult result = mergeSearchPipeline(pipeline, aseSearchProcessors());

        assertTrue(result.canMerge());
        List<Map<String, Object>> procs = (List<Map<String, Object>>) result.getMergedPipeline().get(REQUEST_PROCESSORS_KEY);
        assertEquals(3, procs.size());
        assertTrue(procs.get(2).containsKey("script"));
    }

    public void testSearchMerge_multipleCustomerProcessors_orderPreserved() {
        Map<String, Object> pipeline = searchPipeline(
            List.of(
                proc("neural_query_enricher", Map.of("default_model_id", "model-1")),
                proc("filter_query", Map.of("query", Map.of("term", Map.of("status", "published")))),
                proc("script", Map.of("lang", "painless", "source", "ctx._source.explain = true"))
            )
        );
        MergeResult result = mergeSearchPipeline(pipeline, aseSearchProcessors());

        assertTrue(result.canMerge());
        List<Map<String, Object>> procs = (List<Map<String, Object>>) result.getMergedPipeline().get(REQUEST_PROCESSORS_KEY);
        assertEquals(5, procs.size());
        // ASE first
        assertTrue(procs.get(0).containsKey(SEMANTIC_SEARCH_REWRITE_PROCESSOR));
        assertTrue(procs.get(1).containsKey(NEURAL_SPARSE_TWO_PHASE_PROCESSOR));
        // Customer processors in original order
        assertTrue(procs.get(2).containsKey("neural_query_enricher"));
        assertTrue(procs.get(3).containsKey("filter_query"));
        assertTrue(procs.get(4).containsKey("script"));
    }

    // =========================================================================
    // SEARCH MERGE - SPLIT API (checkSearchPipelineConflicts + buildMergedSearchPipeline)
    // =========================================================================

    public void testCheckSearchPipelineConflicts_noConflict_emptyList() {
        Map<String, Object> pipeline = searchPipeline(List.of(proc("filter_query", Map.of("query", Map.of("term", Map.of("x", "y"))))));
        List<String> conflicts = PipelineMergeUtil.checkSearchPipelineConflicts(pipeline);

        assertTrue(conflicts.isEmpty());
    }

    public void testCheckSearchPipelineConflicts_existingNonAseRewrite_detected() {
        Map<String, Object> pipeline = searchPipeline(
            List.of(proc(SEMANTIC_SEARCH_REWRITE_PROCESSOR, Map.of("analyzer", "custom", FIELD_MAP_KEY, Map.of("x", "y"))))
        );
        List<String> conflicts = PipelineMergeUtil.checkSearchPipelineConflicts(pipeline);

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).contains("double-rewriting"));
    }

    public void testCheckSearchPipelineConflicts_existingNonAseTwoPhase_detected() {
        Map<String, Object> pipeline = searchPipeline(List.of(proc(NEURAL_SPARSE_TWO_PHASE_PROCESSOR, Map.of("enabled", true))));
        List<String> conflicts = PipelineMergeUtil.checkSearchPipelineConflicts(pipeline);

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).contains("neural_sparse_two_phase_processor"));
    }

    public void testCheckSearchPipelineConflicts_aseAlreadyPresent_detected() {
        Map<String, Object> pipeline = searchPipeline(
            List.of(proc(SEMANTIC_SEARCH_REWRITE_PROCESSOR, Map.of(TAG_KEY, ASE_MANAGED_TAG, FIELD_MAP_KEY, Map.of("x", "y"))))
        );
        List<String> conflicts = PipelineMergeUtil.checkSearchPipelineConflicts(pipeline);

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).contains("ASE-managed"));
    }

    public void testCheckSearchPipelineConflicts_multipleConflicts_allReported() {
        Map<String, Object> pipeline = searchPipeline(
            List.of(
                proc(SEMANTIC_SEARCH_REWRITE_PROCESSOR, Map.of("analyzer", "custom", FIELD_MAP_KEY, Map.of("x", "y"))),
                proc(NEURAL_SPARSE_TWO_PHASE_PROCESSOR, Map.of("enabled", true))
            )
        );
        List<String> conflicts = PipelineMergeUtil.checkSearchPipelineConflicts(pipeline);

        assertEquals(2, conflicts.size());
    }

    public void testBuildMergedSearchPipeline_prependsAseProcessors() {
        Map<String, Object> pipeline = searchPipeline(
            List.of(proc("filter_query", Map.of("query", Map.of("term", Map.of("status", "active")))))
        );
        Map<String, Object> merged = PipelineMergeUtil.buildMergedSearchPipeline(pipeline, aseSearchProcessors());

        List<Map<String, Object>> procs = (List<Map<String, Object>>) merged.get(REQUEST_PROCESSORS_KEY);
        assertEquals(3, procs.size());
        assertTrue(procs.get(0).containsKey(SEMANTIC_SEARCH_REWRITE_PROCESSOR));
        assertTrue(procs.get(1).containsKey(NEURAL_SPARSE_TWO_PHASE_PROCESSOR));
        assertTrue(procs.get(2).containsKey("filter_query"));
    }

    public void testBuildMergedSearchPipeline_preservesDescription() {
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("description", "My custom search pipeline");
        pipeline.put(
            REQUEST_PROCESSORS_KEY,
            new ArrayList<>(List.of(proc("filter_query", Map.of("query", Map.of("term", Map.of("x", "y"))))))
        );

        Map<String, Object> merged = PipelineMergeUtil.buildMergedSearchPipeline(pipeline, aseSearchProcessors());

        assertEquals("My custom search pipeline", merged.get("description"));
    }

    public void testBuildMergedSearchPipeline_preservesResponseProcessors() {
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put(REQUEST_PROCESSORS_KEY, new ArrayList<>(List.of(proc("filter_query", Map.of("query", Map.of("x", "y"))))));
        List<Map<String, Object>> responseProcessors = List.of(Map.of("rename_field", Map.of("field", "a", "target_field", "b")));
        pipeline.put("response_processors", responseProcessors);

        Map<String, Object> merged = PipelineMergeUtil.buildMergedSearchPipeline(pipeline, aseSearchProcessors());

        assertEquals(responseProcessors, merged.get("response_processors"));
    }

    public void testBuildMergedSearchPipeline_emptyExistingPipeline() {
        Map<String, Object> pipeline = searchPipeline(List.of());
        Map<String, Object> merged = PipelineMergeUtil.buildMergedSearchPipeline(pipeline, aseSearchProcessors());

        List<Map<String, Object>> procs = (List<Map<String, Object>>) merged.get(REQUEST_PROCESSORS_KEY);
        assertEquals(2, procs.size());
        assertTrue(procs.get(0).containsKey(SEMANTIC_SEARCH_REWRITE_PROCESSOR));
        assertTrue(procs.get(1).containsKey(NEURAL_SPARSE_TWO_PHASE_PROCESSOR));
    }

    // =========================================================================
    // INGEST MERGE - SPLIT API (checkIngestPipelineConflicts)
    // =========================================================================

    public void testCheckIngestPipelineConflicts_noConflict_emptyList() {
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("lowercase", Map.of("field", "text"))));
        List<String> conflicts = PipelineMergeUtil.checkIngestPipelineConflicts(pipeline, SOURCE_FIELDS);

        assertTrue(conflicts.isEmpty());
    }

    public void testCheckIngestPipelineConflicts_removeSourceField_detected() {
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("remove", Map.of("field", "text"))));
        List<String> conflicts = PipelineMergeUtil.checkIngestPipelineConflicts(pipeline, SOURCE_FIELDS);

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).contains("removes field 'text'"));
    }

    public void testCheckIngestPipelineConflicts_aseAlreadyPresent_detected() {
        Map<String, Object> pipeline = ingestPipeline(
            List.of(proc(SPARSE_ENCODING_PROCESSOR, Map.of(TAG_KEY, ASE_MANAGED_TAG, "model_id", MODEL_ID)))
        );
        List<String> conflicts = PipelineMergeUtil.checkIngestPipelineConflicts(pipeline, SOURCE_FIELDS);

        assertFalse(conflicts.isEmpty());
        assertTrue(conflicts.stream().anyMatch(c -> c.contains("ASE-managed")));
    }

    public void testCheckIngestPipelineConflicts_nonAseSparseEncoding_detected() {
        Map<String, Object> pipeline = ingestPipeline(
            List.of(proc(SPARSE_ENCODING_PROCESSOR, Map.of("model_id", "other-model", FIELD_MAP_KEY, Map.of("x", "y"))))
        );
        List<String> conflicts = PipelineMergeUtil.checkIngestPipelineConflicts(pipeline, SOURCE_FIELDS);

        assertFalse(conflicts.isEmpty());
        assertTrue(conflicts.stream().anyMatch(c -> c.contains("sparse_encoding")));
    }

    // =========================================================================
    // INGEST MERGE - MULTIPLE SOURCE FIELDS
    // =========================================================================

    public void testIngestMerge_multipleSourceFields_oneConflicting() {
        Set<String> multiFields = Set.of("title", "body");
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("remove", Map.of("field", "title"))));

        Map<String, Object> aseProc = new LinkedHashMap<>();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(TAG_KEY, ASE_MANAGED_TAG);
        config.put("model_id", MODEL_ID);
        config.put(FIELD_MAP_KEY, Map.of("title", "title_sparse", "body", "body_sparse"));
        aseProc.put(SPARSE_ENCODING_PROCESSOR, config);

        MergeResult result = mergeIngestPipeline(pipeline, multiFields, aseProc);

        assertFalse(result.canMerge());
        assertEquals(1, result.getConflicts().size());
        assertTrue(result.getConflicts().get(0).contains("title"));
    }

    public void testIngestMerge_multipleSourceFields_noneConflicting() {
        Set<String> multiFields = Set.of("title", "body");
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("lowercase", Map.of("field", "title"))));

        Map<String, Object> aseProc = new LinkedHashMap<>();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(TAG_KEY, ASE_MANAGED_TAG);
        config.put("model_id", MODEL_ID);
        config.put(FIELD_MAP_KEY, Map.of("title", "title_sparse", "body", "body_sparse"));
        aseProc.put(SPARSE_ENCODING_PROCESSOR, config);

        MergeResult result = mergeIngestPipeline(pipeline, multiFields, aseProc);

        assertTrue(result.canMerge());
    }

    public void testIngestMerge_multipleSourceFields_removeUnrelated_safe() {
        Set<String> multiFields = Set.of("title", "body");
        Map<String, Object> pipeline = ingestPipeline(List.of(proc("remove", Map.of("field", "internal_id"))));

        Map<String, Object> aseProc = new LinkedHashMap<>();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(TAG_KEY, ASE_MANAGED_TAG);
        config.put("model_id", MODEL_ID);
        config.put(FIELD_MAP_KEY, Map.of("title", "title_sparse", "body", "body_sparse"));
        aseProc.put(SPARSE_ENCODING_PROCESSOR, config);

        MergeResult result = mergeIngestPipeline(pipeline, multiFields, aseProc);

        assertTrue(result.canMerge());
    }

    // =========================================================================
    // SEARCH MERGE - EDGE CASES
    // =========================================================================

    public void testSearchMerge_nullRequestProcessors_treatedAsEmpty() {
        // Pipeline exists but has no request_processors key at all
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("description", "pipeline with only response processors");
        pipeline.put("response_processors", List.of(Map.of("rename_field", Map.of("field", "a", "target_field", "b"))));

        MergeResult result = mergeSearchPipeline(pipeline, aseSearchProcessors());

        assertTrue(result.canMerge());
        List<Map<String, Object>> procs = (List<Map<String, Object>>) result.getMergedPipeline().get(REQUEST_PROCESSORS_KEY);
        assertEquals(2, procs.size());
        assertTrue(procs.get(0).containsKey(SEMANTIC_SEARCH_REWRITE_PROCESSOR));
        assertTrue(procs.get(1).containsKey(NEURAL_SPARSE_TWO_PHASE_PROCESSOR));
        // Response processors preserved
        assertTrue(result.getMergedPipeline().containsKey("response_processors"));
    }

    public void testSearchMerge_phaseResultsProcessors_preserved() {
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put(REQUEST_PROCESSORS_KEY, new ArrayList<>(List.of(proc("filter_query", Map.of("query", Map.of("x", "y"))))));
        pipeline.put("phase_results_processors", List.of(Map.of("normalization-processor", Map.of("technique", "min_max"))));

        MergeResult result = mergeSearchPipeline(pipeline, aseSearchProcessors());

        assertTrue(result.canMerge());
        assertTrue(result.getMergedPipeline().containsKey("phase_results_processors"));
    }
}
