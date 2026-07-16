/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.BoostingQueryBuilder;
import org.opensearch.index.query.ConstantScoreQueryBuilder;
import org.opensearch.index.query.DisMaxQueryBuilder;
import org.opensearch.index.query.MatchQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.opensearch.neuralsearch.query.HybridQueryBuilder;
import org.opensearch.neuralsearch.query.NeuralSparseQueryBuilder;
import org.opensearch.neuralsearch.query.NeuralQueryBuilder;
import org.opensearch.search.pipeline.AbstractProcessor;
import org.opensearch.search.pipeline.PipelineProcessingContext;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchRequestProcessor;
import org.opensearch.core.action.ActionListener;

import java.util.Map;

import static org.opensearch.ingest.ConfigurationUtils.readMap;

/**
 * Search request processor that rewrites match queries on semantic fields into neural search equivalents.
 * Supports sparse (neural_sparse) and dense (neural) rewrite based on per-field configuration.
 */
public class SemanticSearchRewriteProcessor extends AbstractProcessor implements SearchRequestProcessor {
    public static final String TYPE = "semantic_search_rewrite_processor";
    public static final String FIELD_MAP_FIELD = "field_map";
    public static final String TYPE_FIELD = "type";
    public static final String MODEL_ID_FIELD = "model_id";
    public static final String TARGET_FIELD = "target_field";
    public static final String ANALYZER_FIELD = "analyzer";

    private final Map<String, Object> fieldMap;

    protected SemanticSearchRewriteProcessor(Map<String, Object> fieldMap, String tag, String description, boolean ignoreFailure) {
        super(tag, description, ignoreFailure);
        this.fieldMap = fieldMap;
    }

    @Override
    public SearchRequest processRequest(SearchRequest request) throws Exception {
        if (request == null || request.source() == null || request.source().query() == null) {
            return request;
        }
        QueryBuilder queryBuilder = request.source().query();
        QueryBuilder newQueryBuilder = rewriteQuery(queryBuilder);
        request.source().query(newQueryBuilder);
        return request;
    }

