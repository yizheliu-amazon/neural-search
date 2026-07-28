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
import org.opensearch.action.search.GetSearchPipelineResponse;
import org.opensearch.action.search.PutSearchPipelineAction;
import org.opensearch.action.search.PutSearchPipelineRequest;
import org.opensearch.action.support.clustermanager.AcknowledgedResponse;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.MappingMetadata;
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
import org.opensearch.search.pipeline.PipelineConfiguration;
import org.opensearch.transport.client.node.NodeClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * REST handler for semantic field enable/disable APIs.
 *
 * POST /_plugins/_neural/semantic/{index}/enable_semantic_enrichment
 * POST /_plugins/_neural/semantic/{index}/disable_semantic_enrichment
 * POST /_plugins/_neural/semantic/{index}/deploy_semantic_enrichment
 * POST /_plugins/_neural/semantic/{index}/rollback_semantic_enrichment
 * GET  /_plugins/_neural/semantic/{index}/list_semantic_enrichment
 *
 * All POST APIs accept a "fields" array for batch operations. Each entry has
 * original_field + semantic_field (+ optional language / model_type).
 */
public class RestSemanticEnableHandler extends BaseRestHandler {

    private static final Logger log = LogManager.getLogger(RestSemanticEnableHandler.class);
    private static final String SEMANTIC_ENABLE_ACTION = "neural_semantic_enable_action";
    private static final String SEARCH_PIPELINE_SUFFIX = "-semantic-search-pipeline";
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

        if (path.endsWith("/list_semantic_enrichment")) {
            return channel -> handleList(index, client, channel);
        }

        Map<String, Object> body = request.contentParser().map();

