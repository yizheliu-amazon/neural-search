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

    private final MachineLearningNodeClient mlClient;
    private final Map<String, String> resolvedModelCache = new ConcurrentHashMap<>();

    public PretrainedSemanticModelResolver(MachineLearningNodeClient mlClient) {
        this.mlClient = mlClient;
    }

    @Override
    public void resolve(String language, String modelType, ActionListener<String> listener) {
        validate(language, modelType);

        String cacheKey = normKey(language, modelType);
        String cached = resolvedModelCache.get(cacheKey);
        if (cached != null) {
            log.debug("Using cached model_id [{}] for [{}/{}]", cached, language, modelType);
            listener.onResponse(cached);
            return;
        }

        String modelName = resolveModelName(language, modelType);
        String modelVersion = resolveModelVersion(language, modelType);

        // Search for an already-deployed model with the same name+version before registering a new one.
        // This avoids duplicate model deployments across multiple semantic fields or node restarts.
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
        BoolQueryBuilder query = QueryBuilders.boolQuery()
            .must(QueryBuilders.termQuery("name.keyword", modelName))
            .must(QueryBuilders.termQuery("model_version", modelVersion))
            .must(QueryBuilders.termQuery("model_state", "DEPLOYED"));

        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder().query(query).size(1);
        SearchRequest searchRequest = new SearchRequest().source(sourceBuilder);

        mlClient.searchModel(searchRequest, ActionListener.wrap(searchResponse -> {
            SearchHit[] hits = searchResponse.getHits().getHits();
            if (hits.length > 0) {
                String modelId = hits[0].getId();
                listener.onResponse(modelId);
            } else {
                listener.onResponse(null);
            }
        }, listener::onFailure));
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
