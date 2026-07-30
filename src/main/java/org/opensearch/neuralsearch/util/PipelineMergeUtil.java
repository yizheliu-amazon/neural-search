/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Utility for merging ASE processors into existing customer pipelines.
 *
 * <p>For search pipelines: ASE processors are PREPENDED (run first).
 * For ingest pipelines: ASE processor is APPENDED (runs last).
 *
 * <p><b>Concurrency note:</b> Methods in this class are stateless and thread-safe.
 * However, the read-validate-merge-write sequence is NOT atomic. Callers MUST
 * serialize concurrent enable requests for the same index to prevent TOCTOU races.
 * On AOSS, the DP API must implement an external lock (e.g., DynamoDB conditional
 * write or a lock document in a hidden index) since pipeline PUT has no optimistic
 * concurrency control (no if_seq_no/if_primary_term equivalent for cluster state).
 */
@Log4j2
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PipelineMergeUtil {

    public static final String ASE_MANAGED_TAG = "ase_managed";
    public static final String TAG_KEY = "tag";
    public static final String PROCESSORS_KEY = "processors";
    public static final String REQUEST_PROCESSORS_KEY = "request_processors";
    public static final String FIELD_MAP_KEY = "field_map";
    public static final String DESCRIPTION_KEY = "description";
    public static final String FIELD_KEY = "field";
    public static final String TARGET_FIELD_KEY = "target_field";

    // Processor types
    public static final String REMOVE_PROCESSOR = "remove";
    public static final String RENAME_PROCESSOR = "rename";
    public static final String SPARSE_ENCODING_PROCESSOR = "sparse_encoding";
    public static final String TEXT_EMBEDDING_PROCESSOR = "text_embedding";
    public static final String SEMANTIC_SEARCH_REWRITE_PROCESSOR = "semantic_search_rewrite_processor";
    public static final String NEURAL_SPARSE_TWO_PHASE_PROCESSOR = "neural_sparse_two_phase_processor";
    public static final String PIPELINE_PROCESSOR = "pipeline";
    public static final String TEXT_CHUNKING_PROCESSOR = "text_chunking";
    public static final String NAME_KEY = "name";

    /**
     * Result of a pipeline merge evaluation.
     */
    public static class MergeResult {
        private final boolean canMerge;
        private final List<String> conflicts;
        private final Map<String, Object> mergedPipeline;

        private MergeResult(boolean canMerge, List<String> conflicts, Map<String, Object> mergedPipeline) {
            this.canMerge = canMerge;
            this.conflicts = conflicts != null ? conflicts : List.of();
            this.mergedPipeline = mergedPipeline;
        }

        public static MergeResult success(Map<String, Object> mergedPipeline) {
            return new MergeResult(true, List.of(), mergedPipeline);
        }

        public static MergeResult blocked(List<String> conflicts) {
            return new MergeResult(false, conflicts, null);
        }

        public boolean canMerge() {
            return canMerge;
        }

        public List<String> getConflicts() {
            return conflicts;
        }

        public Map<String, Object> getMergedPipeline() {
            return mergedPipeline;
        }
    }

    // =========================================================================
    // SEARCH PIPELINE MERGE
    // =========================================================================

    /**
     * Merge ASE search processors into an existing customer search pipeline.
     * ASE processors are prepended (run first).
     *
     * @param existingPipeline The existing search pipeline definition as a Map.
     * @param aseRequestProcessors The ASE request processors to prepend.
     * @return MergeResult with the merged pipeline.
     */
    public static MergeResult mergeSearchPipeline(Map<String, Object> existingPipeline, List<Map<String, Object>> aseRequestProcessors) {

        List<String> conflicts = new ArrayList<>();

        // Check for existing ASE processors (idempotency guard)
        List<Map<String, Object>> existingRequestProcessors = getProcessorsList(existingPipeline, REQUEST_PROCESSORS_KEY);
        for (Map<String, Object> proc : existingRequestProcessors) {
            for (Map.Entry<String, Object> entry : proc.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) entry.getValue();
                if (ASE_MANAGED_TAG.equals(config.get(TAG_KEY))) {
                    conflicts.add(
                        String.format(
                            Locale.ROOT,
                            "Search pipeline already contains ASE-managed '%s' processor. "
                                + "Disable ASE first before re-enabling with new configuration.",
                            entry.getKey()
                        )
                    );
                }
            }
        }

        // Check for existing rewrite or two_phase processors (not ASE-tagged)
        for (Map<String, Object> proc : existingRequestProcessors) {
            if (proc.containsKey(SEMANTIC_SEARCH_REWRITE_PROCESSOR)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) proc.get(SEMANTIC_SEARCH_REWRITE_PROCESSOR);
                if (!ASE_MANAGED_TAG.equals(config.get(TAG_KEY))) {
                    conflicts.add(
                        "Search pipeline already contains a semantic_search_rewrite_processor "
                            + "(not ASE-managed). Adding another would cause double-rewriting."
                    );
                }
            }
            if (proc.containsKey(NEURAL_SPARSE_TWO_PHASE_PROCESSOR)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) proc.get(NEURAL_SPARSE_TWO_PHASE_PROCESSOR);
                if (!ASE_MANAGED_TAG.equals(config.get(TAG_KEY))) {
                    conflicts.add(
                        "Search pipeline already contains a neural_sparse_two_phase_processor "
                            + "(not ASE-managed). Adding another may cause unexpected rescore behavior."
                    );
                }
            }
        }

        if (!conflicts.isEmpty()) {
            return MergeResult.blocked(conflicts);
        }

        // Build merged pipeline: ASE first, then customer processors
        Map<String, Object> merged = new LinkedHashMap<>(existingPipeline);
        List<Map<String, Object>> mergedProcessors = new ArrayList<>(aseRequestProcessors);
        mergedProcessors.addAll(existingRequestProcessors);
        merged.put(REQUEST_PROCESSORS_KEY, mergedProcessors);

        return MergeResult.success(merged);
    }

    // =========================================================================
    // INGEST PIPELINE MERGE
    // =========================================================================

    /**
     * Evaluate and merge ASE's ingest processor into an existing customer ingest pipeline.
     * ASE processor is appended (runs last). Checks for conflicts first.
     *
     * @param existingPipeline The existing ingest pipeline definition as a Map.
     * @param sourceFields The text fields ASE will read from.
     * @param aseIngestProcessor The ASE ingest processor to append (e.g., sparse_encoding).
     * @return MergeResult with merged pipeline or blocked with conflict messages.
     */
    public static MergeResult mergeIngestPipeline(
        Map<String, Object> existingPipeline,
        Set<String> sourceFields,
        Map<String, Object> aseIngestProcessor
    ) {

        List<Map<String, Object>> processors = getProcessorsList(existingPipeline, PROCESSORS_KEY);
        List<String> conflicts = new ArrayList<>();

        // Check for existing ASE processors
        for (Map<String, Object> proc : processors) {
            for (Map.Entry<String, Object> entry : proc.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) entry.getValue();
                if (ASE_MANAGED_TAG.equals(config.get(TAG_KEY))) {
                    conflicts.add(
                        String.format(
                            Locale.ROOT,
                            "Ingest pipeline already contains ASE-managed '%s' processor. " + "Disable ASE first before re-enabling.",
                            entry.getKey()
                        )
                    );
                }
                // Check for non-ASE sparse_encoding
                if (SPARSE_ENCODING_PROCESSOR.equals(entry.getKey()) && !ASE_MANAGED_TAG.equals(config.get(TAG_KEY))) {
                    conflicts.add(
                        "Ingest pipeline already contains a sparse_encoding processor (not ASE-managed). "
                            + "Merging another may cause duplicate embeddings."
                    );
                }
            }
        }

        // Check for remove/rename conflicts on source fields
        conflicts.addAll(scanIngestConflicts(processors, sourceFields));

        if (!conflicts.isEmpty()) {
            return MergeResult.blocked(conflicts);
        }

        // Build merged pipeline: customer processors + ASE appended last
        Map<String, Object> merged = new LinkedHashMap<>(existingPipeline);
        List<Map<String, Object>> mergedProcessors = new ArrayList<>(processors);
        mergedProcessors.add(aseIngestProcessor);
        merged.put(PROCESSORS_KEY, mergedProcessors);

        return MergeResult.success(merged);
    }

    // =========================================================================
    // CONFLICT SCANNING
    // =========================================================================

    /**
     * Scan ingest processors for remove/rename conflicts with ASE source fields.
     */
    /**
     * Scan ingest processors for remove/rename conflicts with ASE source fields.
     * Does NOT resolve sub-pipelines (use the overload with pipelineResolver for that).
     */
    public static List<String> scanIngestConflicts(List<Map<String, Object>> processors, Set<String> sourceFields) {
        return scanIngestConflicts(processors, sourceFields, null, new java.util.HashSet<>());
    }

    /**
     * Scan ingest processors for remove/rename conflicts with ASE source fields,
     * recursively resolving sub-pipelines via the provided resolver.
     *
     * @param processors       The processors list to scan.
     * @param sourceFields     The source fields ASE will read from.
     * @param pipelineResolver A function that takes a pipeline name and returns its definition
     *                         (the Map containing "processors": [...]).  May be null to skip
     *                         sub-pipeline resolution.
     * @param visited          Set of already-visited pipeline names to prevent infinite recursion.
     * @return List of conflict messages.
     */
    public static List<String> scanIngestConflicts(
        List<Map<String, Object>> processors,
        Set<String> sourceFields,
        Function<String, Map<String, Object>> pipelineResolver,
        Set<String> visited
    ) {

        List<String> conflicts = new ArrayList<>();

        for (Map<String, Object> processorWrapper : processors) {
            for (Map.Entry<String, Object> entry : processorWrapper.entrySet()) {
                String processorType = entry.getKey();
                if (!(entry.getValue() instanceof Map)) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) entry.getValue();

                if (REMOVE_PROCESSOR.equals(processorType)) {
                    checkRemoveConflict(config, sourceFields, conflicts);
                } else if (RENAME_PROCESSOR.equals(processorType)) {
                    checkRenameConflict(config, sourceFields, conflicts);
                } else if (TEXT_CHUNKING_PROCESSOR.equals(processorType)) {
                    checkTextChunkingConflict(config, sourceFields, conflicts);
                } else if (PIPELINE_PROCESSOR.equals(processorType)) {
                    // Recursively check sub-pipeline
                    String subPipelineName = (String) config.get(NAME_KEY);
                    if (subPipelineName != null && pipelineResolver != null && !visited.contains(subPipelineName)) {
                        visited.add(subPipelineName);
                        Map<String, Object> subPipeline = pipelineResolver.apply(subPipelineName);
                        if (subPipeline != null) {
                            List<Map<String, Object>> subProcessors = getProcessorsList(subPipeline, PROCESSORS_KEY);
                            List<String> subConflicts = scanIngestConflicts(subProcessors, sourceFields, pipelineResolver, visited);
                            for (String conflict : subConflicts) {
                                conflicts.add(String.format(Locale.ROOT, "[via sub-pipeline '%s'] %s", subPipelineName, conflict));
                            }
                        } else {
                            log.warn("Sub-pipeline '{}' referenced but not found; cannot validate for conflicts.", subPipelineName);
                        }
                    }
                }
            }
        }
        return conflicts;
    }

    /**
     * Check if a target embedding field is already in use by an existing processor.
     */
    public static List<String> checkEmbeddingFieldInUse(List<Map<String, Object>> processors, Set<String> embeddingFields) {

        List<String> conflicts = new ArrayList<>();
        for (Map<String, Object> processorWrapper : processors) {
            for (Map.Entry<String, Object> entry : processorWrapper.entrySet()) {
                String processorType = entry.getKey();
                if (!(entry.getValue() instanceof Map)) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) entry.getValue();

                if (SPARSE_ENCODING_PROCESSOR.equals(processorType) || TEXT_EMBEDDING_PROCESSOR.equals(processorType)) {
                    Object fieldMapObj = config.get(FIELD_MAP_KEY);
                    if (fieldMapObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> fieldMap = (Map<String, String>) fieldMapObj;
                        for (String target : fieldMap.values()) {
                            if (embeddingFields.contains(target)) {
                                conflicts.add(
                                    String.format(
                                        Locale.ROOT,
                                        "Existing '%s' processor already writes to field '%s'. "
                                            + "Specify a different embedding field name.",
                                        processorType,
                                        target
                                    )
                                );
                            }
                        }
                    }
                }
            }
        }
        return conflicts;
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static void checkTextChunkingConflict(Map<String, Object> config, Set<String> sourceFields, List<String> conflicts) {
        Object fieldMapObj = config.get(FIELD_MAP_KEY);
        if (!(fieldMapObj instanceof Map)) return;

        Map<String, String> fieldMap = (Map<String, String>) fieldMapObj;
        for (Map.Entry<String, String> entry : fieldMap.entrySet()) {
            String chunkOutput = entry.getValue();
            // Conflict: text_chunking writes to a field that ASE reads from.
            // After chunking, the field becomes an array, and sparse_encoding will produce
            // array output that rank_features cannot store.
            if (sourceFields.contains(chunkOutput)) {
                conflicts.add(
                    String.format(
                        Locale.ROOT,
                        "text_chunking processor writes chunks to field '%s' which ASE reads as source. "
                            + "After chunking, this field becomes an array and encoding will fail. "
                            + "Use a different output field for text_chunking (e.g., '%s_chunks').",
                        chunkOutput,
                        chunkOutput
                    )
                );
            }
        }
    }

    private static void checkRemoveConflict(Map<String, Object> config, Set<String> sourceFields, List<String> conflicts) {
        Object fieldValue = config.get(FIELD_KEY);
        if (fieldValue instanceof String) {
            if (sourceFields.contains((String) fieldValue)) {
                conflicts.add(
                    String.format(
                        Locale.ROOT,
                        "Pipeline removes field '%s' before encoding would run. "
                            + "Remove the 'remove' processor or choose a different source field.",
                        fieldValue
                    )
                );
            }
        } else if (fieldValue instanceof List) {
            for (Object f : (List<Object>) fieldValue) {
                if (f instanceof String && sourceFields.contains(f)) {
                    conflicts.add(String.format(Locale.ROOT, "Pipeline removes field '%s' before encoding would run.", f));
                }
            }
        }
    }

    private static void checkRenameConflict(Map<String, Object> config, Set<String> sourceFields, List<String> conflicts) {
        String field = config.get(FIELD_KEY) instanceof String ? (String) config.get(FIELD_KEY) : null;
        String targetField = config.get(TARGET_FIELD_KEY) instanceof String ? (String) config.get(TARGET_FIELD_KEY) : null;

        if (field != null && sourceFields.contains(field) && targetField != null && !sourceFields.contains(targetField)) {
            conflicts.add(
                String.format(
                    Locale.ROOT,
                    "Pipeline renames '%s' to '%s'; encoding target missing. " + "Use '%s' as the source field instead.",
                    field,
                    targetField,
                    targetField
                )
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getProcessorsList(Map<String, Object> pipeline, String key) {
        Object value = pipeline.get(key);
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return new ArrayList<>();
    }
}
