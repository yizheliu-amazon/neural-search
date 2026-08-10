/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.rest;

import com.google.common.collect.ImmutableList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.opensearch.action.search.GetSearchPipelineAction;
import org.opensearch.action.search.GetSearchPipelineRequest;
import org.opensearch.action.search.PutSearchPipelineAction;
import org.opensearch.action.search.PutSearchPipelineRequest;
import org.opensearch.action.ingest.GetPipelineAction;
import org.opensearch.action.ingest.GetPipelineRequest;
import org.opensearch.action.ingest.GetPipelineResponse;
import org.opensearch.action.ingest.PutPipelineRequest;
import org.opensearch.neuralsearch.util.PipelineMergeUtil;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.neuralsearch.plugin.NeuralSearch;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.BytesRestResponse;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.RestChannel;
import org.opensearch.transport.client.node.NodeClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * REST handler for semantic enrichment lifecycle APIs.
 *
 * Uses a customer-visible default ingest pipeline with standard set(copy)/rename processors
 * for data routing. The semantic field's system ingest processor handles embedding generation
 * independently — zero changes to semantic field internals.
 *
 * POST /_plugins/_neural/semantic/{index}/enable_semantic_enrichment
 * POST /_plugins/_neural/semantic/{index}/disable_semantic_enrichment
 * POST /_plugins/_neural/semantic/{index}/deploy_semantic_enrichment
 * POST /_plugins/_neural/semantic/{index}/rollback_semantic_enrichment
 * GET  /_plugins/_neural/semantic/{index}/list_semantic_enrichment
 */
public class RestSemanticEnableHandler extends BaseRestHandler {

    private static final Logger log = LogManager.getLogger(RestSemanticEnableHandler.class);
    private static final String SEMANTIC_ENABLE_ACTION = "neural_semantic_enable_action";
    private static final String INGEST_PIPELINE_SUFFIX = "-ase-ingest-pipeline";
    private static final String SEARCH_PIPELINE_SUFFIX = "-ase-search-pipeline";
    private static final String FIELD_REPLACEMENT_PROCESSOR_TYPE = "field_replacement_processor";
    private static final Set<String> COMPATIBLE_TYPES = Set.of("text", "keyword", "match_only_text", "wildcard", "token_count", "binary");

    private final ClusterService clusterService;

