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
import org.opensearch.search.pipeline.AbstractProcessor;
import org.opensearch.search.pipeline.PipelineProcessingContext;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchRequestProcessor;
import org.opensearch.core.action.ActionListener;

import java.util.Map;

import static org.opensearch.ingest.ConfigurationUtils.readMap;

/**
 * Search request processor that replaces field names in match queries.
 * It walks the query tree (match, bool, boosting, constant_score, dismax, function_score, hybrid)
 * and for any MatchQueryBuilder whose fieldName() is in its field_map, replaces it with a new
 * MatchQueryBuilder on the target field name (same query text, same params like boost, analyzer).
 *
 * Config format: {"field_map": {"title": "title_semantic"}}
 */
public class FieldReplacementProcessor extends AbstractProcessor implements SearchRequestProcessor {
    public static final String TYPE = "field_replacement_processor";
    public static final String FIELD_MAP_FIELD = "field_map";

    private final Map<String, Object> fieldMap;

    protected FieldReplacementProcessor(Map<String, Object> fieldMap, String tag, String description, boolean ignoreFailure) {
        super(tag, description, ignoreFailure);
        this.fieldMap = fieldMap;
    }

    @Override
    public SearchRequest processRequest(SearchRequest request) throws Exception {
        if (request == null || request.source() == null || request.source().query() == null) {
            return request;
        }
        QueryBuilder queryBuilder = request.source().query();
        QueryBuilder newQueryBuilder = replaceFields(queryBuilder);
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

    private QueryBuilder replaceFields(QueryBuilder queryBuilder) {
        if (queryBuilder instanceof MatchQueryBuilder matchQuery) {
            return replaceMatch(matchQuery);
        } else if (queryBuilder instanceof BoolQueryBuilder boolQuery) {
            return replaceBool(boolQuery);
        } else if (queryBuilder instanceof BoostingQueryBuilder boostingQuery) {
            return replaceBoosting(boostingQuery);
        } else if (queryBuilder instanceof ConstantScoreQueryBuilder constantQuery) {
            return replaceConstantScore(constantQuery);
        } else if (queryBuilder instanceof DisMaxQueryBuilder disMaxQuery) {
            return replaceDisMax(disMaxQuery);
        } else if (queryBuilder instanceof FunctionScoreQueryBuilder funcQuery) {
            return replaceFunctionScore(funcQuery);
        } else if (queryBuilder instanceof HybridQueryBuilder hybridQuery) {
            return replaceHybrid(hybridQuery);
        }
        return queryBuilder;
    }

    private QueryBuilder replaceMatch(MatchQueryBuilder matchQuery) {
        String fieldName = matchQuery.fieldName();
        if (!fieldMap.containsKey(fieldName)) {
            return matchQuery;
        }

        String targetField = fieldMap.get(fieldName).toString();
        MatchQueryBuilder newMatch = new MatchQueryBuilder(targetField, matchQuery.value());
        newMatch.boost(matchQuery.boost());
        if (matchQuery.queryName() != null) {
            newMatch.queryName(matchQuery.queryName());
        }
        if (matchQuery.analyzer() != null) {
            newMatch.analyzer(matchQuery.analyzer());
        }
        newMatch.operator(matchQuery.operator());
        newMatch.fuzziness(matchQuery.fuzziness());
        newMatch.prefixLength(matchQuery.prefixLength());
        newMatch.maxExpansions(matchQuery.maxExpansions());
        newMatch.fuzzyTranspositions(matchQuery.fuzzyTranspositions());
        newMatch.lenient(matchQuery.lenient());
        newMatch.zeroTermsQuery(matchQuery.zeroTermsQuery());
        newMatch.autoGenerateSynonymsPhraseQuery(matchQuery.autoGenerateSynonymsPhraseQuery());
        if (matchQuery.minimumShouldMatch() != null) {
            newMatch.minimumShouldMatch(matchQuery.minimumShouldMatch());
        }
        return newMatch;
    }

    private BoolQueryBuilder replaceBool(BoolQueryBuilder boolQuery) {
        BoolQueryBuilder newBool = new BoolQueryBuilder();
        if (boolQuery.queryName() != null) newBool.queryName(boolQuery.queryName());
        newBool.boost(boolQuery.boost());
        newBool.adjustPureNegative(boolQuery.adjustPureNegative());
        if (boolQuery.minimumShouldMatch() != null) newBool.minimumShouldMatch(boolQuery.minimumShouldMatch());

        boolQuery.must().forEach(q -> newBool.must(replaceFields(q)));
        boolQuery.should().forEach(q -> newBool.should(replaceFields(q)));
        boolQuery.filter().forEach(q -> newBool.filter(replaceFields(q)));
        boolQuery.mustNot().forEach(q -> newBool.mustNot(replaceFields(q)));

        return newBool;
    }

    private BoostingQueryBuilder replaceBoosting(BoostingQueryBuilder boostingQuery) {
        QueryBuilder newPositive = replaceFields(boostingQuery.positiveQuery());
        QueryBuilder newNegative = replaceFields(boostingQuery.negativeQuery());
        BoostingQueryBuilder newBoosting = new BoostingQueryBuilder(newPositive, newNegative);
        if (boostingQuery.queryName() != null) newBoosting.queryName(boostingQuery.queryName());
        newBoosting.boost(boostingQuery.boost());
        newBoosting.negativeBoost(boostingQuery.negativeBoost());
        return newBoosting;
    }

    private ConstantScoreQueryBuilder replaceConstantScore(ConstantScoreQueryBuilder constantQuery) {
        QueryBuilder newInner = replaceFields(constantQuery.innerQuery());
        ConstantScoreQueryBuilder newCS = new ConstantScoreQueryBuilder(newInner);
        if (constantQuery.queryName() != null) newCS.queryName(constantQuery.queryName());
        newCS.boost(constantQuery.boost());
        return newCS;
    }

    private DisMaxQueryBuilder replaceDisMax(DisMaxQueryBuilder disMaxQuery) {
        DisMaxQueryBuilder newDisMax = new DisMaxQueryBuilder();
        if (disMaxQuery.queryName() != null) newDisMax.queryName(disMaxQuery.queryName());
        newDisMax.boost(disMaxQuery.boost());
        newDisMax.tieBreaker(disMaxQuery.tieBreaker());
        disMaxQuery.innerQueries().forEach(q -> newDisMax.add(replaceFields(q)));
        return newDisMax;
    }

    private FunctionScoreQueryBuilder replaceFunctionScore(FunctionScoreQueryBuilder funcQuery) {
        QueryBuilder newInner = replaceFields(funcQuery.query());
        FunctionScoreQueryBuilder newFS = new FunctionScoreQueryBuilder(newInner, funcQuery.filterFunctionBuilders());
        if (funcQuery.queryName() != null) newFS.queryName(funcQuery.queryName());
        newFS.boost(funcQuery.boost());
        if (funcQuery.boostMode() != null) newFS.boostMode(funcQuery.boostMode());
        newFS.maxBoost(funcQuery.maxBoost());
        if (funcQuery.scoreMode() != null) newFS.scoreMode(funcQuery.scoreMode());
        if (funcQuery.getMinScore() != null) newFS.setMinScore(funcQuery.getMinScore());
        return newFS;
    }

    private HybridQueryBuilder replaceHybrid(HybridQueryBuilder hybridQuery) {
        HybridQueryBuilder newHybrid = new HybridQueryBuilder();
        if (hybridQuery.queryName() != null) newHybrid.queryName(hybridQuery.queryName());
        newHybrid.boost(hybridQuery.boost());
        hybridQuery.queries().forEach(q -> newHybrid.add(replaceFields(q)));
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
            return new FieldReplacementProcessor(fieldMap, tag, description, ignoreFailure);
        }
    }
}
