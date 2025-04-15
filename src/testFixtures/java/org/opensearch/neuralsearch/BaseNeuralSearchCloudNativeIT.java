/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch;

import static org.apache.http.entity.ContentType.APPLICATION_JSON;
import static org.opensearch.neuralsearch.common.VectorUtil.vectorAsListToArray;
import static org.opensearch.neuralsearch.util.TestUtils.DEFAULT_USER_AGENT;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.opensearch.client.Request;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.client.WarningsHandler;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.knn.index.SpaceType;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;
import com.google.common.collect.ImmutableList;
import org.opensearch.neuralsearch.util.TokenWeightUtil;
import org.opensearch.search.sort.SortBuilder;

@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public abstract class BaseNeuralSearchCloudNativeIT extends OpenSearchSecureRestCloudNativeTestCase {

    protected static final Locale LOCALE = Locale.ROOT;

    private static final int MAX_TASK_RESULT_QUERY_TIME_IN_SECOND = 60 * 5;

    private static final int DEFAULT_TASK_RESULT_QUERY_INTERVAL_IN_MILLISECOND = 1000;

    protected static final String DEFAULT_USER_AGENT = "Kibana";
    protected static final String DEFAULT_NORMALIZATION_METHOD = "min_max";
    protected static final String DEFAULT_COMBINATION_METHOD = "arithmetic_mean";
    protected static final String PARAM_NAME_WEIGHTS = "weights";

    protected static final Map<ProcessorType, String> PIPELINE_CONFIGS_BY_TYPE = Map.of(
        ProcessorType.TEXT_EMBEDDING,
        "processor/PipelineConfiguration.json",
        ProcessorType.SPARSE_ENCODING,
        "processor/SparseEncodingPipelineConfiguration.json",
        ProcessorType.TEXT_IMAGE_EMBEDDING,
        "processor/PipelineForTextImageEmbeddingProcessorConfiguration.json"
    );

    private static final Set<RestStatus> SUCCESS_STATUSES = Set.of(RestStatus.CREATED, RestStatus.OK);

    protected final ClassLoader classLoader = this.getClass().getClassLoader();

    // Titan with parameters.inputText
    // protected static final String MODEL_ID = "cef729bb-66a2-4189-a012-70fb4093b43e";
    // Cohere with parameters.texts
    protected static final String MODEL_ID = "20376cc7-d5ed-4064-8b00-4351c2f2283e";

    // Cohere with parameters.texts
    protected static final String SPARSE_MODEL_ID = "2641d411-5010-4ec4-b006-a5a3d9258cd8";

    /**
     * Execute model inference on the provided query text
     *
     * @param modelId id of model to run inference
     * @param queryText text to be transformed to a model
     * @return text embedding
     */
    @SuppressWarnings("unchecked")
    @SneakyThrows
    protected float[] runInference(String modelId, String queryText) {
        Response inferenceResponse = makeRequest(
            client(),
            "POST",
            String.format(LOCALE, "/_plugins/_ml/_predict/text_embedding/%s", modelId),
            null,
            toHttpEntity(String.format(LOCALE, "{\"text_docs\": [\"%s\"],\"target_response\": [\"sentence_embedding\"]}", queryText)),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, "Kibana"))
        );

        Map<String, Object> inferenceResJson = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(inferenceResponse.getEntity()),
            false
        );

        Object inference_results = inferenceResJson.get("inference_results");
        assertTrue(inference_results instanceof List);
        List<Object> inferenceResultsAsMap = (List<Object>) inference_results;
        assertEquals(1, inferenceResultsAsMap.size());
        Map<String, Object> result = (Map<String, Object>) inferenceResultsAsMap.get(0);
        List<Object> output = (List<Object>) result.get("output");
        assertEquals(1, output.size());
        Map<String, Object> map = (Map<String, Object>) output.get(0);
        List<Float> data = ((List<Double>) map.get("data")).stream().map(Double::floatValue).collect(Collectors.toList());
        return vectorAsListToArray(data);
    }

    protected void createIndexWithConfiguration(String indexName, String indexConfiguration, String pipelineName) throws Exception {
        if (StringUtils.isNotBlank(pipelineName)) {
            indexConfiguration = String.format(LOCALE, indexConfiguration, pipelineName);
        }
        Response response = makeRequest(
            client(),
            "PUT",
            '/' + indexName,
            null,
            toHttpEntity(indexConfiguration),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, "Kibana"))
        );
        Map<String, Object> node = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(response.getEntity()),
            false
        );
        assertEquals("true", node.get("acknowledged").toString());
        assertEquals(indexName, node.get("index").toString());
    }

    protected void createPipelineProcessor(String modelId, String pipelineName) throws Exception {
        createPipelineProcessor(modelId, pipelineName, ProcessorType.TEXT_EMBEDDING);
    }

    protected void createPipelineProcessor(String modelId, String pipelineName, ProcessorType processorType) throws Exception {
        Response pipelineCreateResponse = makeRequest(
            client(),
            "PUT",
            "/_ingest/pipeline/" + pipelineName,
            null,
            toHttpEntity(
                String.format(
                    LOCALE,
                    Files.readString(Path.of(classLoader.getResource(PIPELINE_CONFIGS_BY_TYPE.get(processorType)).toURI())),
                    modelId
                )
            ),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, "Kibana"))
        );
        Map<String, Object> node = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(pipelineCreateResponse.getEntity()),
            false
        );
        assertEquals("true", node.get("acknowledged").toString());
    }

    protected void createSearchRequestProcessor(String modelId, String pipelineName) throws Exception {
        Response pipelineCreateResponse = makeRequest(
            client(),
            "PUT",
            "/_search/pipeline/" + pipelineName,
            null,
            toHttpEntity(
                String.format(
                    LOCALE,
                    Files.readString(Path.of(classLoader.getResource("processor/SearchRequestPipelineConfiguration.json").toURI())),
                    modelId
                )
            ),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
        Map<String, Object> node = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(pipelineCreateResponse.getEntity()),
            false
        );
        assertEquals("true", node.get("acknowledged").toString());
    }

    protected void createNeuralSparseTwoPhaseSearchProcessor(final String pipelineName) throws Exception {
        createNeuralSparseTwoPhaseSearchProcessor(pipelineName, 0.4f, 5.0f, 10000);
    }

    protected void createNeuralSparseTwoPhaseSearchProcessor(
        final String pipelineName,
        float pruneRatio,
        float expansionRate,
        int maxWindowSize
    ) throws Exception {
        String jsonTemplate = Files.readString(
            Path.of(Objects.requireNonNull(classLoader.getResource("processor/NeuralSparseTwoPhaseProcessorConfiguration.json")).toURI())
        );
        String customizedJson = String.format(Locale.ROOT, jsonTemplate, pruneRatio, expansionRate, maxWindowSize);
        Response pipelineCreateResponse = makeRequest(
            client(),
            "PUT",
            "/_search/pipeline/" + pipelineName,
            null,
            toHttpEntity(customizedJson),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
        Map<String, Object> node = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(pipelineCreateResponse.getEntity()),
            false
        );
        assertEquals("true", node.get("acknowledged").toString());
    }

    protected void createSearchPipelineViaConfig(String modelId, String pipelineName, String configPath) throws Exception {
        Response pipelineCreateResponse = makeRequest(
            client(),
            "PUT",
            "/_search/pipeline/" + pipelineName,
            null,
            toHttpEntity(String.format(LOCALE, Files.readString(Path.of(classLoader.getResource(configPath).toURI())), modelId)),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
        Map<String, Object> node = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(pipelineCreateResponse.getEntity()),
            false
        );
        assertEquals("true", node.get("acknowledged").toString());
    }

    /**
     * Get the number of documents in a particular index
     *
     * @param indexName name of index
     * @return number of documents indexed to that index
     */
    @SneakyThrows
    protected int getDocCount(String indexName) {
        Request request = new Request("GET", "/" + indexName + "/_count");
        Response response = client().performRequest(request);
        assertEquals(request.getEndpoint() + ": failed", RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
        String responseBody = EntityUtils.toString(response.getEntity());
        Map<String, Object> responseMap = createParser(XContentType.JSON.xContent(), responseBody).map();
        return (Integer) responseMap.get("count");
    }

    /**
     * Get one doc by its id
     * @param indexName index name
     * @param id doc id
     * @return map of the doc data
     */
    @SneakyThrows
    protected Map<String, Object> getDocById(final String indexName, final String id) {
        Request request = new Request("GET", "/" + indexName + "/_doc/" + id);
        Response response = client().performRequest(request);
        assertEquals(request.getEndpoint() + ": failed", RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
        String responseBody = EntityUtils.toString(response.getEntity());
        return createParser(XContentType.JSON.xContent(), responseBody).map();
    }

    /**
     * Execute a search request initialized from a neural query builder
     *
     * @param index Index to search against
     * @param queryBuilder queryBuilder to produce source of query
     * @param resultSize number of results to return in the search
     * @return Search results represented as a map
     */
    protected Map<String, Object> search(String index, QueryBuilder queryBuilder, int resultSize) {
        return search(index, queryBuilder, null, resultSize);
    }

    /**
     * Execute a search request initialized from a neural query builder that can add a rescore query to the request
     *
     * @param index Index to search against
     * @param queryBuilder queryBuilder to produce source of query
     * @param  rescorer used for rescorer query builder
     * @param resultSize number of results to return in the search
     * @return Search results represented as a map
     */
    @SneakyThrows
    protected Map<String, Object> search(String index, QueryBuilder queryBuilder, QueryBuilder rescorer, int resultSize) {
        return search(index, queryBuilder, rescorer, resultSize, Map.of());
    }

    @SneakyThrows
    protected Map<String, Object> search(
        String index,
        QueryBuilder queryBuilder,
        QueryBuilder rescorer,
        int resultSize,
        Map<String, String> requestParams,
        List<Object> aggs
    ) {
        return search(index, queryBuilder, rescorer, resultSize, requestParams, aggs, null, null, false, null, 0);
    }

    /**
     * Execute a search request initialized from a neural query builder that can add a rescore query to the request
     *
     * @param index Index to search against
     * @param queryBuilder queryBuilder to produce source of query
     * @param  rescorer used for rescorer query builder
     * @param resultSize number of results to return in the search
     * @param requestParams additional request params for search
     * @return Search results represented as a map
     */
    @SneakyThrows
    protected Map<String, Object> search(
        String index,
        QueryBuilder queryBuilder,
        QueryBuilder rescorer,
        int resultSize,
        Map<String, String> requestParams
    ) {
        System.out.println("Sleeping 60 secs before searching");
        Thread.sleep(60 * 1000);
        XContentBuilder builder = XContentFactory.jsonBuilder().startObject().field("query");
        queryBuilder.toXContent(builder, ToXContent.EMPTY_PARAMS);

        if (rescorer != null) {
            builder.startObject("rescore").startObject("query").field("query_weight", 0.0f).field("rescore_query");
            rescorer.toXContent(builder, ToXContent.EMPTY_PARAMS);
            builder.endObject().endObject();
        }

        builder.endObject();

        Request request = new Request("POST", "/" + index + "/_search");
        request.addParameter("size", Integer.toString(resultSize));
        if (requestParams != null && !requestParams.isEmpty()) {
            requestParams.forEach(request::addParameter);
        }
        request.setJsonEntity(builder.toString());

        Response response = client().performRequest(request);
        assertEquals(request.getEndpoint() + ": failed", RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));

        String responseBody = EntityUtils.toString(response.getEntity());

        return XContentHelper.convertToMap(XContentType.JSON.xContent(), responseBody, false);
    }

    @SneakyThrows
    protected Map<String, Object> search(
        String index,
        QueryBuilder queryBuilder,
        QueryBuilder rescorer,
        int resultSize,
        Map<String, String> requestParams,
        List<Object> aggs,
        QueryBuilder postFilterBuilder,
        List<SortBuilder<?>> sortBuilders,
        boolean trackScores,
        List<Object> searchAfter,
        int from
    ) {
        System.out.println("Sleeping 60 secs before searching");
        Thread.sleep(60 * 1000);
        XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
        builder.field("from", from);
        if (queryBuilder != null) {
            builder.field("query");
            queryBuilder.toXContent(builder, ToXContent.EMPTY_PARAMS);
        }

        if (rescorer != null) {
            builder.startObject("rescore").startObject("query").field("query_weight", 0.0f).field("rescore_query");
            rescorer.toXContent(builder, ToXContent.EMPTY_PARAMS);
            builder.endObject().endObject();
        }
        if (Objects.nonNull(aggs)) {
            builder.startObject("aggs");
            for (Object agg : aggs) {
                builder.value(agg);
            }
            builder.endObject();
        }
        if (Objects.nonNull(postFilterBuilder)) {
            builder.field("post_filter");
            postFilterBuilder.toXContent(builder, ToXContent.EMPTY_PARAMS);
        }
        if (Objects.nonNull(sortBuilders) && !sortBuilders.isEmpty()) {
            builder.startArray("sort");
            for (SortBuilder sortBuilder : sortBuilders) {
                sortBuilder.toXContent(builder, ToXContent.EMPTY_PARAMS);
            }
            builder.endArray();
        }

        if (trackScores) {
            builder.field("track_scores", trackScores);
        }
        if (searchAfter != null && !searchAfter.isEmpty()) {
            builder.startArray("search_after");
            for (Object searchAfterEntry : searchAfter) {
                builder.value(searchAfterEntry);
            }
            builder.endArray();
        }

        builder.endObject();

        Request request = new Request("GET", "/" + index + "/_search?timeout=1000s");
        request.addParameter("size", Integer.toString(resultSize));
        if (requestParams != null && !requestParams.isEmpty()) {
            requestParams.forEach(request::addParameter);
        }
        System.out.println("Sorting request  " + builder.toString());
        request.setJsonEntity(builder.toString());
        Response response = client().performRequest(request);
        assertEquals(request.getEndpoint() + ": failed", RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));

        String responseBody = EntityUtils.toString(response.getEntity());
        System.out.println("Response  " + responseBody);
        return XContentHelper.convertToMap(XContentType.JSON.xContent(), responseBody, false);
    }

    @SneakyThrows
    protected String buildIndexConfiguration(
        final List<BaseNeuralSearchIT.KNNFieldConfig> knnFieldConfigs,
        final List<String> nestedFields,
        final List<String> intFields,
        final List<String> keywordFields,
        final List<String> dateFields,
        final int numberOfShards
    ) {
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("settings")
            .field("number_of_shards", numberOfShards)
            .field("index.knn", true)
            .endObject()
            .startObject("mappings")
            .startObject("properties");

        for (BaseNeuralSearchIT.KNNFieldConfig knnFieldConfig : knnFieldConfigs) {
            xContentBuilder.startObject(knnFieldConfig.getName())
                .field("type", "knn_vector")
                .field("dimension", Integer.toString(knnFieldConfig.getDimension()))
                .startObject("method")
                .field("engine", "lucene")
                .field("space_type", knnFieldConfig.getSpaceType().getValue())
                .field("name", "hnsw")
                .endObject()
                .endObject();
        }
        // treat the list in a manner that first element is always the type name and all others are keywords
        if (!nestedFields.isEmpty()) {
            String nestedFieldName = nestedFields.get(0);
            xContentBuilder.startObject(nestedFieldName).field("type", "nested");
            if (nestedFields.size() > 1) {
                xContentBuilder.startObject("properties");
                for (int i = 1; i < nestedFields.size(); i++) {
                    String innerNestedTypeField = nestedFields.get(i);
                    xContentBuilder.startObject(innerNestedTypeField).field("type", "keyword").endObject();
                }
                xContentBuilder.endObject();
            }
            xContentBuilder.endObject();
        }

        for (String intField : intFields) {
            xContentBuilder.startObject(intField).field("type", "integer").endObject();
        }

        for (String keywordField : keywordFields) {
            xContentBuilder.startObject(keywordField).field("type", "keyword").endObject();
        }

        for (String dateField : dateFields) {
            xContentBuilder.startObject(dateField).field("type", "date").field("format", "MM/dd/yyyy").endObject();
        }

        xContentBuilder.endObject().endObject().endObject();
        return xContentBuilder.toString();
    }

    /**
     * Add a set of knn vectors
     *
     * @param index Name of the index
     * @param vectorFieldNames List of vectir fields to be added
     * @param vectors List of vectors corresponding to those fields
     */
    protected void addKnnDoc(String index, List<String> vectorFieldNames, List<Object[]> vectors) {
        addKnnDoc(index, vectorFieldNames, vectors, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Add a set of knn vectors and text to an index
     *
     * @param index Name of the index
     * @param vectorFieldNames List of vectir fields to be added
     * @param vectors List of vectors corresponding to those fields
     * @param textFieldNames List of text fields to be added
     * @param texts List of text corresponding to those fields
     */
    @SneakyThrows
    protected void addKnnDoc(
        String index,
        List<String> vectorFieldNames,
        List<Object[]> vectors,
        List<String> textFieldNames,
        List<String> texts
    ) {
        Request request = new Request("POST", "/" + index + "/_doc");
        XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
        for (int i = 0; i < vectorFieldNames.size(); i++) {
            builder.field(vectorFieldNames.get(i), vectors.get(i));
        }

        for (int i = 0; i < textFieldNames.size(); i++) {
            builder.field(textFieldNames.get(i), texts.get(i));
        }
        builder.endObject();
        Response response = makeRequest(
            client(),
            "POST",
            "/" + index + "/_doc",
            null,
            toHttpEntity(builder.toString()),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, "Kibana"))
        );
        assertEquals(request.getEndpoint() + ": failed", RestStatus.CREATED, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
    }

    /**
     * Add a set of knn vectors and text to an index
     *
     * @param index Name of the index
     * @param docId ID of document to be added
     * @param vectorFieldNames List of vectir fields to be added
     * @param vectors List of vectors corresponding to those fields
     * @param textFieldNames List of text fields to be added
     * @param texts List of text corresponding to those fields
     * @param nestedFieldNames List of nested fields to be added
     * @param nestedFields List of fields and values corresponding to those fields
     */
    @SneakyThrows
    protected void addKnnDoc(
        final String index,
        final String docId,
        final List<String> vectorFieldNames,
        final List<Object[]> vectors,
        final List<String> textFieldNames,
        final List<String> texts,
        final List<String> nestedFieldNames,
        final List<Map<String, String>> nestedFields,
        final List<String> integerFieldNames,
        final List<Integer> integerFieldValues,
        final List<String> keywordFieldNames,
        final List<String> keywordFieldValues,
        final List<String> dateFieldNames,
        final List<String> dateFieldValues
    ) {
        // Request request = new Request("POST", "/" + index + "/_doc/" + docId + "?refresh=true");
        Request request = new Request("POST", "/" + index + "/_doc");
        XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
        for (int i = 0; i < vectorFieldNames.size(); i++) {
            builder.field(vectorFieldNames.get(i), vectors.get(i));
        }

        for (int i = 0; i < textFieldNames.size(); i++) {
            builder.field(textFieldNames.get(i), texts.get(i));
        }

        for (int i = 0; i < nestedFieldNames.size(); i++) {
            builder.field(nestedFieldNames.get(i));
            builder.startObject();
            Map<String, String> nestedValues = nestedFields.get(i);
            for (Map.Entry<String, String> entry : nestedValues.entrySet()) {
                builder.field(entry.getKey(), entry.getValue());
            }
            builder.endObject();
        }

        for (int i = 0; i < integerFieldNames.size(); i++) {
            builder.field(integerFieldNames.get(i), integerFieldValues.get(i));
        }

        for (int i = 0; i < keywordFieldNames.size(); i++) {
            builder.field(keywordFieldNames.get(i), keywordFieldValues.get(i));
        }

        for (int i = 0; i < dateFieldNames.size(); i++) {
            builder.field(dateFieldNames.get(i), dateFieldValues.get(i));
        }
        builder.endObject();

        request.setJsonEntity(builder.toString());
        Response response = client().performRequest(request);
        assertTrue(
            request.getEndpoint() + ": failed",
            SUCCESS_STATUSES.contains(RestStatus.fromCode(response.getStatusLine().getStatusCode()))
        );
    }

    /**
     * Parse the first returned hit from a search response as a map
     *
     * @param searchResponseAsMap Complete search response as a map
     * @return Map of first internal hit from the search
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> getFirstInnerHit(Map<String, Object> searchResponseAsMap) {
        Map<String, Object> hits1map = (Map<String, Object>) searchResponseAsMap.get("hits");
        List<Object> hits2List = (List<Object>) hits1map.get("hits");
        assertFalse(hits2List.isEmpty());
        return (Map<String, Object>) hits2List.get(0);
    }

    /**
     * Parse the total number of hits from the search
     *
     * @param searchResponseAsMap Complete search response as a map
     * @return number of hits from the search
     */
    @SuppressWarnings("unchecked")
    protected int getHitCount(Map<String, Object> searchResponseAsMap) {
        Map<String, Object> hits1map = (Map<String, Object>) searchResponseAsMap.get("hits");
        List<Object> hits1List = (List<Object>) hits1map.get("hits");
        return hits1List.size();
    }

    /**
     * Create a k-NN index from a list of KNNFieldConfigs
     *
     * @param indexName of index to be created
     * @param knnFieldConfigs list of configs specifying field
     */
    @SneakyThrows
    protected void prepareKnnIndex(String indexName, List<KNNFieldConfig> knnFieldConfigs) {
        createIndexWithConfiguration(indexName, buildIndexConfiguration(knnFieldConfigs), "");
    }

    protected float computeExpectedScore(final Map<String, Float> tokenWeightMap, final Map<String, Float> queryTokens) {
        Float score = 0f;
        for (Map.Entry<String, Float> entry : queryTokens.entrySet()) {
            if (tokenWeightMap.containsKey(entry.getKey())) {
                score += entry.getValue() * getFeatureFieldCompressedNumber(tokenWeightMap.get(entry.getKey()));
            }
        }
        return score;
    }

    protected float computeExpectedScore(final String modelId, final Map<String, Float> tokenWeightMap, final String queryText) {
        Map<String, Float> queryTokens = runSparseModelInference(modelId, queryText);
        return computeExpectedScore(tokenWeightMap, queryTokens);
    }

    @SneakyThrows
    protected Map<String, Float> runSparseModelInference(final String modelId, final String queryText) {
        Response inferenceResponse = makeRequest(
            client(),
            "POST",
            String.format(LOCALE, "/_plugins/_ml/models/%s/_predict", modelId),
            null,
            // toHttpEntity(String.format(LOCALE, "{\"text_docs\": [\"%s\"]}", queryText)),
            toHttpEntity(String.format(LOCALE, "{\"parameters\": { \"texts\": [\"%s\"]}}", queryText)),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );

        Map<String, Object> inferenceResJson = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(inferenceResponse.getEntity()),
            false
        );

        Object inference_results = inferenceResJson.get("inference_results");
        assertTrue(inference_results instanceof List);
        List<Object> inferenceResultsAsMap = (List<Object>) inference_results;
        assertEquals(1, inferenceResultsAsMap.size());
        Map<String, Object> result = (Map<String, Object>) inferenceResultsAsMap.get(0);
        List<Object> output = (List<Object>) result.get("output");
        assertEquals(1, output.size());
        Map<String, Object> map = (Map<String, Object>) output.get(0);
        // assertEquals(1, map.size());
        Map<String, Object> dataAsMap = (Map<String, Object>) map.get("dataAsMap");
        return TokenWeightUtil.fetchListOfTokenWeightMap(List.of(dataAsMap)).get(0);
    }

    @SneakyThrows
    protected void prepareSparseEncodingIndex(final String indexName, final List<String> sparseEncodingFieldNames) {
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("mappings").startObject("properties");

        for (String fieldName : sparseEncodingFieldNames) {
            xContentBuilder.startObject(fieldName).field("type", "rank_features").endObject();
        }

        xContentBuilder.endObject().endObject().endObject();
        String indexMappings = xContentBuilder.toString();
        createIndexWithConfiguration(indexName, indexMappings, "");
    }

    @SneakyThrows
    protected void addSparseEncodingDoc(
        final String index,
        final String docId,
        final List<String> fieldNames,
        final List<Map<String, Float>> docs
    ) {
        addSparseEncodingDoc(index, docId, fieldNames, docs, Collections.emptyList(), Collections.emptyList());
    }

    @SneakyThrows
    protected void addSparseEncodingDoc(
        final String index,
        final String docId,
        final List<String> fieldNames,
        final List<Map<String, Float>> docs,
        final List<String> textFieldNames,
        final List<String> texts
    ) {
        Request request = new Request("POST", "/" + index + "/_doc");// /" + docId + "?refresh=true");
        XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
        for (int i = 0; i < fieldNames.size(); i++) {
            builder.field(fieldNames.get(i), docs.get(i));
        }

        for (int i = 0; i < textFieldNames.size(); i++) {
            builder.field(textFieldNames.get(i), texts.get(i));
        }
        builder.endObject();

        request.setJsonEntity(builder.toString());
        Response response = client().performRequest(request);
        assertEquals(request.getEndpoint() + ": failed", RestStatus.CREATED, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
    }

    // rank_features use lucene FeatureField, which will compress the Float number to 16 bit
    // this function simulate the encoding and decoding progress in lucene FeatureField
    protected Float getFeatureFieldCompressedNumber(final Float originNumber) {
        int freqBits = Float.floatToIntBits(originNumber);
        freqBits = freqBits >> 15;
        freqBits = ((int) ((float) freqBits)) << 15;
        return Float.intBitsToFloat(freqBits);
    }

    /**
     * Computes the expected distance between an indexVector and query text without using the neural query type.
     *
     * @param modelId ID of model to run inference
     * @param indexVector vector to compute score against
     * @param spaceType Space to measure distance
     * @param queryText Text to produce query vector from
     * @return Expected OpenSearch score for this indexVector
     */
    protected float computeExpectedScore(String modelId, float[] indexVector, SpaceType spaceType, String queryText) {
        float[] queryVector = runInference(modelId, queryText);
        return spaceType.getKnnVectorSimilarityFunction().compare(queryVector, indexVector);
    }

    @SneakyThrows
    private String buildIndexConfiguration(List<KNNFieldConfig> knnFieldConfigs) {
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("settings")
            .field("index.knn", true)
            .endObject()
            .startObject("mappings")
            .startObject("properties");

        // Lucene engine is not supported on AOSS
        for (KNNFieldConfig knnFieldConfig : knnFieldConfigs) {
            xContentBuilder.startObject(knnFieldConfig.getName())
                .field("type", "knn_vector")
                .field("dimension", Integer.toString(knnFieldConfig.getDimension()))
                .startObject("method")
                .field("space_type", knnFieldConfig.getSpaceType().getValue())
                .field("name", "hnsw")
                .endObject()
                .endObject();
        }
        xContentBuilder.endObject().endObject().endObject();
        return xContentBuilder.toString();
    }

    protected static Response makeRequest(
        RestClient client,
        String method,
        String endpoint,
        Map<String, String> params,
        HttpEntity entity,
        List<Header> headers
    ) throws IOException {
        return makeRequest(client, method, endpoint, params, entity, headers, false);
    }

    protected static Response makeRequest(
        RestClient client,
        String method,
        String endpoint,
        Map<String, String> params,
        HttpEntity entity,
        List<Header> headers,
        boolean strictDeprecationMode
    ) throws IOException {
        Request request = new Request(method, endpoint);

        RequestOptions.Builder options = RequestOptions.DEFAULT.toBuilder();
        if (headers != null) {
            headers.forEach(header -> options.addHeader(header.getName(), header.getValue()));
        }
        options.setWarningsHandler(strictDeprecationMode ? WarningsHandler.STRICT : WarningsHandler.PERMISSIVE);
        request.setOptions(options.build());

        if (params != null) {
            params.forEach(request::addParameter);
        }
        if (entity != null) {
            request.setEntity(entity);
        }
        System.out.println(
            request.getOptions().getHeaders().toString() + " in BaseNeuralSearchCloudNativeIT " + EntityUtils.toString(request.getEntity())
        );
        return client.performRequest(request);
    }

    protected static HttpEntity toHttpEntity(String jsonString) {
        return new StringEntity(jsonString, APPLICATION_JSON);
    }

    @AllArgsConstructor
    @Getter
    protected static class KNNFieldConfig {
        private final String name;
        private final Integer dimension;
        private final SpaceType spaceType;
    }

    @SneakyThrows
    protected void createSearchPipelineWithResultsPostProcessor(final String pipelineId) {
        createSearchPipeline(pipelineId, DEFAULT_NORMALIZATION_METHOD, DEFAULT_COMBINATION_METHOD, Map.of());
    }

    @SneakyThrows
    protected void createSearchPipeline(
        final String pipelineId,
        final String normalizationMethod,
        String combinationMethod,
        final Map<String, String> combinationParams
    ) {
        StringBuilder stringBuilderForContentBody = new StringBuilder();
        stringBuilderForContentBody.append("{\"description\": \"Post processor pipeline\",")
            .append("\"phase_results_processors\": [{ ")
            .append("\"normalization-processor\": {")
            .append("\"normalization\": {")
            .append("\"technique\": \"%s\"")
            .append("},")
            .append("\"combination\": {")
            .append("\"technique\": \"%s\"");
        if (Objects.nonNull(combinationParams) && !combinationParams.isEmpty()) {
            stringBuilderForContentBody.append(", \"parameters\": {");
            if (combinationParams.containsKey(PARAM_NAME_WEIGHTS)) {
                stringBuilderForContentBody.append("\"weights\": ").append(combinationParams.get(PARAM_NAME_WEIGHTS));
            }
            stringBuilderForContentBody.append(" }");
        }
        stringBuilderForContentBody.append("}").append("}}]}");
        makeRequest(
            client(),
            "PUT",
            String.format(LOCALE, "/_search/pipeline/%s", pipelineId),
            null,
            toHttpEntity(String.format(LOCALE, stringBuilderForContentBody.toString(), normalizationMethod, combinationMethod)),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    protected void wipeOfTestResources(
        final String indexName,
        final String ingestPipeline,
        final String modelId,
        final String searchPipeline
    ) throws IOException {
        if (ingestPipeline != null) {
            deletePipeline(ingestPipeline);
        }
        if (searchPipeline != null) {
            deleteSearchPipeline(searchPipeline);
        }
        // if (modelId != null) {
        // try {
        // deleteModel(modelId);
        // } catch (AssertionError e) {
        // // sometimes we have flaky test that the model state doesn't change after call undeploy api
        // // for this case we can call undeploy api one more time
        // deleteModel(modelId);
        // }
        // }
        if (indexName != null) {
            deleteIndex(indexName);
        }
    }

    /**
     * Delete pipeline
     *
     * @param pipelineName of the pipeline
     *
     * @return delete pipeline response as a map object
     */
    @SneakyThrows
    protected Map<String, Object> deletePipeline(final String pipelineName) {
        Request request = new Request("DELETE", "/_ingest/pipeline/" + pipelineName);
        Response response = client().performRequest(request);
        assertEquals(request.getEndpoint() + ": failed", RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
        String responseBody = EntityUtils.toString(response.getEntity());
        Map<String, Object> responseMap = createParser(XContentType.JSON.xContent(), responseBody).map();
        return responseMap;
    }

    @SneakyThrows
    protected void createSearchPipelineWithDefaultResultsPostProcessor(final String pipelineId) {
        makeRequest(
            client(),
            "PUT",
            String.format(LOCALE, "/_search/pipeline/%s", pipelineId),
            null,
            toHttpEntity(
                String.format(
                    LOCALE,
                    "{\"description\": \"Post processor pipeline\","
                        + "\"phase_results_processors\": [{ "
                        + "\"normalization-processor\": {}}]}"
                )
            ),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    @SneakyThrows
    protected void deleteSearchPipeline(final String pipelineId) {
        makeRequest(
            client(),
            "DELETE",
            String.format(LOCALE, "/_search/pipeline/%s", pipelineId),
            null,
            toHttpEntity(""),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    /**
     * Enumeration for types of pipeline processors, used to lookup resources like create
     * processor request as those are type specific
     */
    protected enum ProcessorType {
        TEXT_EMBEDDING,
        TEXT_IMAGE_EMBEDDING,
        SPARSE_ENCODING
    }
}
