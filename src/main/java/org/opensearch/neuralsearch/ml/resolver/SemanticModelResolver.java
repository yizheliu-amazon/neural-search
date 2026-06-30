/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.ml.resolver;

import org.opensearch.core.action.ActionListener;

/**
 * Interface for resolving language/model_type to a deployed model_id.
 * The OSS implementation registers pretrained models from the model hub.
 * Managed service implementations can override to resolve to pre-deployed managed models.
 */
public interface SemanticModelResolver {

    /**
     * Resolve language + model_type to a deployed model_id.
     * The implementation may register/deploy the model if needed (async).
     *
     * @param language    the language option (e.g., "ENGLISH", "MULTI-LINGUAL")
     * @param modelType   the model type (e.g., "SPARSE", "DENSE")
     * @param listener    callback with the resolved model_id
     */
    void resolve(String language, String modelType, ActionListener<String> listener);

    /**
     * Validate the language/model_type combination.
     * @throws IllegalArgumentException if the combination is invalid
     */
    default void validate(String language, String modelType) {
        if ("DENSE".equalsIgnoreCase(modelType) && "MULTI-LINGUAL".equalsIgnoreCase(language)) {
            throw new IllegalArgumentException(
                "DENSE model_type is not supported with MULTI-LINGUAL language. Dense model is only available for ENGLISH."
            );
        }
    }
}
