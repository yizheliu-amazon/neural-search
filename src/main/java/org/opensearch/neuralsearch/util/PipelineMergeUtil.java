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
    public static final String MATCH_TO_NEURAL_REWRITE_PROCESSOR = "match_to_neural_rewrite_processor";
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
     * Pre-check whether an existing search pipeline blocks ASE merge, WITHOUT requiring
     * the ASE processor content (e.g., model_id) to be known yet.
     *
     * <p>Use this before any irreversible mutation (such as PutMapping, which cannot be
     * undone) so that a blocking conflict is detected before the mutation happens. Once
     * this check passes and the mutation has been applied, callers should build and PUT
     * the merged pipeline directly via {@link #buildMergedSearchPipeline} WITHOUT
     * re-running this check — the mutation has already committed, and a second rejection
     * at that point would leave the index in a half-configured state with no way back.
     *
     * @param existingPipeline The existing search pipeline definition as a Map.
     * @return List of conflict messages. Empty list means no blocking conflict.
     */
    public static List<String> checkSearchPipelineConflicts(Map<String, Object> existingPipeline) {
        List<String> conflicts = new ArrayList<>();
        List<Map<String, Object>> existingRequestProcessors = getProcessorsList(existingPipeline, REQUEST_PROCESSORS_KEY);

        // Check for existing ASE processors (idempotency guard)
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
            if (proc.containsKey(MATCH_TO_NEURAL_REWRITE_PROCESSOR)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) proc.get(MATCH_TO_NEURAL_REWRITE_PROCESSOR);
                if (!ASE_MANAGED_TAG.equals(config.get(TAG_KEY))) {
                    conflicts.add(
                        "Search pipeline already contains a match_to_neural_rewrite_processor "
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

        return conflicts;
    }

    /**
     * Build the merged search pipeline content, WITHOUT re-running conflict checks.
     *
     * <p>Callers MUST have already called {@link #checkSearchPipelineConflicts} on the
     * same {@code existingPipeline} (before any irreversible mutation) and confirmed it
     * returned no conflicts. This method performs no validation of its own — by the time
     * it runs, rejecting is no longer a safe option, since the caller's mutation (e.g.
     * PutMapping) has typically already committed.
     *
     * @param existingPipeline The existing search pipeline definition as a Map.
     * @param aseRequestProcessors The ASE request processors to prepend (with resolved model_id, etc.).
     * @return The merged pipeline content, ready to PUT.
     */
    public static Map<String, Object> buildMergedSearchPipeline(
        Map<String, Object> existingPipeline,
        List<Map<String, Object>> aseRequestProcessors
    ) {
        List<Map<String, Object>> existingRequestProcessors = getProcessorsList(existingPipeline, REQUEST_PROCESSORS_KEY);
        Map<String, Object> merged = new LinkedHashMap<>(existingPipeline);
        List<Map<String, Object>> mergedProcessors = new ArrayList<>(aseRequestProcessors);
        mergedProcessors.addAll(existingRequestProcessors);
        merged.put(REQUEST_PROCESSORS_KEY, mergedProcessors);
        return merged;
    }

    /**
     * Convenience method that runs {@link #checkSearchPipelineConflicts} and
     * {@link #buildMergedSearchPipeline} together in one call.
     *
     * <p>Only use this when the ASE processor content is fully known up front and no
     * irreversible mutation happens between the check and the build (i.e., there is no
     * PutMapping-style step in between). If there IS such a step, call
     * {@link #checkSearchPipelineConflicts} before it and {@link #buildMergedSearchPipeline}
     * after it instead — do not call this combined method in that case.
     *
     * @param existingPipeline The existing search pipeline definition as a Map.
     * @param aseRequestProcessors The ASE request processors to prepend.
     * @return MergeResult with the merged pipeline, or blocked with conflict messages.
     */
    public static MergeResult mergeSearchPipeline(Map<String, Object> existingPipeline, List<Map<String, Object>> aseRequestProcessors) {
        List<String> conflicts = checkSearchPipelineConflicts(existingPipeline);
        if (!conflicts.isEmpty()) {
            return MergeResult.blocked(conflicts);
        }
        return MergeResult.success(buildMergedSearchPipeline(existingPipeline, aseRequestProcessors));
    }

    // =========================================================================
    // INGEST PIPELINE MERGE
    //
    // ASE now uses standard ingest processors (set/rename) in the customer's ingest
    // pipeline for data routing between original_field and semantic_field. These methods
    // are called from RestSemanticEnableHandler to merge ASE processors into an existing
    // customer ingest pipeline (if one is already set as index.default_pipeline).
    // =========================================================================

    /**
     * Pre-check whether an existing ingest pipeline blocks ASE merge, WITHOUT requiring
     * the ASE processor content (e.g., model_id) to be known yet.
     *
     * <p>Same rationale as {@link #checkSearchPipelineConflicts}: call this before any
     * irreversible mutation, and use {@link #buildMergedIngestPipeline} afterward WITHOUT
     * re-checking.
     *
     * @param existingPipeline The existing ingest pipeline definition as a Map.
     * @param sourceFields The text fields ASE will read from.
     * @return List of conflict messages. Empty list means no blocking conflict.
     */
    public static List<String> checkIngestPipelineConflicts(Map<String, Object> existingPipeline, Set<String> sourceFields) {
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

        return conflicts;
    }

    /**
     * Build the merged ingest pipeline content, WITHOUT re-running conflict checks.
     *
     * <p>Callers MUST have already called {@link #checkIngestPipelineConflicts} on the
     * same {@code existingPipeline} before any irreversible mutation and confirmed it
     * returned no conflicts. See {@link #buildMergedSearchPipeline} javadoc for the
     * same rationale.
     *
     * @param existingPipeline The existing ingest pipeline definition as a Map.
     * @param aseIngestProcessor The ASE ingest processor to append (e.g., sparse_encoding).
     * @return The merged pipeline content, ready to PUT.
     */
    public static Map<String, Object> buildMergedIngestPipeline(
        Map<String, Object> existingPipeline,
        Map<String, Object> aseIngestProcessor
    ) {
        List<Map<String, Object>> processors = getProcessorsList(existingPipeline, PROCESSORS_KEY);
        Map<String, Object> merged = new LinkedHashMap<>(existingPipeline);
        List<Map<String, Object>> mergedProcessors = new ArrayList<>(processors);
        mergedProcessors.add(aseIngestProcessor);
        merged.put(PROCESSORS_KEY, mergedProcessors);
        return merged;
    }

    /**
     * Convenience method that runs {@link #checkIngestPipelineConflicts} and
     * {@link #buildMergedIngestPipeline} together in one call. Only use when there is no
     * irreversible mutation between the check and the build — see
     * {@link #mergeSearchPipeline} javadoc for the same caveat.
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
        List<String> conflicts = checkIngestPipelineConflicts(existingPipeline, sourceFields);
        if (!conflicts.isEmpty()) {
            return MergeResult.blocked(conflicts);
        }
        return MergeResult.success(buildMergedIngestPipeline(existingPipeline, aseIngestProcessor));
    }

    // =========================================================================
    // DEPLOY / ROLLBACK / DISABLE — PIPELINE SURGERY
    // =========================================================================

    /**
     * Remove all ASE-managed processors from a processor list.
     *
     * @param processors The processor list (from "processors" or "request_processors" key).
     * @return A new list with ASE-managed processors removed. May be empty.
     */
    public static List<Map<String, Object>> removeAseProcessors(List<Map<String, Object>> processors) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> proc : processors) {
            if (!isAseManaged(proc)) {
                result.add(proc);
            }
        }
        return result;
    }

    /**
     * Swap ASE-managed ingest processors from enrich mode (set/copy) to deploy mode (rename).
     *
     * <p>In enrich mode, ASE uses a {@code set} processor to COPY original_field → semantic_field.
     * In deploy mode, ASE uses a {@code rename} processor to MOVE original_field → semantic_field.
     *
     * <p>Non-ASE processors are passed through unchanged.
     *
     * @param processors The processor list from the ingest pipeline.
     * @return A new list with ASE set processors replaced by equivalent rename processors.
     */
    public static List<Map<String, Object>> swapToDeployMode(List<Map<String, Object>> processors) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> proc : processors) {
            if (!isAseManaged(proc)) {
                result.add(proc);
                continue;
            }
            // Check if this is a "set" processor that needs to become "rename"
            if (proc.containsKey("set")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> setConfig = (Map<String, Object>) proc.get("set");
                // set processor: field=semantic_field, value={{original_field}}
                // rename processor: field=original_field, target_field=semantic_field
                String semanticField = (String) setConfig.get(FIELD_KEY);
                String valueTemplate = (String) setConfig.get("value");
                String originalField = extractFieldFromMustache(valueTemplate);
                if (semanticField != null && originalField != null) {
                    Map<String, Object> renameConfig = new LinkedHashMap<>();
                    renameConfig.put(TAG_KEY, ASE_MANAGED_TAG);
                    renameConfig.put(FIELD_KEY, originalField);
                    renameConfig.put(TARGET_FIELD_KEY, semanticField);
                    Map<String, Object> renameProc = new LinkedHashMap<>();
                    renameProc.put(RENAME_PROCESSOR, renameConfig);
                    result.add(renameProc);
                } else {
                    // Can't parse — leave unchanged
                    result.add(proc);
                }
            } else {
                // Not a set processor (already rename, or other ASE-managed) — pass through
                result.add(proc);
            }
        }
        return result;
    }

    /**
     * Swap ASE-managed ingest processors from deploy mode (rename) back to enrich mode (set/copy).
     *
     * <p>Reverses what {@link #swapToDeployMode} does.
     *
     * @param processors The processor list from the ingest pipeline.
     * @return A new list with ASE rename processors replaced by equivalent set processors.
     */
    public static List<Map<String, Object>> swapToEnrichMode(List<Map<String, Object>> processors) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> proc : processors) {
            if (!isAseManaged(proc)) {
                result.add(proc);
                continue;
            }
            // Check if this is a "rename" processor that needs to become "set"
            if (proc.containsKey(RENAME_PROCESSOR)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> renameConfig = (Map<String, Object>) proc.get(RENAME_PROCESSOR);
                // rename processor: field=original_field, target_field=semantic_field
                // set processor: field=semantic_field, value={{original_field}}
                String originalField = (String) renameConfig.get(FIELD_KEY);
                String semanticField = (String) renameConfig.get(TARGET_FIELD_KEY);
                if (originalField != null && semanticField != null) {
                    Map<String, Object> setConfig = new LinkedHashMap<>();
                    setConfig.put(TAG_KEY, ASE_MANAGED_TAG);
                    setConfig.put(FIELD_KEY, semanticField);
                    setConfig.put("value", "{{" + originalField + "}}");
                    Map<String, Object> setProc = new LinkedHashMap<>();
                    setProc.put("set", setConfig);
                    result.add(setProc);
                } else {
                    result.add(proc);
                }
            } else {
                // Not a rename processor — pass through
                result.add(proc);
            }
        }
        return result;
    }

    /**
     * Check whether a processor wrapper has the ASE-managed tag.
     *
     * @param processorWrapper A single processor entry (e.g., {"set": {"tag": "ase_managed", ...}}).
     * @return true if any processor config in this wrapper has tag=ase_managed.
     */
    public static boolean isAseManaged(Map<String, Object> processorWrapper) {
        for (Object val : processorWrapper.values()) {
            if (val instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) val;
                if (ASE_MANAGED_TAG.equals(config.get(TAG_KEY))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Extract a field name from a Mustache template like "{{field_name}}".
     * Returns null if the template doesn't match the expected pattern.
     */
    static String extractFieldFromMustache(String template) {
        if (template == null) return null;
        String trimmed = template.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            return trimmed.substring(2, trimmed.length() - 2).trim();
        }
        return null;
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
