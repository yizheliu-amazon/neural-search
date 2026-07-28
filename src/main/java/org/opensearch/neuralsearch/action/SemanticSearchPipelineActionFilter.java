/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.action;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.admin.indices.create.CreateIndexAction;
import org.opensearch.action.admin.indices.create.CreateIndexResponse;
import org.opensearch.action.admin.indices.mapping.put.PutMappingAction;
import org.opensearch.action.support.clustermanager.AcknowledgedResponse;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.search.PutSearchPipelineRequest;
import org.opensearch.action.support.ActionFilter;
import org.opensearch.action.support.ActionFilterChain;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.transport.client.Client;

import java.util.HashMap;
import java.util.Map;

/**
 * ActionFilter that automatically creates a search pipeline with semantic_search_rewrite_processor
 * and neural_sparse_two_phase_processor when an index with enabled semantic fields is created.
 *
 * When source_field is configured on a semantic field, the field_map maps the source_field name
 * (e.g., "title") to the companion embedding field, so that queries on the source field are
 * automatically rewritten to neural queries.
 */
public class SemanticSearchPipelineActionFilter implements ActionFilter {

    private static final Logger log = LogManager.getLogger(SemanticSearchPipelineActionFilter.class);
    private static final String SEARCH_PIPELINE_SUFFIX = "-semantic-search-pipeline";

    private final ClusterService clusterService;
    private final Client client;

    public SemanticSearchPipelineActionFilter(ClusterService clusterService, Client client) {
        this.clusterService = clusterService;
        this.client = client;
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE; // run last, after index is created
    }

    @Override
    public <Request extends ActionRequest, Response extends ActionResponse> void apply(
        org.opensearch.tasks.Task task,
        String action,
        Request request,
        ActionListener<Response> listener,
        ActionFilterChain<Request, Response> chain
    ) {
        if (CreateIndexAction.NAME.equals(action)) {
            // Pre-check: reject if CreateIndex request specifies both semantic fields and a search pipeline
            try {
                checkCreateIndexPipelineConflict(request);
            } catch (IllegalArgumentException e) {
                listener.onFailure(e);
                return;
            }
            chain.proceed(task, action, request, new ActionListener<Response>() {
                @Override
                public void onResponse(Response response) {
                    if (response instanceof CreateIndexResponse createIndexResponse && createIndexResponse.isAcknowledged()) {
                        String indexName = createIndexResponse.index();
                        try {
                            handleIndexWithSemanticFields(indexName);
                        } catch (Exception e) {
                            log.warn("Failed to create search pipeline for index [{}]: {}", indexName, e.getMessage());
                        }
                    }
                    listener.onResponse(response);
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(e);
                }
            });
        } else if (PutMappingAction.NAME.equals(action)) {
            // Pre-check: reject if index has a non-ASE search pipeline and request adds semantic fields
            try {
                checkSearchPipelineConflict(request);
            } catch (IllegalArgumentException e) {
                listener.onFailure(e);
                return;
            }
            chain.proceed(task, action, request, new ActionListener<Response>() {
                @Override
                public void onResponse(Response response) {
                    if (response instanceof AcknowledgedResponse ack && ack.isAcknowledged()) {
                        try {
                            handlePutMappingResponse(request);
                        } catch (Exception e) {
                            log.warn("Failed to update search pipeline after PutMapping: {}", e.getMessage());
                        }
                    }
                    listener.onResponse(response);
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(e);
                }
            });
        } else {
            chain.proceed(task, action, request, listener);
        }
    }

    /**
     * Check if CreateIndex request specifies both semantic fields and a custom search pipeline.
     * If so, reject — ASE will create its own pipeline.
     */
    private <Request extends ActionRequest> void checkCreateIndexPipelineConflict(Request request) {
        if (!(request instanceof org.opensearch.action.admin.indices.create.CreateIndexRequest createRequest)) {
            return;
        }
        String searchPipeline = createRequest.settings().get("index.search.default_pipeline");
        if (searchPipeline == null) return;

        // Check if mappings contain semantic fields
        String mappingSource = createRequest.mappings();
        if (mappingSource != null && mappingSource.contains("\"semantic\"")) {
            throw new IllegalArgumentException(
                "Cannot specify both semantic fields and index.search.default_pipeline in the same CreateIndex request. "
                    + "Semantic fields automatically create and attach a search pipeline."
            );
        }
    }