        if (path.endsWith("/enable_semantic_enrichment")) {
            return channel -> handleEnableEnrichment(index, body, client, channel);
        } else if (path.endsWith("/disable_semantic_enrichment")) {
            return channel -> handleDisableEnrichment(index, body, client, channel);
        } else if (path.endsWith("/deploy_semantic_enrichment")) {
            return channel -> handleDeploy(index, body, client, channel);
        } else if (path.endsWith("/rollback_semantic_enrichment")) {
            return channel -> handleRollback(index, body, client, channel);
        } else {
            return channel -> channel.sendResponse(new BytesRestResponse(RestStatus.BAD_REQUEST, "Unknown action: " + path));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractFields(Map<String, Object> body) {
        Object fieldsObj = body.get("fields");
        if (fieldsObj instanceof List<?>) {
            return (List<Map<String, Object>>) fieldsObj;
        }
        // Support single-field shorthand (backward compat)
        if (body.containsKey("original_field")) {
            return List.of(body);
        }
        return null;
    }

    // ========================= enable_semantic_enrichment =========================

    @SuppressWarnings("unchecked")
    private void handleEnableEnrichment(String index, Map<String, Object> body, NodeClient client, RestChannel channel) {
        List<Map<String, Object>> fields = extractFields(body);
        if (fields == null || fields.isEmpty()) {
            sendError(channel, RestStatus.BAD_REQUEST, "Request must contain 'fields' array or original_field/semantic_field params");
            return;
        }

        // Validate index exists
        IndexMetadata indexMetadata = clusterService.state().metadata().index(index);
        if (indexMetadata == null) {
            sendError(channel, RestStatus.NOT_FOUND, "Index [" + index + "] does not exist");
            return;
        }

        MappingMetadata mappingMetadata = indexMetadata.mapping();
        if (mappingMetadata == null) {
            sendError(channel, RestStatus.BAD_REQUEST, "Index [" + index + "] has no mapping");
            return;
        }

        Map<String, Object> mappingMap = mappingMetadata.sourceAsMap();
        Map<String, Object> properties = (Map<String, Object>) mappingMap.get("properties");
        if (properties == null) {
            sendError(channel, RestStatus.BAD_REQUEST, "Index [" + index + "] has no properties in mapping");
            return;
        }

        // Check for non-ASE pipeline conflict
        String currentPipeline = indexMetadata.getSettings().get("index.search.default_pipeline");
        if (currentPipeline != null && !currentPipeline.endsWith(SEARCH_PIPELINE_SUFFIX)) {
            sendError(
                channel,
                RestStatus.BAD_REQUEST,
                "Index [" + index + "] already has a non-ASE search pipeline [" + currentPipeline + "]"
            );
            return;
        }

        // Validate all fields first
        StringBuilder mappingBuilder = new StringBuilder("{\"properties\":{");
        boolean first = true;
        List<String[]> validatedFields = new ArrayList<>();

        for (Map<String, Object> fieldSpec : fields) {
            String originalField = (String) fieldSpec.get("original_field");
            String semanticField = (String) fieldSpec.get("semantic_field");
            String language = (String) fieldSpec.getOrDefault("language", "ENGLISH");
            String modelType = (String) fieldSpec.getOrDefault("model_type", "SPARSE");

            if (originalField == null || originalField.isEmpty()) {
                sendError(channel, RestStatus.BAD_REQUEST, "original_field is required for each field entry");
                return;
            }
            if (semanticField == null || semanticField.isEmpty()) {
                sendError(channel, RestStatus.BAD_REQUEST, "semantic_field is required for each field entry");
                return;
            }

            // Validate original_field exists
            Object originalFieldDef = properties.get(originalField);
            if (originalFieldDef == null) {
                sendError(
                    channel,
                    RestStatus.BAD_REQUEST,
                    "original_field [" + originalField + "] does not exist in index [" + index + "]"
                );
                return;
            }

            // Validate type compatibility
            if (originalFieldDef instanceof Map) {
                String originalType = (String) ((Map<String, Object>) originalFieldDef).get("type");
                if (originalType != null && !COMPATIBLE_TYPES.contains(originalType)) {
                    sendError(
                        channel,
                        RestStatus.BAD_REQUEST,
                        "original_field ["
                            + originalField
                            + "] has type ["
                            + originalType
                            + "] which is not compatible. Compatible types: "
                            + COMPATIBLE_TYPES
                    );
                    return;
                }
            }

            // Check no OTHER semantic field already enriches this original_field.
            // Skip the target semantic field itself so that re-enabling an existing (e.g. previously
            // DISABLED) enrichment is allowed — the field legitimately already has this original_field.
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (entry.getKey().equals(semanticField)) {
                    continue;
                }
                if (entry.getValue() instanceof Map) {
                    Map<String, Object> fieldDef = (Map<String, Object>) entry.getValue();
                    if ("semantic".equals(fieldDef.get("type"))) {
                        String existingOriginal = (String) fieldDef.get("original_field");
                        if (originalField.equals(existingOriginal)) {
                            sendError(
                                channel,
                                RestStatus.BAD_REQUEST,
                                "original_field [" + originalField + "] is already enriched by semantic field [" + entry.getKey() + "]"
                            );
                            return;
                        }
                    }
                }
            }

            // Check semantic_field doesn't already exist as non-semantic
            Object existingSemantic = properties.get(semanticField);
            if (existingSemantic instanceof Map) {
                String existingType = (String) ((Map<String, Object>) existingSemantic).get("type");
                if (existingType != null && !"semantic".equals(existingType)) {
                    sendError(
                        channel,
                        RestStatus.BAD_REQUEST,
                        "semantic_field [" + semanticField + "] already exists with type [" + existingType + "]"
                    );
                    return;
                }
            }

            // Build mapping fragment
            if (!first) mappingBuilder.append(",");
            // Explicitly set status=ENABLED so that calling enable on a previously DISABLED field
            // re-enables it (the mapper preserves existing status when the incoming value is null).
            mappingBuilder.append(
                String.format(
                    Locale.ROOT,
                    "\"%s\":{\"type\":\"semantic\",\"original_field\":\"%s\",\"language\":\"%s\",\"model_type\":\"%s\",\"status\":\"ENABLED\"}",
                    semanticField,
                    originalField,
                    language.toUpperCase(Locale.ROOT),
                    modelType.toUpperCase(Locale.ROOT)
                )
            );
            first = false;
            validatedFields.add(new String[] { originalField, semanticField, language, modelType });
        }
        mappingBuilder.append("}}");

        // Execute PutMapping for all fields at once
        PutMappingRequest putMappingRequest = new PutMappingRequest(index);
        putMappingRequest.source(mappingBuilder.toString(), XContentType.JSON);

        client.admin().indices().putMapping(putMappingRequest, new ActionListener<AcknowledgedResponse>() {
            @Override
            public void onResponse(AcknowledgedResponse response) {
                if (!response.isAcknowledged()) {
                    sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "PutMapping was not acknowledged");
                    return;
                }
                // After mapping is created, create the search pipeline with semantic_search_rewrite_processor
                createSearchPipelineForEnrichment(index, validatedFields, client, channel);
            }

            @Override
            public void onFailure(Exception e) {
                sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Failed to create semantic fields: " + e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void createSearchPipelineForEnrichment(String index, List<String[]> validatedFields, NodeClient client, RestChannel channel) {
        // Read updated mapping to get model_ids
        IndexMetadata updated = clusterService.state().metadata().index(index);
        Map<String, Object> updatedProps = null;
        if (updated != null && updated.mapping() != null) {
            updatedProps = (Map<String, Object>) updated.mapping().sourceAsMap().get("properties");
        }

        // Build field_map with SEMANTIC field name as key (not source field)
        StringJoiner rewriteFieldMapEntries = new StringJoiner(",");
        for (String[] vf : validatedFields) {
            String semanticField = vf[1];
            String modelId = "unknown";
            String modelType = vf[3];
            if (updatedProps != null && updatedProps.get(semanticField) instanceof Map) {
                Object mid = ((Map<String, Object>) updatedProps.get(semanticField)).get("model_id");
                if (mid != null) modelId = mid.toString();
            }
            String targetField = semanticField + "_semantic_info.embedding";
            String type = "SPARSE".equalsIgnoreCase(modelType) ? "sparse" : "dense";
            String analyzer = "SPARSE".equalsIgnoreCase(modelType) ? ",\"analyzer\":\"bert-uncased\"" : "";
            rewriteFieldMapEntries.add(
                String.format(
                    Locale.ROOT,
                    "\"%s\":{\"type\":\"%s\",\"target_field\":\"%s\",\"model_id\":\"%s\"%s}",
                    semanticField,
                    type,
                    targetField,
                    modelId,
                    analyzer
                )
            );
        }

        String pipelineName = index + SEARCH_PIPELINE_SUFFIX;
        String pipelineBody = String.format(
            Locale.ROOT,
            "{\"request_processors\":[{\"semantic_search_rewrite_processor\":{\"field_map\":{%s}}}"
                + ",{\"neural_sparse_two_phase_processor\":{\"enabled\":true}}]}",
            rewriteFieldMapEntries.toString()
        );

        PutSearchPipelineRequest pipelineRequest = new PutSearchPipelineRequest(
            pipelineName,
            new BytesArray(pipelineBody),
            XContentType.JSON
        );

        final Map<String, Object> finalUpdatedProps = updatedProps;
        client.admin().cluster().execute(PutSearchPipelineAction.INSTANCE, pipelineRequest, new ActionListener<AcknowledgedResponse>() {
            @Override
            public void onResponse(AcknowledgedResponse response) {
                if (!response.isAcknowledged()) {
                    sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Failed to create search pipeline");
                    return;
                }
                // Set as default search pipeline
                org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest settingsRequest =
                    new org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest(index);
                settingsRequest.settings(
                    org.opensearch.common.settings.Settings.builder().put("index.search.default_pipeline", pipelineName).build()
                );
                client.admin().indices().updateSettings(settingsRequest, new ActionListener<AcknowledgedResponse>() {
                    @Override
                    public void onResponse(AcknowledgedResponse settingsResp) {
                        StringBuilder respBuilder = new StringBuilder(
                            "{\"acknowledged\":true,\"index\":\"" + index + "\",\"pipeline\":\"" + pipelineName + "\",\"fields\":["
                        );
                        boolean respFirst = true;
                        for (String[] vf : validatedFields) {
                            if (!respFirst) respBuilder.append(",");
                            String modelId = "unknown";
                            if (finalUpdatedProps != null && finalUpdatedProps.get(vf[1]) instanceof Map) {
                                Object mid = ((Map<String, Object>) finalUpdatedProps.get(vf[1])).get("model_id");
                                if (mid != null) modelId = mid.toString();
                            }
                            respBuilder.append(
                                String.format(
                                    Locale.ROOT,
                                    "{\"original_field\":\"%s\",\"semantic_field\":\"%s\",\"model_id\":\"%s\",\"status\":\"ENABLED\"}",
                                    vf[0],
                                    vf[1],
                                    modelId
                                )
                            );
                            respFirst = false;
                        }
                        respBuilder.append("]}");
                        try {
                            channel.sendResponse(new BytesRestResponse(RestStatus.OK, "application/json", respBuilder.toString()));
                        } catch (Exception e) {
                            log.error("Failed to send response", e);
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        sendError(
                            channel,
                            RestStatus.INTERNAL_SERVER_ERROR,
                            "Pipeline created but failed to set as default: " + e.getMessage()
                        );
                    }
                });
            }

            @Override
            public void onFailure(Exception e) {
                sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Failed to create search pipeline: " + e.getMessage());
            }
        });
    }

    // ========================= disable_semantic_enrichment =========================

    @SuppressWarnings("unchecked")
    private void handleDisableEnrichment(String index, Map<String, Object> body, NodeClient client, RestChannel channel) {
        List<Map<String, Object>> fields = extractFields(body);
        if (fields == null || fields.isEmpty()) {
            sendError(channel, RestStatus.BAD_REQUEST, "Request must contain 'fields' array or original_field/semantic_field params");
            return;
        }

        IndexMetadata indexMetadata = clusterService.state().metadata().index(index);
        if (indexMetadata == null) {
            sendError(channel, RestStatus.NOT_FOUND, "Index [" + index + "] does not exist");
            return;
        }

        List<String[]> fieldPairs = new ArrayList<>();
        for (Map<String, Object> fieldSpec : fields) {
            String originalField = (String) fieldSpec.get("original_field");
            String semanticField = (String) fieldSpec.get("semantic_field");
            if (originalField == null || semanticField == null) {
                sendError(channel, RestStatus.BAD_REQUEST, "original_field and semantic_field are required");
                return;
            }
            fieldPairs.add(new String[] { originalField, semanticField });
        }

        // First: remove FieldReplacementProcessor from pipeline (in case currently DEPLOYED)
        removeFieldReplacementProcessor(index, client, channel, () -> {
            // Then: set status=DISABLED via PutMapping (full cleanup from any state)
            StringBuilder mappingBuilder = new StringBuilder("{\"properties\":{");
            boolean first = true;
            for (String[] fp : fieldPairs) {
                if (!first) mappingBuilder.append(",");
                mappingBuilder.append(String.format(Locale.ROOT, "\"%s\":{\"type\":\"semantic\",\"status\":\"DISABLED\"}", fp[1]));
                first = false;
            }
            mappingBuilder.append("}}");

            PutMappingRequest putMappingRequest = new PutMappingRequest(index);
            putMappingRequest.source(mappingBuilder.toString(), XContentType.JSON);

            client.admin().indices().putMapping(putMappingRequest, new ActionListener<AcknowledgedResponse>() {
                @Override
                public void onResponse(AcknowledgedResponse response) {
                    StringBuilder respBuilder = new StringBuilder("{\"acknowledged\":true,\"index\":\"" + index + "\",\"fields\":[");
                    boolean respFirst = true;
                    for (String[] fp : fieldPairs) {
                        if (!respFirst) respBuilder.append(",");
                        respBuilder.append(
                            String.format(
                                Locale.ROOT,
                                "{\"original_field\":\"%s\",\"semantic_field\":\"%s\",\"status\":\"DISABLED\"}",
                                fp[0],
                                fp[1]
                            )
                        );
                        respFirst = false;
                    }
                    respBuilder.append("]}");
                    try {
                        channel.sendResponse(new BytesRestResponse(RestStatus.OK, "application/json", respBuilder.toString()));
                    } catch (Exception e) {
                        log.error("Failed to send response", e);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Failed to disable enrichment: " + e.getMessage());
                }
            });
        });
    }

    // ========================= deploy_semantic_enrichment =========================

    @SuppressWarnings("unchecked")
    private void handleDeploy(String index, Map<String, Object> body, NodeClient client, RestChannel channel) {
        List<Map<String, Object>> fields = extractFields(body);
        if (fields == null || fields.isEmpty()) {
            sendError(channel, RestStatus.BAD_REQUEST, "Request must contain 'fields' array or original_field/semantic_field params");
            return;
        }

        IndexMetadata indexMetadata = clusterService.state().metadata().index(index);
        if (indexMetadata == null) {
            sendError(channel, RestStatus.NOT_FOUND, "Index [" + index + "] does not exist");
            return;
        }

        Map<String, Object> properties = (Map<String, Object>) indexMetadata.mapping().sourceAsMap().get("properties");
        if (properties == null) {
            sendError(channel, RestStatus.BAD_REQUEST, "Index [" + index + "] has no properties");
            return;
        }

        // Validate all semantic fields exist and are ENABLED
        List<String[]> fieldPairs = new ArrayList<>();
        for (Map<String, Object> fieldSpec : fields) {
            String originalField = (String) fieldSpec.get("original_field");
            String semanticField = (String) fieldSpec.get("semantic_field");
            if (originalField == null || semanticField == null) {
                sendError(channel, RestStatus.BAD_REQUEST, "original_field and semantic_field are required");
                return;
            }

            if (!(properties.get(semanticField) instanceof Map)) {
                sendError(channel, RestStatus.BAD_REQUEST, "semantic_field [" + semanticField + "] does not exist");
                return;
            }
            Map<String, Object> semDef = (Map<String, Object>) properties.get(semanticField);
            if (!"semantic".equals(semDef.get("type"))) {
                sendError(channel, RestStatus.BAD_REQUEST, "[" + semanticField + "] is not a semantic field");
                return;
            }
            String modelId = (String) semDef.get("model_id");
            if (modelId == null) {
                sendError(channel, RestStatus.BAD_REQUEST, "semantic_field [" + semanticField + "] has no model_id");
                return;
            }
            // Deploy only allowed from ENRICHING state (status=ENABLED, not DISABLED)
            String status = (String) semDef.getOrDefault("status", "ENABLED");
            if ("DISABLED".equalsIgnoreCase(status)) {
                sendError(
                    channel,
                    RestStatus.BAD_REQUEST,
                    "Cannot deploy semantic_field [" + semanticField + "] — field is DISABLED. Call enable_semantic_enrichment first."
                );
                return;
            }
            fieldPairs.add(new String[] { originalField, semanticField });
        }

        // Deploy is purely a search-pipeline change: add the FieldReplacementProcessor so that
        // queries on the original field are rewritten to the semantic field (then to neural).
        // The DEPLOYED vs ENRICHING distinction lives entirely in the pipeline — no mapping change.
        // Reject if already DEPLOYED (FieldReplacementProcessor already present).
        String pipelineName = index + SEARCH_PIPELINE_SUFFIX;
        GetSearchPipelineRequest getPipelineRequest = new GetSearchPipelineRequest(pipelineName);
        client.admin()
            .cluster()
            .execute(GetSearchPipelineAction.INSTANCE, getPipelineRequest, new ActionListener<GetSearchPipelineResponse>() {
                @Override
                public void onResponse(GetSearchPipelineResponse pipelineResponse) {
                    if (checkFieldReplacementInPipeline(pipelineResponse)) {
                        sendError(channel, RestStatus.BAD_REQUEST, "Index [" + index + "] is already DEPLOYED.");
                        return;
                    }
                    addFieldReplacementProcessor(index, fieldPairs, client, channel, () -> sendDeployResponse(channel, index, fieldPairs));
                }

                @Override
                public void onFailure(Exception e) {
                    // Pipeline missing — enable was not run
                    sendError(
                        channel,
                        RestStatus.BAD_REQUEST,
                        "Search pipeline [" + pipelineName + "] does not exist. Run enable_semantic_enrichment first."
                    );
                }
            });
    }

    private void sendDeployResponse(RestChannel channel, String index, List<String[]> fieldPairs) {
        StringBuilder respBuilder = new StringBuilder(
            "{\"acknowledged\":true,\"index\":\"" + index + "\",\"state\":\"DEPLOYED\",\"fields\":["
        );
        boolean respFirst = true;
        for (String[] fp : fieldPairs) {
            if (!respFirst) respBuilder.append(",");
            respBuilder.append(
                String.format(
                    Locale.ROOT,
                    "{\"original_field\":\"%s\",\"semantic_field\":\"%s\",\"field_replacement\":\"active\"}",
                    fp[0],
                    fp[1]
                )
            );
            respFirst = false;
        }
        respBuilder.append("]}");
        try {
            channel.sendResponse(new BytesRestResponse(RestStatus.OK, "application/json", respBuilder.toString()));
        } catch (Exception e) {
            log.error("Failed to send response", e);
        }
    }

    // ========================= rollback_semantic_enrichment =========================

    @SuppressWarnings("unchecked")
    private void handleRollback(String index, Map<String, Object> body, NodeClient client, RestChannel channel) {
        List<Map<String, Object>> fields = extractFields(body);
        if (fields == null || fields.isEmpty()) {
            sendError(channel, RestStatus.BAD_REQUEST, "Request must contain 'fields' array or original_field/semantic_field params");
            return;
        }

        IndexMetadata indexMetadata = clusterService.state().metadata().index(index);
        if (indexMetadata == null) {
            sendError(channel, RestStatus.NOT_FOUND, "Index [" + index + "] does not exist");
            return;
        }

        List<String[]> fieldPairs = new ArrayList<>();
        for (Map<String, Object> fieldSpec : fields) {
            String originalField = (String) fieldSpec.get("original_field");
            String semanticField = (String) fieldSpec.get("semantic_field");
            if (originalField == null || semanticField == null) {
                sendError(channel, RestStatus.BAD_REQUEST, "original_field and semantic_field are required");
                return;
            }
            fieldPairs.add(new String[] { originalField, semanticField });
        }

        // Rollback is purely a search-pipeline change: remove the FieldReplacementProcessor so that
        // queries on the original field return to plain BM25. Embeddings keep generating (copy mode).
        // No mapping change is needed.
        removeFieldReplacementProcessor(index, client, channel, () -> {
            StringBuilder respBuilder = new StringBuilder(
                "{\"acknowledged\":true,\"index\":\"" + index + "\",\"state\":\"ENRICHING\",\"fields\":["
            );
            boolean respFirst = true;
            for (String[] fp : fieldPairs) {
                if (!respFirst) respBuilder.append(",");
                respBuilder.append(
                    String.format(
                        Locale.ROOT,
                        "{\"original_field\":\"%s\",\"semantic_field\":\"%s\",\"field_replacement\":\"removed\"}",
                        fp[0],
                        fp[1]
                    )
                );
                respFirst = false;
            }
            respBuilder.append("]}");
            try {
                channel.sendResponse(new BytesRestResponse(RestStatus.OK, "application/json", respBuilder.toString()));
            } catch (Exception e) {
                log.error("Failed to send response", e);
            }
        });
    }

    // ========================= list_semantic_enrichment =========================

    @SuppressWarnings("unchecked")
    private void handleList(String index, NodeClient client, RestChannel channel) {
        IndexMetadata indexMetadata = clusterService.state().metadata().index(index);
        if (indexMetadata == null) {
            sendError(channel, RestStatus.NOT_FOUND, "Index [" + index + "] does not exist");
            return;
        }

        Map<String, Object> properties = (Map<String, Object>) indexMetadata.mapping().sourceAsMap().get("properties");
        if (properties == null) {
            try {
                channel.sendResponse(
                    new BytesRestResponse(RestStatus.OK, "application/json", "{\"index\":\"" + index + "\",\"fields\":[]}")
                );
            } catch (Exception e) {
                log.error("Failed to send response", e);
            }
            return;
        }

        // GET the search pipeline to check if field_replacement_processor is present
        String pipelineName = index + SEARCH_PIPELINE_SUFFIX;
        GetSearchPipelineRequest getPipelineRequest = new GetSearchPipelineRequest(pipelineName);

        client.admin()
            .cluster()
            .execute(GetSearchPipelineAction.INSTANCE, getPipelineRequest, new ActionListener<GetSearchPipelineResponse>() {
                @Override
                public void onResponse(GetSearchPipelineResponse pipelineResponse) {
                    boolean hasFieldReplacement = checkFieldReplacementInPipeline(pipelineResponse);
                    sendListResponse(channel, index, properties, hasFieldReplacement);
                }

                @Override
                public void onFailure(Exception e) {
                    // Pipeline might not exist — that's fine, just means no field_replacement
                    sendListResponse(channel, index, properties, false);
                }
            });
    }

    @SuppressWarnings("unchecked")
    private boolean checkFieldReplacementInPipeline(GetSearchPipelineResponse pipelineResponse) {
        if (pipelineResponse == null || pipelineResponse.pipelines() == null || pipelineResponse.pipelines().isEmpty()) {
            return false;
        }
        for (PipelineConfiguration config : pipelineResponse.pipelines()) {
            Map<String, Object> configMap = config.getConfigAsMap();
            Object requestProcessors = configMap.get("request_processors");
            if (requestProcessors instanceof List<?> processors) {
                for (Object proc : processors) {
                    if (proc instanceof Map<?, ?> procMap) {
                        if (procMap.containsKey(FIELD_REPLACEMENT_PROCESSOR_TYPE)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void sendListResponse(RestChannel channel, String index, Map<String, Object> properties, boolean hasFieldReplacement) {
        StringBuilder respBuilder = new StringBuilder("{\"index\":\"" + index + "\",\"fields\":[");
        boolean first = true;

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            Map<String, Object> fieldDef = (Map<String, Object>) entry.getValue();
            if (!"semantic".equals(fieldDef.get("type"))) continue;

            String originalField = (String) fieldDef.get("original_field");
            if (originalField == null) continue;

            String semanticField = entry.getKey();
            String modelId = (String) fieldDef.getOrDefault("model_id", "");
            String modelType = (String) fieldDef.getOrDefault("model_type", "SPARSE");
            String language = (String) fieldDef.getOrDefault("language", "ENGLISH");
            String status = (String) fieldDef.getOrDefault("status", "ENABLED");

            // Derive state from status + pipeline (no stored _rename flag):
            // DISABLED — status is DISABLED
            // DEPLOYED — FieldReplacementProcessor present (queries on original field rewritten)
            // ENRICHING — otherwise (embeddings generated, queries on original field still BM25)
            String state;
            if ("DISABLED".equalsIgnoreCase(status)) {
                state = "DISABLED";
            } else if (hasFieldReplacement) {
                state = "DEPLOYED";
            } else {
                state = "ENRICHING";
            }

            if (!first) respBuilder.append(",");
            respBuilder.append(
                String.format(
                    Locale.ROOT,
                    "{\"original_field\":\"%s\",\"semantic_field\":\"%s\",\"model_id\":\"%s\",\"model_type\":\"%s\",\"language\":\"%s\",\"status\":\"%s\",\"state\":\"%s\"}",
                    originalField,
                    semanticField,
                    modelId,
                    modelType,
                    language,
                    status,
                    state
                )
            );
            first = false;
        }
        respBuilder.append("]}");

        try {
            channel.sendResponse(new BytesRestResponse(RestStatus.OK, "application/json", respBuilder.toString()));
        } catch (Exception e) {
            log.error("Failed to send response", e);
        }
    }

    // ========================= Shared pipeline helpers =========================

    /**
     * GET existing pipeline, prepend field_replacement_processor with field_map entries, PUT updated pipeline.
     */
    @SuppressWarnings("unchecked")
    private void addFieldReplacementProcessor(
        String index,
        List<String[]> fieldPairs,
        NodeClient client,
        RestChannel channel,
        Runnable onSuccess
    ) {
        String pipelineName = index + SEARCH_PIPELINE_SUFFIX;
        GetSearchPipelineRequest getRequest = new GetSearchPipelineRequest(pipelineName);

        client.admin().cluster().execute(GetSearchPipelineAction.INSTANCE, getRequest, new ActionListener<GetSearchPipelineResponse>() {
            @Override
            public void onResponse(GetSearchPipelineResponse getResponse) {
                if (getResponse.pipelines() == null || getResponse.pipelines().isEmpty()) {
                    sendError(
                        channel,
                        RestStatus.BAD_REQUEST,
                        "Search pipeline [" + pipelineName + "] does not exist. Run enable_semantic_enrichment first."
                    );
                    return;
                }

                PipelineConfiguration pipelineConfig = getResponse.pipelines().get(0);
                Map<String, Object> configMap = new LinkedHashMap<>(pipelineConfig.getConfigAsMap());

                // Build field_map for field_replacement_processor: {original_field: semantic_field}
                Map<String, Object> fieldMap = new LinkedHashMap<>();
                for (String[] fp : fieldPairs) {
                    fieldMap.put(fp[0], fp[1]);
                }

                // Build the field_replacement_processor entry
                Map<String, Object> fieldReplacementConfig = new LinkedHashMap<>();
                fieldReplacementConfig.put("field_map", fieldMap);
                Map<String, Object> processorEntry = new LinkedHashMap<>();
                processorEntry.put(FIELD_REPLACEMENT_PROCESSOR_TYPE, fieldReplacementConfig);

                // Get existing request_processors list
                List<Map<String, Object>> requestProcessors;
                Object existing = configMap.get("request_processors");
                if (existing instanceof List<?>) {
                    requestProcessors = new ArrayList<>((List<Map<String, Object>>) existing);
                } else {
                    requestProcessors = new ArrayList<>();
                }

                // Remove existing field_replacement_processor if present (replace it)
                requestProcessors.removeIf(p -> p.containsKey(FIELD_REPLACEMENT_PROCESSOR_TYPE));

                // Prepend field_replacement_processor
                requestProcessors.add(0, processorEntry);
                configMap.put("request_processors", requestProcessors);

                // PUT updated pipeline
                putPipeline(pipelineName, configMap, client, channel, onSuccess);
            }

            @Override
            public void onFailure(Exception e) {
                sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Failed to get search pipeline: " + e.getMessage());
            }
        });
    }

    /**
     * GET existing pipeline, remove field_replacement_processor, PUT updated pipeline.
     */
    @SuppressWarnings("unchecked")
    private void removeFieldReplacementProcessor(String index, NodeClient client, RestChannel channel, Runnable onSuccess) {
        String pipelineName = index + SEARCH_PIPELINE_SUFFIX;
        GetSearchPipelineRequest getRequest = new GetSearchPipelineRequest(pipelineName);

        client.admin().cluster().execute(GetSearchPipelineAction.INSTANCE, getRequest, new ActionListener<GetSearchPipelineResponse>() {
            @Override
            public void onResponse(GetSearchPipelineResponse getResponse) {
                if (getResponse.pipelines() == null || getResponse.pipelines().isEmpty()) {
                    // Pipeline doesn't exist — nothing to remove, treat as success
                    onSuccess.run();
                    return;
                }

                PipelineConfiguration pipelineConfig = getResponse.pipelines().get(0);
                Map<String, Object> configMap = new LinkedHashMap<>(pipelineConfig.getConfigAsMap());

                // Get existing request_processors list
                Object existing = configMap.get("request_processors");
                if (existing instanceof List<?>) {
                    List<Map<String, Object>> requestProcessors = new ArrayList<>((List<Map<String, Object>>) existing);
                    // Remove field_replacement_processor
                    requestProcessors.removeIf(p -> p.containsKey(FIELD_REPLACEMENT_PROCESSOR_TYPE));
                    configMap.put("request_processors", requestProcessors);
                }

                // PUT updated pipeline (without field_replacement_processor)
                putPipeline(pipelineName, configMap, client, channel, onSuccess);
            }

            @Override
            public void onFailure(Exception e) {
                // Pipeline doesn't exist — nothing to remove
                onSuccess.run();
            }
        });
    }

    /**
     * PUT a search pipeline with the given config map.
     */
    private void putPipeline(
        String pipelineName,
        Map<String, Object> configMap,
        NodeClient client,
        RestChannel channel,
        Runnable onSuccess
    ) {
        try {
            org.opensearch.core.xcontent.XContentBuilder builder = org.opensearch.core.xcontent.XContentBuilder.builder(
                XContentType.JSON.xContent()
            );
            builder.map(configMap);
            org.opensearch.core.common.bytes.BytesReference pipelineBytes = org.opensearch.core.common.bytes.BytesReference.bytes(builder);

            PutSearchPipelineRequest putRequest = new PutSearchPipelineRequest(pipelineName, pipelineBytes, XContentType.JSON);

            client.admin().cluster().execute(PutSearchPipelineAction.INSTANCE, putRequest, new ActionListener<AcknowledgedResponse>() {
                @Override
                public void onResponse(AcknowledgedResponse response) {
                    if (response.isAcknowledged()) {
                        onSuccess.run();
                    } else {
                        sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Pipeline update not acknowledged");
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Failed to update search pipeline: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Failed to serialize pipeline config: " + e.getMessage());
        }
    }

    // ========================= Error helper =========================

    private void sendError(RestChannel channel, RestStatus status, String message) {
        String errorBody = String.format(
            Locale.ROOT,
            "{\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"%s\"},\"status\":%d}",
            message,
            status.getStatus()
        );
        try {
            channel.sendResponse(new BytesRestResponse(status, "application/json", errorBody));
        } catch (Exception e) {
            log.error("Failed to send error response", e);
        }
    }
}