    public RestSemanticEnableHandler(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    @Override
    public String getName() {
        return SEMANTIC_ENABLE_ACTION;
    }

    @Override
    public List<Route> routes() {
        String base = NeuralSearch.NEURAL_BASE_URI + "/semantic/{index}";
        return ImmutableList.of(
            new Route(RestRequest.Method.POST, base + "/enable_semantic_enrichment"),
            new Route(RestRequest.Method.POST, base + "/disable_semantic_enrichment"),
            new Route(RestRequest.Method.POST, base + "/deploy_semantic_enrichment"),
            new Route(RestRequest.Method.POST, base + "/rollback_semantic_enrichment"),
            new Route(RestRequest.Method.GET, base + "/list_semantic_enrichment")
        );
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        String index = request.param("index");
        String path = request.path();
        if (path.endsWith("/list_semantic_enrichment")) return channel -> handleList(index, client, channel);
        Map<String, Object> body = request.contentParser().map();
        if (path.endsWith("/enable_semantic_enrichment")) return channel -> handleEnable(index, body, client, channel);
        if (path.endsWith("/disable_semantic_enrichment")) return channel -> handleDisable(index, body, client, channel);
        if (path.endsWith("/deploy_semantic_enrichment")) return channel -> handleDeploy(index, body, client, channel);
        if (path.endsWith("/rollback_semantic_enrichment")) return channel -> handleRollback(index, body, client, channel);
        return channel -> sendError(channel, RestStatus.BAD_REQUEST, "Unknown action: " + path);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractFields(Map<String, Object> body) {
        Object fieldsObj = body.get("fields");
        if (fieldsObj instanceof List<?>) return (List<Map<String, Object>>) fieldsObj;
        if (body.containsKey("original_field")) return List.of(body);
        return null;
    }

    // ========================= enable =========================

    @SuppressWarnings("unchecked")
    private void handleEnable(String index, Map<String, Object> body, NodeClient client, RestChannel channel) {
        List<Map<String, Object>> fields = extractFields(body);
        if (fields == null || fields.isEmpty()) {
            sendError(channel, RestStatus.BAD_REQUEST, "fields required");
            return;
        }

        IndexMetadata indexMetadata = clusterService.state().metadata().index(index);
        if (indexMetadata == null) {
            sendError(channel, RestStatus.NOT_FOUND, "Index [" + index + "] does not exist");
            return;
        }

        Map<String, Object> properties = indexMetadata.mapping() != null
            ? (Map<String, Object>) indexMetadata.mapping().sourceAsMap().get("properties")
            : Map.of();

        List<String[]> validated = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            String orig = (String) f.get("original_field");
            String sem = (String) f.get("semantic_field");
            String lang = (String) f.getOrDefault("language", "ENGLISH");
            String mtype = (String) f.getOrDefault("model_type", "SPARSE");
            if (orig == null || orig.isEmpty()) {
                sendError(channel, RestStatus.BAD_REQUEST, "original_field required");
                return;
            }
            if (sem == null || sem.isEmpty()) {
                sendError(channel, RestStatus.BAD_REQUEST, "semantic_field required");
                return;
            }
            if (properties.get(orig) == null) {
                sendError(channel, RestStatus.BAD_REQUEST, "original_field [" + orig + "] not found");
                return;
            }
            validated.add(new String[] { orig, sem, lang, mtype });
        }

        // Build mapping source
        StringBuilder mb = new StringBuilder("{\"properties\":{");
        boolean first = true;
        for (String[] v : validated) {
            if (!first) mb.append(",");
            mb.append(
                String.format(
                    Locale.ROOT,
                    "\"%s\":{\"type\":\"semantic\",\"original_field\":\"%s\",\"language\":\"%s\",\"model_type\":\"%s\",\"status\":\"ENABLED\"}",
                    v[1],
                    v[0],
                    v[2].toUpperCase(Locale.ROOT),
                    v[3].toUpperCase(Locale.ROOT)
                )
            );
            first = false;
        }
        mb.append("}}");
        final String mappingSource = mb.toString();

        // Pre-check: validate that both ingest and search pipeline merges will succeed
        // BEFORE the irreversible PutMapping. Collect source fields for ingest validation.
        Set<String> sourceFields = new HashSet<>();
        for (String[] v : validated) {
            sourceFields.add(v[0]);
        }

        // ASE installs its routing processors in the FINAL pipeline, not the default pipeline:
        // - final_pipeline cannot be bypassed by a request-level ?pipeline= parameter
        // - final_pipeline still runs BEFORE the system ingest pipeline that generates embeddings
        // See SemanticFieldUpdateSemanticsIT for the tests establishing both properties.
        //
        // We must still conflict-check the DEFAULT pipeline even though we never modify it, because
        // it runs first and could remove or rename the source field out from under us.
        String existingDefaultPipeline = indexMetadata.getSettings().get("index.default_pipeline");
        String existingFinalPipeline = indexMetadata.getSettings().get("index.final_pipeline");
        String existingSearchPipeline = indexMetadata.getSettings().get("index.search.default_pipeline");

        List<String> ingestPipelinesToCheck = new ArrayList<>();
        if (isRealPipeline(existingDefaultPipeline)) {
            ingestPipelinesToCheck.add(existingDefaultPipeline);
        }
        if (isRealPipeline(existingFinalPipeline)) {
            ingestPipelinesToCheck.add(existingFinalPipeline);
        }

        preCheckIngestPipelines(
            index,
            mappingSource,
            validated,
            sourceFields,
            ingestPipelinesToCheck,
            0,
            existingFinalPipeline,
            existingSearchPipeline,
            client,
            channel
        );
    }

    /** True when a pipeline setting names an actual pipeline (not absent, not the _none sentinel). */
    private boolean isRealPipeline(String pipelineName) {
        return pipelineName != null && !"_none".equals(pipelineName);
    }

    /**
     * Sequentially conflict-check each ingest pipeline attached to the index (default and final)
     * before the irreversible PutMapping. Both are checked; only the final pipeline is later modified.
     * Once all pass, moves on to the search pipeline check.
     */
    @SuppressWarnings("unchecked")
    private void preCheckIngestPipelines(
        String index,
        String mappingSource,
        List<String[]> validated,
        Set<String> sourceFields,
        List<String> pipelinesToCheck,
        int position,
        String existingFinalPipeline,
        String existingSearchPipeline,
        NodeClient client,
        RestChannel channel
    ) {
        if (position >= pipelinesToCheck.size()) {
            preCheckSearchPipelineAndProceed(
                index,
                mappingSource,
                validated,
                sourceFields,
                existingFinalPipeline,
                existingSearchPipeline,
                client,
                channel
            );
            return;
        }

        final String pipelineName = pipelinesToCheck.get(position);
        final Runnable next = () -> preCheckIngestPipelines(
            index,
            mappingSource,
            validated,
            sourceFields,
            pipelinesToCheck,
            position + 1,
            existingFinalPipeline,
            existingSearchPipeline,
            client,
            channel
        );

        client.admin().cluster().execute(GetPipelineAction.INSTANCE, new GetPipelineRequest(pipelineName), ActionListener.wrap(resp -> {
            if (resp.isFound() && resp.pipelines() != null && !resp.pipelines().isEmpty()) {
                Map<String, Object> pipelineConfig = resp.pipelines().get(0).getConfigAsMap();
                List<String> conflicts = PipelineMergeUtil.checkIngestPipelineConflicts(pipelineConfig, sourceFields);
                if (!conflicts.isEmpty()) {
                    sendError(
                        channel,
                        RestStatus.CONFLICT,
                        "Cannot enable ASE: existing ingest pipeline [" + pipelineName + "] has conflicts: " + String.join("; ", conflicts)
                    );
                    return;
                }
            }
            next.run();
        }, e -> {
            // GET failing usually means the pipeline does not exist despite the setting — nothing to conflict with.
            next.run();
        }));
    }

    @SuppressWarnings("unchecked")
    private void preCheckSearchPipelineAndProceed(
        String index,
        String mappingSource,
        List<String[]> validated,
        Set<String> sourceFields,
        String existingFinalPipeline,
        String existingSearchPipeline,
        NodeClient client,
        RestChannel channel
    ) {
        if (existingSearchPipeline != null && !"_none".equals(existingSearchPipeline)) {
            client.admin()
                .cluster()
                .execute(
                    GetSearchPipelineAction.INSTANCE,
                    new GetSearchPipelineRequest(existingSearchPipeline),
                    ActionListener.wrap(searchResp -> {
                        if (searchResp.pipelines() != null && !searchResp.pipelines().isEmpty()) {
                            Map<String, Object> existingConfig = searchResp.pipelines().get(0).getConfigAsMap();
                            List<String> conflicts = PipelineMergeUtil.checkSearchPipelineConflicts(existingConfig);
                            if (!conflicts.isEmpty()) {
                                sendError(
                                    channel,
                                    RestStatus.CONFLICT,
                                    "Cannot enable ASE: existing search pipeline ["
                                        + existingSearchPipeline
                                        + "] has conflicts: "
                                        + String.join("; ", conflicts)
                                );
                                return;
                            }
                        }
                        // All pre-checks passed — proceed with PutMapping
                        proceedWithPutMapping(
                            index,
                            mappingSource,
                            validated,
                            existingFinalPipeline,
                            existingSearchPipeline,
                            client,
                            channel
                        );
                    }, e -> {
                        // Search pipeline fetch failed — proceed
                        proceedWithPutMapping(
                            index,
                            mappingSource,
                            validated,
                            existingFinalPipeline,
                            existingSearchPipeline,
                            client,
                            channel
                        );
                    })
                );
        } else {
            // No existing search pipeline — proceed directly
            proceedWithPutMapping(index, mappingSource, validated, existingFinalPipeline, existingSearchPipeline, client, channel);
        }
    }

    /**
     * Execute the irreversible PutMapping, then create/merge pipelines.
     */
    private void proceedWithPutMapping(
        String index,
        String mappingSource,
        List<String[]> validated,
        String existingFinalPipeline,
        String existingSearchPipeline,
        NodeClient client,
        RestChannel channel
    ) {
        PutMappingRequest pmr = new PutMappingRequest(index);
        pmr.source(mappingSource, XContentType.JSON);
        client.admin().indices().putMapping(pmr, ActionListener.wrap(resp -> {
            if (!resp.isAcknowledged()) {
                sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "PutMapping not acknowledged");
                return;
            }
            // PutMapping committed. Now create/merge pipelines.
            mergeOrCreateIngestPipeline(index, validated, existingFinalPipeline, existingSearchPipeline, client, channel);
        }, e -> sendError(channel, RestStatus.BAD_REQUEST, "PutMapping failed: " + e.getMessage())));
    }

