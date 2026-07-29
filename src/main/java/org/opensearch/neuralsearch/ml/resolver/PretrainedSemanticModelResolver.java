/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.ml.resolver;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.ml.client.MachineLearningNodeClient;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.MLTask;
import org.opensearch.ml.common.MLTaskState;
import org.opensearch.ml.common.model.MLModelFormat;
import org.opensearch.ml.common.transport.register.MLRegisterModelInput;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.transport.client.Client;

/**
 * OSS default implementation of SemanticModelResolver.
 * Registers pretrained models from the OpenSearch model hub and deploys them.
 * Caches resolved model_ids so subsequent calls for the same language/model_type
 * return immediately.
 */
public class PretrainedSemanticModelResolver implements SemanticModelResolver {

    private static final Logger log = LogManager.getLogger(PretrainedSemanticModelResolver.class);

    public static final String SPARSE_ENGLISH_MODEL = "amazon/neural-sparse/opensearch-neural-sparse-encoding-v2-distill";
    public static final String SPARSE_ENGLISH_MODEL_VERSION = "1.0.0";
    public static final String SPARSE_MULTILINGUAL_MODEL = "amazon/neural-sparse/opensearch-neural-sparse-encoding-multilingual-v1";
    public static final String SPARSE_MULTILINGUAL_MODEL_VERSION = "1.0.1";
    public static final String DENSE_ENGLISH_MODEL = "huggingface/sentence-transformers/all-MiniLM-L6-v2";
    public static final String DENSE_ENGLISH_MODEL_VERSION = "1.0.1";

    private static final String ML_MODEL_INDEX = ".plugins-ml-model";

    private final MachineLearningNodeClient mlClient;
    private final Client client;
    private final Map<String, String> resolvedModelCache = new ConcurrentHashMap<>();

    public PretrainedSemanticModelResolver(MachineLearningNodeClient mlClient, Client client) {
        this.mlClient = mlClient;
        this.client = client;
    }

    @Override
    public void resolve(String language, String modelType, ActionListener<String> listener) {
        validate(language, modelType);

        String cacheKey = normKey(language, modelType);
        String modelName = resolveModelName(language, modelType);
        String modelVersion = resolveModelVersion(language, modelType);

        // Always search ml-commons first (skip in-memory cache for testability).
        // This makes it easy to verify the search path works end-to-end.
        searchExistingModel(modelName, modelVersion, cacheKey, ActionListener.wrap(existingModelId -> {
            if (existingModelId != null) {
                log.info("Found existing DEPLOYED model [{}] for [{}/{}], reusing", existingModelId, language, modelType);
                resolvedModelCache.put(cacheKey, existingModelId);
                listener.onResponse(existingModelId);
            } else {
                registerAndDeploy(language, modelType, modelName, modelVersion, cacheKey, listener);
            }
        }, e -> {
            // Search failed (e.g. ml index doesn't exist yet) — fall through to register
            log.warn("Failed to search for existing model [{}], proceeding to register: {}", modelName, e.getMessage());
            registerAndDeploy(language, modelType, modelName, modelVersion, cacheKey, listener);
        }));
    }

    private void searchExistingModel(String modelName, String modelVersion, String cacheKey, ActionListener<String> listener) {
        // Search the .plugins-ml-model index directly using the standard OpenSearch Client.
        // We cannot use mlClient.searchModel() because it requires MLSearchActionRequest which
        // is loaded by a different classloader (plugin isolation) and causes ClassCastException.
        BoolQueryBuilder query = QueryBuilders.boolQuery()
            .must(QueryBuilders.termQuery("name.keyword", modelName))
            .must(QueryBuilders.termQuery("algorithm", resolveFunctionName(modelName)));

        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder().query(query).size(10);
        SearchRequest searchRequest = new SearchRequest(ML_MODEL_INDEX).source(sourceBuilder);

        client.search(searchRequest, ActionListener.wrap(searchResponse -> {
            SearchHit[] hits = searchResponse.getHits().getHits();
            if (hits.length == 0) {
                listener.onResponse(null);
                return;
            }
            // The ML model index stores both metadata docs and content chunks under the same name.
            // Extract the model_id from the source (all docs carry it) and deduplicate, then
            // verify DEPLOYED state via the GET model API.
            String candidateModelId = null;
            for (SearchHit hit : hits) {
                Map<String, Object> source = hit.getSourceAsMap();
                Object mid = source != null ? source.get("model_id") : null;
                if (mid != null && !mid.toString().isEmpty()) {
                    candidateModelId = mid.toString();
                    break;
                }
            }
            if (candidateModelId == null) {
                listener.onResponse(null);
                return;
            }
            // Verify this model is DEPLOYED via the GET model API
            String finalModelId = candidateModelId;
            mlClient.getModel(finalModelId, null, ActionListener.wrap(model -> {
                if (model.getModelState() != null && "DEPLOYED".equals(model.getModelState().name())) {
                    listener.onResponse(finalModelId);
                } else {
                    listener.onResponse(null);
                }
            }, e -> listener.onResponse(null)));
        }, listener::onFailure));
    }

