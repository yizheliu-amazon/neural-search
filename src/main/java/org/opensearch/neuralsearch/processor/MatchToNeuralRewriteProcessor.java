/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.BoostingQueryBuilder;
import org.opensearch.index.query.ConstantScoreQueryBuilder;
import org.opensearch.index.query.DisMaxQueryBuilder;
import org.opensearch.index.query.MatchQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.opensearch.neuralsearch.query.HybridQueryBuilder;
import org.opensearch.neuralsearch.query.NeuralQueryBuilder;
import org.opensearch.search.pipeline.AbstractProcessor;
import org.opensearch.search.pipeline.PipelineProcessingContext;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchRequestProcessor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Search request processor that rewrites match queries on specified semantic fields into
 * neural queries on the SAME field name. The NeuralQueryBuilder then handles resolution
 * to the correct embedding field, model, sparse/dense type, etc. from the mapping.
 *
 * Config: {"fields": ["title_semantic", "body_semantic"]}
 *
 * This is simpler than semantic_search_rewrite_processor — no model_id, target_field,
 * type, or analyzer needed. The neural query figures it all out from the semantic field mapping.
 */
public class MatchToNeuralRewriteProcessor extends AbstractProcessor implements SearchRequestProcessor {
    public static final String TYPE = "match_to_neural_rewrite_processor";
    public static final String FIELDS_KEY = "fields";

    private final Set<String> fields;

    protected MatchToNeuralRewriteProcessor(Set<String> fields, String tag, String description, boolean ignoreFailure) {
        super(tag, description, ignoreFailure);
        this.fields = fields;
    }

    @Override
    public SearchRequest processRequest(SearchRequest request) throws Exception {
        if (request == null || request.source() == null || request.source().query() == null) {
            return request;
        }
        QueryBuilder rewritten = rewrite(request.source().query());
        request.source().query(rewritten);
        return request;
    }

    @Override
    public void processRequestAsync(SearchRequest request, PipelineProcessingContext ctx, ActionListener<SearchRequest> listener) {
        try {
            listener.onResponse(processRequest(request));
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    @Override
    public String getType() {
        return TYPE;
    }

    private QueryBuilder rewrite(QueryBuilder query) {
        if (query instanceof MatchQueryBuilder match) {
            return rewriteMatch(match);
        } else if (query instanceof BoolQueryBuilder bool) {
            return rewriteBool(bool);
        } else if (query instanceof DisMaxQueryBuilder disMax) {
            return rewriteDisMax(disMax);
        } else if (query instanceof BoostingQueryBuilder boosting) {
            return rewriteBoosting(boosting);
        } else if (query instanceof ConstantScoreQueryBuilder cs) {
            return rewriteConstantScore(cs);
        } else if (query instanceof FunctionScoreQueryBuilder fs) {
            return rewriteFunctionScore(fs);
        } else if (query instanceof HybridQueryBuilder hybrid) {
            return rewriteHybrid(hybrid);
        }
        return query;
    }

    private QueryBuilder rewriteMatch(MatchQueryBuilder match) {
        if (!fields.contains(match.fieldName())) {
            return match;
        }
        // Rewrite to neural query on the same field — NeuralQueryBuilder resolves everything from mapping
        NeuralQueryBuilder neural = NeuralQueryBuilder.builder().fieldName(match.fieldName()).queryText(match.value().toString()).build();
        if (match.boost() != 1.0f) neural.boost(match.boost());
        if (match.queryName() != null) neural.queryName(match.queryName());
        return neural;
    }

    private QueryBuilder rewriteBool(BoolQueryBuilder bool) {
        BoolQueryBuilder newBool = new BoolQueryBuilder();
        if (bool.queryName() != null) newBool.queryName(bool.queryName());
        newBool.boost(bool.boost());
        newBool.adjustPureNegative(bool.adjustPureNegative());
        if (bool.minimumShouldMatch() != null) newBool.minimumShouldMatch(bool.minimumShouldMatch());
        bool.must().forEach(q -> newBool.must(rewrite(q)));
        bool.should().forEach(q -> newBool.should(rewrite(q)));
        bool.filter().forEach(newBool::filter);
        bool.mustNot().forEach(newBool::mustNot);
        return newBool;
    }

    private QueryBuilder rewriteDisMax(DisMaxQueryBuilder disMax) {
        DisMaxQueryBuilder n = new DisMaxQueryBuilder();
        if (disMax.queryName() != null) n.queryName(disMax.queryName());
        n.boost(disMax.boost());
        n.tieBreaker(disMax.tieBreaker());
        disMax.innerQueries().forEach(q -> n.add(rewrite(q)));
        return n;
    }

    private QueryBuilder rewriteBoosting(BoostingQueryBuilder boosting) {
        QueryBuilder pos = rewrite(boosting.positiveQuery());
        BoostingQueryBuilder n = new BoostingQueryBuilder(pos, boosting.negativeQuery());
        if (boosting.queryName() != null) n.queryName(boosting.queryName());
        n.boost(boosting.boost());
        n.negativeBoost(boosting.negativeBoost());
        return n;
    }

    private QueryBuilder rewriteConstantScore(ConstantScoreQueryBuilder cs) {
        QueryBuilder inner = rewrite(cs.innerQuery());
        ConstantScoreQueryBuilder n = new ConstantScoreQueryBuilder(inner);
        if (cs.queryName() != null) n.queryName(cs.queryName());
        n.boost(cs.boost());
        return n;
    }

    private QueryBuilder rewriteFunctionScore(FunctionScoreQueryBuilder fs) {
        QueryBuilder inner = rewrite(fs.query());
        FunctionScoreQueryBuilder n = new FunctionScoreQueryBuilder(inner, fs.filterFunctionBuilders());
        if (fs.queryName() != null) n.queryName(fs.queryName());
        n.boost(fs.boost());
        if (fs.boostMode() != null) n.boostMode(fs.boostMode());
        n.maxBoost(fs.maxBoost());
        if (fs.scoreMode() != null) n.scoreMode(fs.scoreMode());
        if (fs.getMinScore() != null) n.setMinScore(fs.getMinScore());
        return n;
    }

    private QueryBuilder rewriteHybrid(HybridQueryBuilder hybrid) {
        HybridQueryBuilder n = new HybridQueryBuilder();
        if (hybrid.queryName() != null) n.queryName(hybrid.queryName());
        n.boost(hybrid.boost());
        hybrid.queries().forEach(q -> n.add(rewrite(q)));
        return n;
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
            @SuppressWarnings("unchecked")
            List<String> fieldsList = (List<String>) config.remove(FIELDS_KEY);
            if (fieldsList == null) throw new IllegalArgumentException("[" + TYPE + "] requires [fields] list");
            return new MatchToNeuralRewriteProcessor(Set.copyOf(fieldsList), tag, description, ignoreFailure);
        }
    }
}