    /**
     * Merge ASE ingest processors into the index's existing FINAL pipeline, or create one.
     * If a final pipeline is already set, append ASE's set/rename processors to the end of it.
     * Otherwise create a fresh ASE-owned pipeline and attach it as index.final_pipeline.
     * The customer's default_pipeline is never modified.
     */
    @SuppressWarnings("unchecked")
    private void mergeOrCreateIngestPipeline(
        String index,
        List<String[]> validated,
        String existingFinalPipeline,
        String existingSearchPipeline,
        NodeClient client,
        RestChannel channel
    ) {
        // Build ASE ingest processors to append
        List<Map<String, Object>> aseIngestProcessors = buildAseIngestProcessors(validated, false);

        if (existingFinalPipeline != null && !"_none".equals(existingFinalPipeline)) {
            // Merge into existing pipeline
            client.admin()
                .cluster()
                .execute(GetPipelineAction.INSTANCE, new GetPipelineRequest(existingFinalPipeline), ActionListener.wrap(resp -> {
                    String targetPipeName = existingFinalPipeline;
                    if (resp.isFound() && resp.pipelines() != null && !resp.pipelines().isEmpty()) {
                        Map<String, Object> existing = resp.pipelines().get(0).getConfigAsMap();
                        List<Map<String, Object>> processors = existing.get("processors") instanceof List<?> l
                            ? new ArrayList<>((List<Map<String, Object>>) l)
                            : new ArrayList<>();
                        // Remove any existing ASE-managed processors before appending fresh ones
                        processors.removeIf(p -> PipelineMergeUtil.isAseManaged(p));
                        processors.addAll(aseIngestProcessors);
                        Map<String, Object> merged = new LinkedHashMap<>(existing);
                        merged.put("processors", processors);
                        putIngestPipelineConfig(
                            targetPipeName,
                            merged,
                            client,
                            channel,
                            () -> mergeOrCreateSearchPipeline(index, validated, existingSearchPipeline, targetPipeName, client, channel)
                        );
                    } else {
                        // Pipeline doesn't actually exist despite setting — create fresh
                        createFreshIngestPipeline(index, validated, existingSearchPipeline, client, channel);
                    }
                }, e -> createFreshIngestPipeline(index, validated, existingSearchPipeline, client, channel)));
        } else {
            // No existing ingest pipeline — create fresh
            createFreshIngestPipeline(index, validated, existingSearchPipeline, client, channel);
        }
    }