    @Override
    public void processRequestAsync(
        SearchRequest request,
        PipelineProcessingContext requestContext,
        ActionListener<SearchRequest> requestListener
    ) {
        try {
            requestListener.onResponse(processRequest(request));
        } catch (Exception e) {
            requestListener.onFailure(e);
        }
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @SuppressWarnings("unchecked")
    private QueryBuilder rewriteQuery(QueryBuilder queryBuilder) {
        if (queryBuilder instanceof MatchQueryBuilder matchQuery) {
            return convertMatch(matchQuery);
        } else if (queryBuilder instanceof BoolQueryBuilder boolQuery) {
            return rewriteBool(boolQuery);
        } else if (queryBuilder instanceof BoostingQueryBuilder boostingQuery) {
            return rewriteBoosting(boostingQuery);
        } else if (queryBuilder instanceof ConstantScoreQueryBuilder constantQuery) {
            return rewriteConstantScore(constantQuery);
        } else if (queryBuilder instanceof DisMaxQueryBuilder disMaxQuery) {
            return rewriteDisMax(disMaxQuery);
        } else if (queryBuilder instanceof FunctionScoreQueryBuilder funcQuery) {
            return rewriteFunctionScore(funcQuery);
        } else if (queryBuilder instanceof HybridQueryBuilder hybridQuery) {
            return rewriteHybrid(hybridQuery);
        }
        return queryBuilder;
    }

    @SuppressWarnings("unchecked")
    private QueryBuilder convertMatch(MatchQueryBuilder matchQuery) {
        String fieldName = matchQuery.fieldName();
        if (!fieldMap.containsKey(fieldName)) {
            return matchQuery;
        }

        Object fieldConfig = fieldMap.get(fieldName);
        String targetField;
        String modelId = null;
        String type = "sparse";

        if (fieldConfig instanceof String) {
            // Simple format: field_map: { "title": "title_semantic_info.embedding" }
            targetField = (String) fieldConfig;
        } else if (fieldConfig instanceof Map) {
            // Object format: field_map: { "title": { "target_field": "...", "model_id": "...", "type": "sparse|dense" } }
            Map<String, Object> config = (Map<String, Object>) fieldConfig;
            targetField = (String) config.get(TARGET_FIELD);
            modelId = (String) config.get(MODEL_ID_FIELD);
            type = (String) config.getOrDefault(TYPE_FIELD, "sparse");
        } else {
            return matchQuery;
        }

        if ("dense".equalsIgnoreCase(type)) {
            return NeuralQueryBuilder.builder()
                .fieldName(targetField)
                .queryText(matchQuery.value().toString())
                .modelId(modelId)
                .boost(matchQuery.boost())
                .queryName(matchQuery.queryName())
                .build();
        }

        // Default: sparse
        NeuralSparseQueryBuilder sparseBuilder = new NeuralSparseQueryBuilder().fieldName(targetField)
            .queryText(matchQuery.value().toString())
            .boost(matchQuery.boost())
            .queryName(matchQuery.queryName());
        if (modelId != null) {
            sparseBuilder.modelId(modelId);
        }
        return sparseBuilder;
    }

    private BoolQueryBuilder rewriteBool(BoolQueryBuilder boolQuery) {
        BoolQueryBuilder newBool = new BoolQueryBuilder();
        if (boolQuery.queryName() != null) newBool.queryName(boolQuery.queryName());
        newBool.boost(boolQuery.boost());
        newBool.adjustPureNegative(boolQuery.adjustPureNegative());
        if (boolQuery.minimumShouldMatch() != null) newBool.minimumShouldMatch(boolQuery.minimumShouldMatch());

        // Only rewrite must and should — preserve filter and must_not as-is
        boolQuery.must().forEach(q -> newBool.must(rewriteQuery(q)));
        boolQuery.should().forEach(q -> newBool.should(rewriteQuery(q)));
        boolQuery.filter().forEach(newBool::filter);
        boolQuery.mustNot().forEach(newBool::mustNot);

        return newBool;
    }

    private BoostingQueryBuilder rewriteBoosting(BoostingQueryBuilder boostingQuery) {
        QueryBuilder newPositive = rewriteQuery(boostingQuery.positiveQuery());
        QueryBuilder newNegative = boostingQuery.negativeQuery();
        BoostingQueryBuilder newBoosting = new BoostingQueryBuilder(newPositive, newNegative);
        if (boostingQuery.queryName() != null) newBoosting.queryName(boostingQuery.queryName());
        newBoosting.boost(boostingQuery.boost());
        newBoosting.negativeBoost(boostingQuery.negativeBoost());
        return newBoosting;
    }

    private ConstantScoreQueryBuilder rewriteConstantScore(ConstantScoreQueryBuilder constantQuery) {
        QueryBuilder newInner = rewriteQuery(constantQuery.innerQuery());
        ConstantScoreQueryBuilder newCS = new ConstantScoreQueryBuilder(newInner);
        if (constantQuery.queryName() != null) newCS.queryName(constantQuery.queryName());
        newCS.boost(constantQuery.boost());
        return newCS;
    }

    private DisMaxQueryBuilder rewriteDisMax(DisMaxQueryBuilder disMaxQuery) {
        DisMaxQueryBuilder newDisMax = new DisMaxQueryBuilder();
        if (disMaxQuery.queryName() != null) newDisMax.queryName(disMaxQuery.queryName());
        newDisMax.boost(disMaxQuery.boost());
        newDisMax.tieBreaker(disMaxQuery.tieBreaker());
        disMaxQuery.innerQueries().forEach(q -> newDisMax.add(rewriteQuery(q)));
        return newDisMax;
    }

    private FunctionScoreQueryBuilder rewriteFunctionScore(FunctionScoreQueryBuilder funcQuery) {
        QueryBuilder newInner = rewriteQuery(funcQuery.query());
        FunctionScoreQueryBuilder newFS = new FunctionScoreQueryBuilder(newInner, funcQuery.filterFunctionBuilders());
        if (funcQuery.queryName() != null) newFS.queryName(funcQuery.queryName());
        newFS.boost(funcQuery.boost());
        if (funcQuery.boostMode() != null) newFS.boostMode(funcQuery.boostMode());
        newFS.maxBoost(funcQuery.maxBoost());
        if (funcQuery.scoreMode() != null) newFS.scoreMode(funcQuery.scoreMode());
        if (funcQuery.getMinScore() != null) newFS.setMinScore(funcQuery.getMinScore());
        return newFS;
    }

    private HybridQueryBuilder rewriteHybrid(HybridQueryBuilder hybridQuery) {
        HybridQueryBuilder newHybrid = new HybridQueryBuilder();
        if (hybridQuery.queryName() != null) newHybrid.queryName(hybridQuery.queryName());
        newHybrid.boost(hybridQuery.boost());
        hybridQuery.queries().forEach(q -> newHybrid.add(rewriteQuery(q)));
        return newHybrid;
    }

    public static class Factory implements Processor.Factory<SearchRequestProcessor> {

        @Override
        public SearchRequestProcessor create(
            Map<String, Processor.Factory<SearchRequestProcessor>> processorFactories,
            String tag,
            String description,
            boolean ignoreFailure,
            Map<String, Object> config,
            PipelineContext pipelineContext
        ) throws Exception {
            Map<String, Object> fieldMap = readMap(TYPE, tag, config, FIELD_MAP_FIELD);
            return new SemanticSearchRewriteProcessor(fieldMap, tag, description, ignoreFailure);
        }
    }
}