    /**
     * Check if the PutMapping request adds semantic fields to an index that already has a
     * non-ASE search pipeline attached. If so, throw to reject the request.
     */
    private <Request extends ActionRequest> void checkSearchPipelineConflict(Request request) {
        if (!(request instanceof org.opensearch.action.admin.indices.mapping.put.PutMappingRequest putRequest)) {
            return;
        }

        // Check if the incoming mapping contains semantic fields
        String mappingSource = putRequest.source();
        if (mappingSource == null || !mappingSource.contains("\"semantic\"")) {
            return; // No semantic field in this PutMapping — no conflict possible
        }

        for (String indexName : putRequest.indices()) {
            IndexMetadata indexMetadata = clusterService.state().metadata().index(indexName);
            if (indexMetadata == null) continue;

            String currentPipeline = indexMetadata.getSettings().get("index.search.default_pipeline");
            if (currentPipeline != null && !currentPipeline.endsWith(SEARCH_PIPELINE_SUFFIX)) {
                throw new IllegalArgumentException(
                    "Search pipeline ["
                        + currentPipeline
                        + "] is already enabled for index ["
                        + indexName
                        + "]. "
                        + "Please remove it before adding semantic fields. "
                        + "Use: PUT /"
                        + indexName
                        + "/_settings {\"index.search.default_pipeline\": null}"
                );
            }
        }
    }

    private <Request extends ActionRequest> void handlePutMappingResponse(Request request) {
        if (request instanceof org.opensearch.action.admin.indices.mapping.put.PutMappingRequest putRequest) {
            for (String indexName : putRequest.indices()) {
                handleIndexWithSemanticFields(indexName);
            }
        }
    }

    private void handleIndexWithSemanticFields(String indexName) {
        IndexMetadata indexMetadata = clusterService.state().metadata().index(indexName);
        if (indexMetadata == null || indexMetadata.mapping() == null) return;

        Map<String, Object> mappingSource = indexMetadata.mapping().sourceAsMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) mappingSource.get("properties");
        if (properties == null) return;

        // Collect enabled semantic fields
        Map<String, SemanticFieldInfo> enabledFields = new HashMap<>();
        collectEnabledSemanticFields(properties, "", enabledFields);

        if (enabledFields.isEmpty()) {
            // No enabled semantic fields — detach pipeline if attached
            detachSearchPipeline(indexName);
            return;
        }