    private String resolveFunctionName(String modelName) {
        if (modelName.contains("sentence-transformers") || modelName.contains("text-embedding")) {
            return "TEXT_EMBEDDING";
        }
        return "SPARSE_ENCODING";
    }

    private void registerAndDeploy(
        String language,
        String modelType,
        String modelName,
        String modelVersion,
        String cacheKey,
        ActionListener<String> listener
    ) {
        FunctionName functionName = "DENSE".equalsIgnoreCase(modelType) ? FunctionName.TEXT_EMBEDDING : FunctionName.SPARSE_ENCODING;

        log.info("Registering pretrained model [{}] v[{}] for [{}/{}]", modelName, modelVersion, language, modelType);

        MLRegisterModelInput input = MLRegisterModelInput.builder()
            .functionName(functionName)
            .modelName(modelName)
            .version(modelVersion)
            .modelFormat(MLModelFormat.TORCH_SCRIPT)
            .deployModel(true)
            .build();

        mlClient.register(input, ActionListener.wrap(response -> {
            String modelId = response.getModelId();
            if (modelId != null && !modelId.isEmpty()) {
                resolvedModelCache.put(cacheKey, modelId);
                log.info("Model registered directly with id [{}]", modelId);
                listener.onResponse(modelId);
            } else {
                String taskId = response.getTaskId();
                log.info("Registration async, polling task [{}]", taskId);
                pollTaskForModelId(taskId, cacheKey, 0, listener);
            }
        }, e -> {
            log.error("Failed to register model [{}]: {}", modelName, e.getMessage());
            listener.onFailure(e);
        }));
    }

    private void pollTaskForModelId(String taskId, String cacheKey, int attempt, ActionListener<String> listener) {
        if (attempt > 30) {
            listener.onFailure(new IllegalStateException("Model registration task [" + taskId + "] did not complete within timeout"));
            return;
        }

        mlClient.getTask(taskId, ActionListener.wrap((MLTask task) -> {
            MLTaskState state = task.getState();
            log.debug("Task [{}] state: {} (attempt {})", taskId, state, attempt);

            if (MLTaskState.COMPLETED == state) {
                String modelId = task.getModelId();
                if (modelId != null && !modelId.isEmpty()) {
                    resolvedModelCache.put(cacheKey, modelId);
                    log.info("Task completed, model_id: {}", modelId);
                    listener.onResponse(modelId);
                } else {
                    listener.onFailure(new IllegalStateException("Task completed but no model_id returned"));
                }
            } else if (MLTaskState.FAILED == state || MLTaskState.CANCELLED == state) {
                listener.onFailure(new IllegalStateException("Model registration failed: " + task.getError()));
            } else {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    listener.onFailure(e);
                    return;
                }
                pollTaskForModelId(taskId, cacheKey, attempt + 1, listener);
            }
        }, e -> {
            log.warn("Failed to get task [{}], retrying...", taskId);
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                listener.onFailure(ie);
                return;
            }
            pollTaskForModelId(taskId, cacheKey, attempt + 1, listener);
        }));
    }

    private String resolveModelName(String language, String modelType) {
        if ("DENSE".equalsIgnoreCase(modelType)) {
            return DENSE_ENGLISH_MODEL;
        }
        if ("MULTI-LINGUAL".equalsIgnoreCase(language)) {
            return SPARSE_MULTILINGUAL_MODEL;
        }
        return SPARSE_ENGLISH_MODEL;
    }

    private String resolveModelVersion(String language, String modelType) {
        if ("DENSE".equalsIgnoreCase(modelType)) {
            return DENSE_ENGLISH_MODEL_VERSION;
        }
        if ("MULTI-LINGUAL".equalsIgnoreCase(language)) {
            return SPARSE_MULTILINGUAL_MODEL_VERSION;
        }
        return SPARSE_ENGLISH_MODEL_VERSION;
    }

    private String normKey(String language, String modelType) {
        return (language != null ? language.toUpperCase(Locale.ROOT) : "ENGLISH")
            + ":"
            + (modelType != null ? modelType.toUpperCase(Locale.ROOT) : "SPARSE");
    }
}