    private void createFreshIngestPipeline(
        String index,
        List<String[]> validated,
        String existingSearchPipeline,
        NodeClient client,
        RestChannel channel
    ) {
        String pipeName = index + INGEST_PIPELINE_SUFFIX;
        String body = buildIngestPipelineBody(index, validated, false);
        PutPipelineRequest req = new PutPipelineRequest(pipeName, new BytesArray(body), XContentType.JSON);
        client.admin().cluster().putPipeline(req, ActionListener.wrap(resp -> {
            // Attach as the FINAL pipeline (not default) so it cannot be bypassed by ?pipeline=
            var sr = new org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest(index);
            sr.settings(org.opensearch.common.settings.Settings.builder().put("index.final_pipeline", pipeName).build());
            client.admin().indices().updateSettings(sr, ActionListener.wrap(settResp -> {
                mergeOrCreateSearchPipeline(index, validated, existingSearchPipeline, pipeName, client, channel);
            }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Settings failed: " + e.getMessage())));
        }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Ingest pipeline failed: " + e.getMessage())));
    }

    /**
     * Merge ASE search processors into an existing pipeline, or create a new one.
     * If an existing search pipeline is set, prepend ASE's processors to the beginning.
     * Otherwise, create a fresh ASE-owned pipeline and set it as default.
     */
    @SuppressWarnings("unchecked")
    private void mergeOrCreateSearchPipeline(
        String index,
        List<String[]> validated,
        String existingSearchPipeline,
        String ingestPipeName,
        NodeClient client,
        RestChannel channel
    ) {
        List<Map<String, Object>> aseSearchProcessors = buildAseSearchProcessors(validated);

        if (existingSearchPipeline != null && !"_none".equals(existingSearchPipeline)) {
            // Merge into existing search pipeline
            client.admin()
                .cluster()
                .execute(
                    GetSearchPipelineAction.INSTANCE,
                    new GetSearchPipelineRequest(existingSearchPipeline),
                    ActionListener.wrap(resp -> {
                        if (resp.pipelines() != null && !resp.pipelines().isEmpty()) {
                            Map<String, Object> existingConfig = resp.pipelines().get(0).getConfigAsMap();
                            Map<String, Object> merged = PipelineMergeUtil.buildMergedSearchPipeline(existingConfig, aseSearchProcessors);
                            putSearchPipeline(
                                existingSearchPipeline,
                                merged,
                                client,
                                channel,
                                () -> sendEnableResponse(channel, index, ingestPipeName, existingSearchPipeline)
                            );
                        } else {
                            createFreshSearchPipeline(index, validated, ingestPipeName, client, channel);
                        }
                    }, e -> createFreshSearchPipeline(index, validated, ingestPipeName, client, channel))
                );
        } else {
            createFreshSearchPipeline(index, validated, ingestPipeName, client, channel);
        }
    }

    private void createFreshSearchPipeline(
        String index,
        List<String[]> validated,
        String ingestPipeName,
        NodeClient client,
        RestChannel channel
    ) {
        List<Map<String, Object>> aseSearchProcessors = buildAseSearchProcessors(validated);
        String searchPipeName = index + SEARCH_PIPELINE_SUFFIX;

        Map<String, Object> freshConfig = new LinkedHashMap<>();
        freshConfig.put("request_processors", aseSearchProcessors);
        putSearchPipeline(searchPipeName, freshConfig, client, channel, () -> {
            // Set as default search pipeline
            var sr = new org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest(index);
            sr.settings(org.opensearch.common.settings.Settings.builder().put("index.search.default_pipeline", searchPipeName).build());
            client.admin()
                .indices()
                .updateSettings(
                    sr,
                    ActionListener.wrap(
                        settResp -> sendEnableResponse(channel, index, ingestPipeName, searchPipeName),
                        e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Settings failed: " + e.getMessage())
                    )
                );
        });
    }

    private void sendEnableResponse(RestChannel channel, String index, String ingestPipeName, String searchPipeName) {
        sendResponse(
            channel,
            RestStatus.OK,
            String.format(
                Locale.ROOT,
                "{\"acknowledged\":true,\"index\":\"%s\",\"ingest_pipeline\":\"%s\",\"search_pipeline\":\"%s\",\"state\":\"ENRICHING\"}",
                index,
                ingestPipeName,
                searchPipeName
            )
        );
    }

    // ========================= ASE processor builders =========================

    /**
     * Build the list of ASE ingest processors (set or rename), tagged as ASE-managed.
     */
    private List<Map<String, Object>> buildAseIngestProcessors(List<String[]> fieldPairs, boolean deployMode) {
        List<Map<String, Object>> processors = new ArrayList<>();
        for (String[] fp : fieldPairs) {
            Map<String, Object> procConfig = new LinkedHashMap<>();
            procConfig.put(PipelineMergeUtil.TAG_KEY, PipelineMergeUtil.ASE_MANAGED_TAG);
            Map<String, Object> proc = new LinkedHashMap<>();
            if (deployMode) {
                procConfig.put("field", fp[0]);
                procConfig.put("target_field", fp[1]);
                proc.put("rename", procConfig);
            } else {
                procConfig.put("field", fp[1]);
                procConfig.put("value", "{{" + fp[0] + "}}");
                proc.put("set", procConfig);
            }
            processors.add(proc);
        }
        return processors;
    }

    /**
     * Build the list of ASE search request processors, tagged as ASE-managed.
     */
    private List<Map<String, Object>> buildAseSearchProcessors(List<String[]> validated) {
        // match_to_neural_rewrite_processor
        List<String> fieldNames = new ArrayList<>();
        for (String[] v : validated) {
            fieldNames.add(v[1]); // semantic_field name
        }
        Map<String, Object> rewriteConfig = new LinkedHashMap<>();
        rewriteConfig.put(PipelineMergeUtil.TAG_KEY, PipelineMergeUtil.ASE_MANAGED_TAG);
        rewriteConfig.put("fields", fieldNames);
        Map<String, Object> rewriteProc = new LinkedHashMap<>();
        rewriteProc.put(PipelineMergeUtil.MATCH_TO_NEURAL_REWRITE_PROCESSOR, rewriteConfig);

        // neural_sparse_two_phase_processor
        Map<String, Object> twoPhaseConfig = new LinkedHashMap<>();
        twoPhaseConfig.put(PipelineMergeUtil.TAG_KEY, PipelineMergeUtil.ASE_MANAGED_TAG);
        twoPhaseConfig.put("enabled", true);
        Map<String, Object> twoPhaseProc = new LinkedHashMap<>();
        twoPhaseProc.put(PipelineMergeUtil.NEURAL_SPARSE_TWO_PHASE_PROCESSOR, twoPhaseConfig);

        List<Map<String, Object>> processors = new ArrayList<>();
        processors.add(rewriteProc);
        processors.add(twoPhaseProc);
        return processors;
    }

    /**
     * Check if a processor wrapper is ASE-managed (has tag=ase_managed).
     */
    /**
     * PUT an ingest pipeline given a config map.
     */
    private void putIngestPipelineConfig(
        String pipelineName,
        Map<String, Object> configMap,
        NodeClient client,
        RestChannel channel,
        Runnable onSuccess
    ) {
        try {
            var builder = org.opensearch.core.xcontent.XContentBuilder.builder(XContentType.JSON.xContent());
            builder.map(configMap);
            var bytes = org.opensearch.core.common.bytes.BytesReference.bytes(builder);
            PutPipelineRequest req = new PutPipelineRequest(pipelineName, bytes, XContentType.JSON);
            client.admin().cluster().putPipeline(req, ActionListener.wrap(r -> {
                if (r.isAcknowledged()) onSuccess.run();
                else sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Ingest pipeline not acknowledged");
            }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Ingest pipeline update failed: " + e.getMessage())));
        } catch (Exception e) {
            sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Serialize failed: " + e.getMessage());
        }
    }

    // ========================= deploy =========================

    /**
     * Deploy: swap ASE ingest processors from enrich mode (set/copy) to deploy mode (rename).
     * Discovers the pipeline from index settings rather than assuming a hardcoded name.
     */
    @SuppressWarnings("unchecked")
    private void handleDeploy(String index, Map<String, Object> body, NodeClient client, RestChannel channel) {
        IndexMetadata indexMetadata = clusterService.state().metadata().index(index);
        if (indexMetadata == null) {
            sendError(channel, RestStatus.NOT_FOUND, "Index [" + index + "] does not exist");
            return;
        }

        String finalPipeline = indexMetadata.getSettings().get("index.final_pipeline");
        if (!isRealPipeline(finalPipeline)) {
            sendError(channel, RestStatus.BAD_REQUEST, "No final_pipeline configured on index [" + index + "]. Enable ASE first.");
            return;
        }

        // GET the ingest pipeline, swap set→rename, PUT back
        client.admin().cluster().execute(GetPipelineAction.INSTANCE, new GetPipelineRequest(finalPipeline), ActionListener.wrap(resp -> {
            if (!resp.isFound() || resp.pipelines() == null || resp.pipelines().isEmpty()) {
                sendError(channel, RestStatus.BAD_REQUEST, "Pipeline [" + finalPipeline + "] not found. Enable ASE first.");
                return;
            }
            Map<String, Object> pipelineConfig = resp.pipelines().get(0).getConfigAsMap();
            List<Map<String, Object>> processors = pipelineConfig.get("processors") instanceof List<?> l
                ? new ArrayList<>((List<Map<String, Object>>) l)
                : new ArrayList<>();

            // Verify there are ASE-managed processors to swap
            boolean hasAse = processors.stream().anyMatch(PipelineMergeUtil::isAseManaged);
            if (!hasAse) {
                sendError(
                    channel,
                    RestStatus.BAD_REQUEST,
                    "Pipeline [" + finalPipeline + "] has no ASE-managed processors. Enable ASE first."
                );
                return;
            }

            List<Map<String, Object>> swapped = PipelineMergeUtil.swapToDeployMode(processors);
            Map<String, Object> updatedPipeline = new LinkedHashMap<>(pipelineConfig);
            updatedPipeline.put("processors", swapped);

            putIngestPipelineConfig(finalPipeline, updatedPipeline, client, channel, () -> {
                // Also add field_replacement_processor to search pipeline for query routing
                List<String[]> fp = extractFieldPairsFromProcessors(swapped);
                if (fp != null && !fp.isEmpty()) {
                    addFieldReplacementProcessor(
                        index,
                        fp,
                        client,
                        channel,
                        () -> sendResponse(
                            channel,
                            RestStatus.OK,
                            "{\"acknowledged\":true,\"index\":\"" + index + "\",\"state\":\"DEPLOYED\"}"
                        )
                    );
                } else {
                    sendResponse(channel, RestStatus.OK, "{\"acknowledged\":true,\"index\":\"" + index + "\",\"state\":\"DEPLOYED\"}");
                }
            });
        }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Deploy failed: " + e.getMessage())));
    }

    // ========================= rollback =========================

    /**
     * Rollback: swap ASE ingest processors from deploy mode (rename) back to enrich mode (set/copy).
     * Discovers the pipeline from index settings rather than assuming a hardcoded name.
     */
    @SuppressWarnings("unchecked")
    private void handleRollback(String index, Map<String, Object> body, NodeClient client, RestChannel channel) {
        IndexMetadata indexMetadata = clusterService.state().metadata().index(index);
        if (indexMetadata == null) {
            sendError(channel, RestStatus.NOT_FOUND, "Index [" + index + "] does not exist");
            return;
        }

        String finalPipeline = indexMetadata.getSettings().get("index.final_pipeline");
        if (!isRealPipeline(finalPipeline)) {
            sendError(channel, RestStatus.BAD_REQUEST, "No final_pipeline configured on index [" + index + "]. Nothing to rollback.");
            return;
        }

        // GET the ingest pipeline, swap rename→set, PUT back
        client.admin().cluster().execute(GetPipelineAction.INSTANCE, new GetPipelineRequest(finalPipeline), ActionListener.wrap(resp -> {
            if (!resp.isFound() || resp.pipelines() == null || resp.pipelines().isEmpty()) {
                sendError(channel, RestStatus.BAD_REQUEST, "Pipeline [" + finalPipeline + "] not found.");
                return;
            }
            Map<String, Object> pipelineConfig = resp.pipelines().get(0).getConfigAsMap();
            List<Map<String, Object>> processors = pipelineConfig.get("processors") instanceof List<?> l
                ? new ArrayList<>((List<Map<String, Object>>) l)
                : new ArrayList<>();

            boolean hasAse = processors.stream().anyMatch(PipelineMergeUtil::isAseManaged);
            if (!hasAse) {
                sendError(channel, RestStatus.BAD_REQUEST, "Pipeline [" + finalPipeline + "] has no ASE-managed processors.");
                return;
            }

            List<Map<String, Object>> swapped = PipelineMergeUtil.swapToEnrichMode(processors);
            Map<String, Object> updatedPipeline = new LinkedHashMap<>(pipelineConfig);
            updatedPipeline.put("processors", swapped);

            putIngestPipelineConfig(finalPipeline, updatedPipeline, client, channel, () -> {
                // Remove field_replacement_processor from search pipeline
                removeFieldReplacementProcessor(
                    index,
                    client,
                    channel,
                    () -> sendResponse(
                        channel,
                        RestStatus.OK,
                        "{\"acknowledged\":true,\"index\":\"" + index + "\",\"state\":\"ENRICHING\"}"
                    )
                );
            });
        }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Rollback failed: " + e.getMessage())));
    }

    // ========================= disable =========================

    /**
     * Disable: remove all ASE processors from both ingest and search pipelines.
     * If ASE owns the entire pipeline (all processors are ASE-managed), delete it and clear the setting.
     * If ASE merged into a customer pipeline, surgically remove only ASE processors and PUT back.
     */
    @SuppressWarnings("unchecked")
    private void handleDisable(String index, Map<String, Object> body, NodeClient client, RestChannel channel) {
        IndexMetadata indexMetadata = clusterService.state().metadata().index(index);
        if (indexMetadata == null) {
            sendError(channel, RestStatus.NOT_FOUND, "Index [" + index + "] does not exist");
            return;
        }

        String finalPipeline = indexMetadata.getSettings().get("index.final_pipeline");
        String searchPipeline = indexMetadata.getSettings().get("index.search.default_pipeline");

        // Step 1: Clean up the ingest pipeline
        disableIngestPipeline(index, finalPipeline, client, channel, () -> {
            // Step 2: Clean up the search pipeline
            disableSearchPipeline(index, searchPipeline, client, channel, () -> {
                // Step 3: Update mapping status to DISABLED
                updateMappingStatus(
                    index,
                    "DISABLED",
                    client,
                    channel,
                    () -> sendResponse(channel, RestStatus.OK, "{\"acknowledged\":true,\"index\":\"" + index + "\",\"state\":\"DISABLED\"}")
                );
            });
        });
    }

    /**
     * Remove ASE processors from the ingest (final) pipeline.
     * If the pipeline becomes empty or was entirely ASE-owned, delete it and clear the setting.
     */
    @SuppressWarnings("unchecked")
    private void disableIngestPipeline(String index, String pipelineName, NodeClient client, RestChannel channel, Runnable onSuccess) {
        if (!isRealPipeline(pipelineName)) {
            onSuccess.run();
            return;
        }

        client.admin().cluster().execute(GetPipelineAction.INSTANCE, new GetPipelineRequest(pipelineName), ActionListener.wrap(resp -> {
            if (!resp.isFound() || resp.pipelines() == null || resp.pipelines().isEmpty()) {
                onSuccess.run();
                return;
            }

            Map<String, Object> pipelineConfig = resp.pipelines().get(0).getConfigAsMap();
            List<Map<String, Object>> processors = pipelineConfig.get("processors") instanceof List<?> l
                ? new ArrayList<>((List<Map<String, Object>>) l)
                : new ArrayList<>();

            List<Map<String, Object>> remaining = PipelineMergeUtil.removeAseProcessors(processors);

            if (remaining.isEmpty()) {
                // ASE owned the entire pipeline (or it's now empty) — delete pipeline + clear setting
                deletePipelineAndClearSetting(index, pipelineName, "index.final_pipeline", client, channel, onSuccess);
            } else {
                // Customer processors remain — PUT the trimmed pipeline back
                Map<String, Object> updatedPipeline = new LinkedHashMap<>(pipelineConfig);
                updatedPipeline.put("processors", remaining);
                putIngestPipelineConfig(pipelineName, updatedPipeline, client, channel, onSuccess);
            }
        }, e -> {
            // Pipeline fetch failed — proceed anyway
            onSuccess.run();
        }));
    }

    /**
     * Remove ASE processors from the search pipeline.
     * If the pipeline becomes empty or was entirely ASE-owned, delete it and clear the setting.
     */
    @SuppressWarnings("unchecked")
    private void disableSearchPipeline(String index, String pipelineName, NodeClient client, RestChannel channel, Runnable onSuccess) {
        if (!isRealPipeline(pipelineName)) {
            onSuccess.run();
            return;
        }

        client.admin()
            .cluster()
            .execute(GetSearchPipelineAction.INSTANCE, new GetSearchPipelineRequest(pipelineName), ActionListener.wrap(resp -> {
                if (resp.pipelines() == null || resp.pipelines().isEmpty()) {
                    onSuccess.run();
                    return;
                }

                Map<String, Object> pipelineConfig = resp.pipelines().get(0).getConfigAsMap();
                List<Map<String, Object>> requestProcessors = pipelineConfig.get("request_processors") instanceof List<?> l
                    ? new ArrayList<>((List<Map<String, Object>>) l)
                    : new ArrayList<>();

                List<Map<String, Object>> remaining = PipelineMergeUtil.removeAseProcessors(requestProcessors);

                // Also remove field_replacement_processor (deploy-mode artifact)
                remaining.removeIf(p -> p.containsKey(FIELD_REPLACEMENT_PROCESSOR_TYPE));

                // Check if pipeline has any other content (response_processors, phase_results_processors)
                boolean hasOtherContent = pipelineConfig.containsKey("response_processors")
                    || pipelineConfig.containsKey("phase_results_processors");

                if (remaining.isEmpty() && !hasOtherContent) {
                    // Pipeline is now empty — delete it and clear setting
                    deleteSearchPipelineAndClearSetting(index, pipelineName, client, channel, onSuccess);
                } else {
                    // PUT the trimmed pipeline back
                    Map<String, Object> updatedPipeline = new LinkedHashMap<>(pipelineConfig);
                    updatedPipeline.put("request_processors", remaining);
                    putSearchPipeline(pipelineName, updatedPipeline, client, channel, onSuccess);
                }
            }, e -> { onSuccess.run(); }));
    }

    /**
     * Delete an ingest pipeline and clear the corresponding index setting.
     */
    private void deletePipelineAndClearSetting(
        String index,
        String pipelineName,
        String settingKey,
        NodeClient client,
        RestChannel channel,
        Runnable onSuccess
    ) {
        var deleteReq = new org.opensearch.action.ingest.DeletePipelineRequest(pipelineName);
        client.admin().cluster().deletePipeline(deleteReq, ActionListener.wrap(resp -> {
            // Clear the index setting
            var sr = new org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest(index);
            sr.settings(org.opensearch.common.settings.Settings.builder().put(settingKey, "_none").build());
            client.admin().indices().updateSettings(sr, ActionListener.wrap(settResp -> onSuccess.run(), e -> {
                log.warn("Failed to clear setting {} on index {}: {}", settingKey, index, e.getMessage());
                onSuccess.run(); // Pipeline deleted, setting clear failed — still proceed
            }));
        }, e -> {
            log.warn("Failed to delete pipeline {}: {}", pipelineName, e.getMessage());
            onSuccess.run(); // Best effort
        }));
    }

    /**
     * Delete a search pipeline and clear the search.default_pipeline setting.
     */
    private void deleteSearchPipelineAndClearSetting(
        String index,
        String pipelineName,
        NodeClient client,
        RestChannel channel,
        Runnable onSuccess
    ) {
        var deleteReq = new org.opensearch.action.search.DeleteSearchPipelineRequest(pipelineName);
        client.admin()
            .cluster()
            .execute(org.opensearch.action.search.DeleteSearchPipelineAction.INSTANCE, deleteReq, ActionListener.wrap(resp -> {
                var sr = new org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest(index);
                sr.settings(org.opensearch.common.settings.Settings.builder().put("index.search.default_pipeline", "_none").build());
                client.admin().indices().updateSettings(sr, ActionListener.wrap(settResp -> onSuccess.run(), e -> {
                    log.warn("Failed to clear search pipeline setting on index {}: {}", index, e.getMessage());
                    onSuccess.run();
                }));
            }, e -> {
                log.warn("Failed to delete search pipeline {}: {}", pipelineName, e.getMessage());
                onSuccess.run();
            }));
    }

    /**
     * Update all semantic field mappings to a given status (ENABLED, DISABLED).
     */
    @SuppressWarnings("unchecked")
    private void updateMappingStatus(String index, String status, NodeClient client, RestChannel channel, Runnable onSuccess) {
        IndexMetadata im = clusterService.state().metadata().index(index);
        if (im == null || im.mapping() == null) {
            onSuccess.run();
            return;
        }
        Map<String, Object> props = (Map<String, Object>) im.mapping().sourceAsMap().get("properties");
        if (props == null) {
            onSuccess.run();
            return;
        }

        // Find all semantic fields
        StringBuilder mb = new StringBuilder("{\"properties\":{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            Map<String, Object> fieldDef = (Map<String, Object>) entry.getValue();
            if (!"semantic".equals(fieldDef.get("type"))) continue;
            if (!first) mb.append(",");
            mb.append(String.format(Locale.ROOT, "\"%s\":{\"type\":\"semantic\",\"status\":\"%s\"}", entry.getKey(), status));
            first = false;
        }
        mb.append("}}");

        if (first) {
            // No semantic fields found
            onSuccess.run();
            return;
        }

        PutMappingRequest pmr = new PutMappingRequest(index);
        pmr.source(mb.toString(), XContentType.JSON);
        client.admin().indices().putMapping(pmr, ActionListener.wrap(resp -> onSuccess.run(), e -> {
            log.warn("Failed to update mapping status on index {}: {}", index, e.getMessage());
            onSuccess.run(); // Best effort
        }));
    }

    // ========================= list =========================

    @SuppressWarnings("unchecked")
    private void handleList(String index, NodeClient client, RestChannel channel) {
        IndexMetadata im = clusterService.state().metadata().index(index);
        if (im == null) {
            sendError(channel, RestStatus.NOT_FOUND, "Index not found");
            return;
        }
        Map<String, Object> props = im.mapping() != null ? (Map<String, Object>) im.mapping().sourceAsMap().get("properties") : Map.of();

        String pipeName = index + INGEST_PIPELINE_SUFFIX;
        client.admin().cluster().getPipeline(new GetPipelineRequest(pipeName), new ActionListener<GetPipelineResponse>() {
            public void onResponse(GetPipelineResponse r) {
                buildListResponse(channel, index, props, hasRename(r, pipeName));
            }

            public void onFailure(Exception e) {
                buildListResponse(channel, index, props, false);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private boolean hasRename(GetPipelineResponse r, String name) {
        if (r == null || r.pipelines() == null) return false;
        for (var c : r.pipelines()) {
            if (name.equals(c.getId())) {
                Object procs = c.getConfigAsMap().get("processors");
                if (procs instanceof List<?> l) {
                    for (Object p : l) {
                        if (p instanceof Map<?, ?> m && m.containsKey("rename")) return true;
                    }
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void buildListResponse(RestChannel channel, String index, Map<String, Object> props, boolean hasRename) {
        StringBuilder sb = new StringBuilder("{\"index\":\"" + index + "\",\"fields\":[");
        boolean first = true;
        for (var e : props.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> fd = (Map<String, Object>) e.getValue();
            if (!"semantic".equals(fd.get("type"))) continue;
            String of = (String) fd.get("original_field");
            if (of == null) continue;
            String status = (String) fd.getOrDefault("status", "ENABLED");
            String state = "DISABLED".equalsIgnoreCase(status) ? "DISABLED" : (hasRename ? "DEPLOYED" : "ENRICHING");
            if (!first) sb.append(",");
            sb.append(
                String.format(Locale.ROOT, "{\"original_field\":\"%s\",\"semantic_field\":\"%s\",\"state\":\"%s\"}", of, e.getKey(), state)
            );
            first = false;
        }
        sb.append("]}");
        sendResponse(channel, RestStatus.OK, sb.toString());
    }

    // ========================= Pipeline builders =========================

    private String buildIngestPipelineBody(String index, List<String[]> fieldPairs, boolean deployMode) {
        StringBuilder procs = new StringBuilder("[");
        boolean first = true;
        for (String[] fp : fieldPairs) {
            if (!first) procs.append(",");
            if (deployMode) {
                // rename: moves original_field → semantic_field
                procs.append(String.format(Locale.ROOT, "{\"rename\":{\"field\":\"%s\",\"target_field\":\"%s\"}}", fp[0], fp[1]));
            } else {
                // set with mustache template: copies original_field → semantic_field (preserves original)
                procs.append(String.format(Locale.ROOT, "{\"set\":{\"field\":\"%s\",\"value\":\"{{%s}}\"}}", fp[1], fp[0]));
            }
            first = false;
        }
        procs.append("]");
        String desc = deployMode ? "ASE ingest (DEPLOYED)" : "ASE ingest (ENRICHING)";
        return String.format(Locale.ROOT, "{\"description\":\"%s for %s\",\"processors\":%s}", desc, index, procs);
    }

    private List<String[]> extractFieldPairs(Map<String, Object> body, RestChannel channel) {
        List<Map<String, Object>> fields = extractFields(body);
        if (fields == null || fields.isEmpty()) {
            sendError(channel, RestStatus.BAD_REQUEST, "fields required");
            return null;
        }
        List<String[]> pairs = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            String o = (String) f.get("original_field");
            String s = (String) f.get("semantic_field");
            if (o == null || s == null) {
                sendError(channel, RestStatus.BAD_REQUEST, "original_field and semantic_field required");
                return null;
            }
            pairs.add(new String[] { o, s });
        }
        return pairs;
    }

    /**
     * Extract field pairs [original_field, semantic_field] from ASE-managed rename processors in a pipeline.
     * Used by deploy to know which fields to set up in the search pipeline's field_replacement_processor.
     */
    @SuppressWarnings("unchecked")
    private List<String[]> extractFieldPairsFromProcessors(List<Map<String, Object>> processors) {
        List<String[]> pairs = new ArrayList<>();
        for (Map<String, Object> proc : processors) {
            if (!PipelineMergeUtil.isAseManaged(proc)) continue;
            if (proc.containsKey("rename")) {
                Map<String, Object> config = (Map<String, Object>) proc.get("rename");
                String origField = (String) config.get("field");
                String semField = (String) config.get("target_field");
                if (origField != null && semField != null) {
                    pairs.add(new String[] { origField, semField });
                }
            }
        }
        return pairs;
    }

    // ========================= Search pipeline helpers =========================

    @SuppressWarnings("unchecked")
    private void addFieldReplacementProcessor(String index, List<String[]> fp, NodeClient client, RestChannel channel, Runnable onSuccess) {
        // Discover the search pipeline from index settings (not hardcoded name)
        IndexMetadata im = clusterService.state().metadata().index(index);
        String name = (im != null) ? im.getSettings().get("index.search.default_pipeline") : null;
        if (!isRealPipeline(name)) {
            // Fallback to ASE-owned pipeline name convention
            name = index + SEARCH_PIPELINE_SUFFIX;
        }
        final String pipelineName = name;
        client.admin()
            .cluster()
            .execute(GetSearchPipelineAction.INSTANCE, new GetSearchPipelineRequest(pipelineName), ActionListener.wrap(gr -> {
                if (gr.pipelines() == null || gr.pipelines().isEmpty()) {
                    sendError(channel, RestStatus.BAD_REQUEST, "Search pipeline [" + pipelineName + "] not found");
                    return;
                }
                Map<String, Object> cfg = new LinkedHashMap<>(gr.pipelines().get(0).getConfigAsMap());
                Map<String, Object> fm = new LinkedHashMap<>();
                for (String[] f : fp)
                    fm.put(f[0], f[1]);
                Map<String, Object> frc = Map.of("field_map", fm);
                List<Map<String, Object>> rp = cfg.get("request_processors") instanceof List<?> l
                    ? new ArrayList<>((List<Map<String, Object>>) l)
                    : new ArrayList<>();
                rp.removeIf(p -> p.containsKey(FIELD_REPLACEMENT_PROCESSOR_TYPE));
                rp.add(0, Map.of(FIELD_REPLACEMENT_PROCESSOR_TYPE, frc));
                cfg.put("request_processors", rp);
                putSearchPipeline(pipelineName, cfg, client, channel, onSuccess);
            }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Get search pipeline failed: " + e.getMessage())));
    }

    @SuppressWarnings("unchecked")
    private void removeFieldReplacementProcessor(String index, NodeClient client, RestChannel channel, Runnable onSuccess) {
        // Discover the search pipeline from index settings (not hardcoded name)
        IndexMetadata im = clusterService.state().metadata().index(index);
        String name = (im != null) ? im.getSettings().get("index.search.default_pipeline") : null;
        if (!isRealPipeline(name)) {
            name = index + SEARCH_PIPELINE_SUFFIX;
        }
        final String pipelineName = name;
        client.admin()
            .cluster()
            .execute(GetSearchPipelineAction.INSTANCE, new GetSearchPipelineRequest(pipelineName), ActionListener.wrap(gr -> {
                if (gr.pipelines() == null || gr.pipelines().isEmpty()) {
                    onSuccess.run();
                    return;
                }
                Map<String, Object> cfg = new LinkedHashMap<>(gr.pipelines().get(0).getConfigAsMap());
                if (cfg.get("request_processors") instanceof List<?> l) {
                    List<Map<String, Object>> rp = new ArrayList<>((List<Map<String, Object>>) l);
                    rp.removeIf(p -> p.containsKey(FIELD_REPLACEMENT_PROCESSOR_TYPE));
                    cfg.put("request_processors", rp);
                }
                putSearchPipeline(pipelineName, cfg, client, channel, onSuccess);
            }, e -> onSuccess.run()));
    }

    private void putSearchPipeline(String name, Map<String, Object> cfg, NodeClient client, RestChannel channel, Runnable onSuccess) {
        try {
            var builder = org.opensearch.core.xcontent.XContentBuilder.builder(XContentType.JSON.xContent());
            builder.map(cfg);
            var bytes = org.opensearch.core.common.bytes.BytesReference.bytes(builder);
            client.admin()
                .cluster()
                .execute(
                    PutSearchPipelineAction.INSTANCE,
                    new PutSearchPipelineRequest(name, bytes, XContentType.JSON),
                    ActionListener.wrap(r -> {
                        if (r.isAcknowledged()) onSuccess.run();
                        else sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Not acknowledged");
                    }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Search pipeline update failed: " + e.getMessage()))
                );
        } catch (Exception e) {
            sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Serialize failed: " + e.getMessage());
        }
    }

    // ========================= Error/Response helpers =========================

    private void sendError(RestChannel ch, RestStatus st, String msg) {
        try {
            ch.sendResponse(
                new BytesRestResponse(
                    st,
                    "application/json",
                    String.format(Locale.ROOT, "{\"error\":{\"reason\":\"%s\"},\"status\":%d}", msg, st.getStatus())
                )
            );
        } catch (Exception e) {
            log.error("Send error failed", e);
        }
    }

    private void sendResponse(RestChannel ch, RestStatus st, String body) {
        try {
            ch.sendResponse(new BytesRestResponse(st, "application/json", body));
        } catch (Exception e) {
            log.error("Send response failed", e);
        }
    }
}
