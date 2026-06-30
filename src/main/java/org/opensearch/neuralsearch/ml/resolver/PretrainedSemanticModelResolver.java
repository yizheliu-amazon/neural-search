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
import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.client.MachineLearningNodeClient;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.MLTask;
import org.opensearch.ml.common.MLTaskState;
import org.opensearch.ml.common.model.MLModelFormat;
import org.opensearch.ml.common.transport.register.MLRegisterModelInput;

/**
 * OSS default implementation of SemanticModelResolver.
 * Registers pretrained models from the OpenSearch model hub and deploys them.
 * Caches resolved model_ids so subsequent calls for the same language/model_type
 * return immediately.
 */
public class PretrainedSemanticModelResolver implements SemanticModelResolver {

    private static final Logger log = LogManager.getLogger(PretrainedSemanticModelResolver.class);

    public static final String SPARSE_ENGLISH_MODEL = "amazon/neural-sparse/opensearch-neural-sparse-encoding-v1";
    public static final String SPARSE_MULTILINGUAL_MODEL = "amazon/neural-sparse/opensearch-neural-sparse-encoding-multilingual-v1";
    public static final String DENSE_ENGLISH_MODEL = "huggingface/sentence-transformers/all-MiniLM-L6-v2";

    private static final String MODEL_VERSION = "1.0.1";

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
        FunctionName functionName = "DENSE".equalsIgnoreCase(modelType) ? FunctionName.TEXT_EMBEDDING : FunctionName.SPARSE_ENCODING;

        log.info("Registering pretrained model [{}] for [{}/{}]", modelName, language, modelType);

        MLRegisterModelInput input = MLRegisterModelInput.builder()
            .functionName(functionName)
            .modelName(modelName)
            .version(MODEL_VERSION)
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

    private String normKey(String language, String modelType) {
        return (language != null ? language.toUpperCase(Locale.ROOT) : "ENGLISH")
            + ":"
            + (modelType != null ? modelType.toUpperCase(Locale.ROOT) : "SPARSE");
    }
}