        // Build and create search pipeline
        String pipelineName = indexName + SEARCH_PIPELINE_SUFFIX;
        String pipelineBody = buildSearchPipelineBody(enabledFields);
        createOrUpdateSearchPipeline(indexName, pipelineName, pipelineBody);
    }

    /**
     * Collect enabled semantic fields from the mapping properties.
     * When source_field is configured, the field_map key is the source_field name (so queries on
     * the source field get rewritten). When no source_field, the key is the semantic field name itself.
     */
    @SuppressWarnings("unchecked")
    private void collectEnabledSemanticFields(Map<String, Object> properties, String prefix, Map<String, SemanticFieldInfo> result) {
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String fieldName = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (!(entry.getValue() instanceof Map)) continue;
            Map<String, Object> fieldDef = (Map<String, Object>) entry.getValue();

            if ("semantic".equals(fieldDef.get("type"))) {
                String status = (String) fieldDef.getOrDefault("status", "ENABLED");
                if ("ENABLED".equalsIgnoreCase(status)) {
                    String modelId = (String) fieldDef.get("model_id");
                    String modelType = (String) fieldDef.getOrDefault("model_type", "SPARSE");
                    String originalField = (String) fieldDef.get("original_field");
                    String targetField = fieldName + "_semantic_info.embedding";

                    // Key decision: if original_field is configured, map original_field -> embedding
                    // so that match queries on the original field get rewritten.
                    // Otherwise, map the semantic field name itself.
                    String mapKey = (originalField != null && !originalField.isEmpty()) ? originalField : fieldName;
                    result.put(mapKey, new SemanticFieldInfo(modelId, modelType, targetField));
                }
            }

            // Recurse into nested/object fields
            if (fieldDef.containsKey("properties")) {
                collectEnabledSemanticFields((Map<String, Object>) fieldDef.get("properties"), fieldName, result);
            }
        }
    }

    private String buildSearchPipelineBody(Map<String, SemanticFieldInfo> fields) {
        StringBuilder fieldMapEntries = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, SemanticFieldInfo> entry : fields.entrySet()) {
            if (!first) fieldMapEntries.append(",");
            first = false;

            SemanticFieldInfo info = entry.getValue();
            String type = "DENSE".equalsIgnoreCase(info.modelType) ? "dense" : "sparse";

            fieldMapEntries.append("\"").append(entry.getKey()).append("\": {");
            fieldMapEntries.append("\"type\": \"").append(type).append("\",");
            fieldMapEntries.append("\"target_field\": \"").append(info.targetField).append("\"");
            if (info.modelId != null) {
                fieldMapEntries.append(",\"model_id\": \"").append(info.modelId).append("\"");
            }
            if ("sparse".equals(type)) {
                fieldMapEntries.append(",\"analyzer\": \"bert-uncased\"");
            }
            fieldMapEntries.append("}");
        }

        return "{"
            + "\"request_processors\": ["
            + "{\"semantic_search_rewrite_processor\": {\"field_map\": {"
            + fieldMapEntries
            + "}}},"
            + "{\"neural_sparse_two_phase_processor\": {\"enabled\": true}}"
            + "]"
            + "}";
    }

    private void createOrUpdateSearchPipeline(String indexName, String pipelineName, String pipelineBody) {
        log.info("Creating search pipeline [{}] for index [{}]", pipelineName, indexName);

        // Create or update the search pipeline
        PutSearchPipelineRequest putRequest = new PutSearchPipelineRequest(pipelineName, new BytesArray(pipelineBody), XContentType.JSON);
        client.admin().cluster().putSearchPipeline(putRequest, ActionListener.wrap(response -> {
            if (response.isAcknowledged()) {
                attachSearchPipeline(indexName, pipelineName);
            } else {
                log.warn("Search pipeline creation not acknowledged for [{}]", pipelineName);
            }
        }, e -> log.warn("Failed to create search pipeline [{}]: {}", pipelineName, e.getMessage())));
    }

    private void attachSearchPipeline(String indexName, String pipelineName) {
        client.admin()
            .indices()
            .prepareUpdateSettings(indexName)
            .setSettings(org.opensearch.common.settings.Settings.builder().put("index.search.default_pipeline", pipelineName).build())
            .execute(
                ActionListener.wrap(
                    response -> log.info("Attached search pipeline [{}] to index [{}]", pipelineName, indexName),
                    e -> log.warn("Failed to attach search pipeline [{}] to index [{}]: {}", pipelineName, indexName, e.getMessage())
                )
            );
    }

    private void detachSearchPipeline(String indexName) {
        IndexMetadata indexMetadata = clusterService.state().metadata().index(indexName);
        if (indexMetadata == null) return;

        String currentPipeline = indexMetadata.getSettings().get("index.search.default_pipeline");
        if (currentPipeline != null && currentPipeline.endsWith(SEARCH_PIPELINE_SUFFIX)) {
            client.admin()
                .indices()
                .prepareUpdateSettings(indexName)
                .setSettings(org.opensearch.common.settings.Settings.builder().putNull("index.search.default_pipeline").build())
                .execute(
                    ActionListener.wrap(
                        response -> log.info("Detached search pipeline from index [{}]", indexName),
                        e -> log.warn("Failed to detach search pipeline from index [{}]: {}", indexName, e.getMessage())
                    )
                );
        }
    }

    private record SemanticFieldInfo(String modelId, String modelType, String targetField) {
    }
}
