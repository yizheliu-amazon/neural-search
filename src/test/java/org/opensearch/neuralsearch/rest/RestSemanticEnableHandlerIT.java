/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.rest;

import java.util.Map;

import lombok.SneakyThrows;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicHeader;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.neuralsearch.BaseNeuralSearchIT;

import com.google.common.collect.ImmutableList;

import static org.opensearch.neuralsearch.plugin.NeuralSearch.NEURAL_BASE_URI;
import static org.opensearch.neuralsearch.util.TestUtils.DEFAULT_USER_AGENT;

/**
 * REST API integration tests for the semantic enable/disable endpoints, focusing on
 * the pipeline merge conflict behavior (409) introduced by PipelineMergeUtil.
 */
public class RestSemanticEnableHandlerIT extends BaseNeuralSearchIT {

    private static final String INDEX_NAME = "semantic-enable-it-index";

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        try {
            deleteIndex(INDEX_NAME);
        } catch (Exception ignored) {}
        try {
            deleteSearchPipeline(INDEX_NAME + "-semantic-search-pipeline");
        } catch (Exception ignored) {}
        try {
            deleteSearchPipeline("customer-pipeline-it");
        } catch (Exception ignored) {}
    }

    @SneakyThrows
    public void testEnableEnrichment_noExistingPipeline_succeeds() {
        createIndex(INDEX_NAME, "{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"}}}}");

        Response response = enableEnrichment(INDEX_NAME, "title", "title_semantic");

        assertEquals(200, response.getStatusLine().getStatusCode());
        Map<String, Object> body = toMap(response);
        assertEquals(Boolean.TRUE, body.get("acknowledged"));
    }

    @SneakyThrows
    public void testEnableEnrichment_existingCompatiblePipeline_mergedSuccessfully() {
        createIndex(INDEX_NAME, "{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"}}}}");

        // Customer creates their own search pipeline with a filter_query processor,
        // and sets it as the index's default search pipeline BEFORE enabling ASE.
        String customerPipeline = "customer-pipeline-it";
        putSearchPipelineRaw(
            customerPipeline,
            "{\"request_processors\":[{\"filter_query\":{\"tag\":\"customer\",\"query\":{\"match_all\":{}}}}]}"
        );
        updateIndexSettings(INDEX_NAME, "{\"index.search.default_pipeline\":\"" + customerPipeline + "\"}");

        Response response = enableEnrichment(INDEX_NAME, "title", "title_semantic");

        assertEquals("Merge should succeed and return 200", 200, response.getStatusLine().getStatusCode());

        // Verify the resulting ASE-named pipeline contains both the ASE and customer processors
        Map<String, Object> pipeline = getSearchPipelineRaw(INDEX_NAME + "-semantic-search-pipeline");
        java.util.List<Map<String, Object>> processors = (java.util.List<Map<String, Object>>) pipeline.get("request_processors");
        assertEquals(3, processors.size());
        assertTrue(processors.get(0).containsKey("semantic_search_rewrite_processor"));
        assertTrue(processors.get(1).containsKey("neural_sparse_two_phase_processor"));
        assertTrue(processors.get(2).containsKey("filter_query"));
    }

    @SneakyThrows
    public void testEnableEnrichment_existingPipelineWithRemoveConflict_returns409() {
        createIndex(INDEX_NAME, "{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"}}}}");

        // Customer's pipeline (targeting the ASE pipeline name directly) removes 'title'
        // before ASE would rewrite/encode it — should be blocked.
        String aseNamedPipeline = INDEX_NAME + "-semantic-search-pipeline";
        // Search pipelines don't have remove/rename processors (that's ingest-only), so to
        // exercise the CONFLICT path for search we simulate an existing ASE-managed processor
        // already present, which PipelineMergeUtil detects as "already enabled".
        putSearchPipelineRaw(
            aseNamedPipeline,
            "{\"request_processors\":[{\"neural_sparse_two_phase_processor\":{\"tag\":\"ase_managed\",\"enabled\":true}}]}"
        );

        ResponseException ex = expectThrows(ResponseException.class, () -> enableEnrichment(INDEX_NAME, "title", "title_semantic"));

        assertEquals(409, ex.getResponse().getStatusLine().getStatusCode());
        String responseBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue(responseBody.contains("ASE-managed"));
    }

    // --- Helpers ---

    private Response enableEnrichment(String index, String originalField, String semanticField) throws Exception {
        String body = String.format(
            java.util.Locale.ROOT,
            "{\"original_field\":\"%s\",\"semantic_field\":\"%s\",\"model_type\":\"SPARSE\"}",
            originalField,
            semanticField
        );
        return makeRequest(
            client(),
            "POST",
            NEURAL_BASE_URI + "/semantic/" + index + "/enable_semantic_enrichment",
            null,
            toHttpEntity(body),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    private void putSearchPipelineRaw(String name, String body) throws Exception {
        makeRequest(
            client(),
            "PUT",
            "/_search/pipeline/" + name,
            null,
            toHttpEntity(body),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSearchPipelineRaw(String name) throws Exception {
        Response response = makeRequest(
            client(),
            "GET",
            "/_search/pipeline/" + name,
            null,
            null,
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
        Map<String, Object> responseMap = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(response.getEntity()),
            false
        );
        return (Map<String, Object>) responseMap.get(name);
    }

    private void updateIndexSettings(String index, String settingsJson) throws Exception {
        makeRequest(
            client(),
            "PUT",
            "/" + index + "/_settings",
            null,
            toHttpEntity(settingsJson),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Response response) throws Exception {
        return XContentHelper.convertToMap(XContentType.JSON.xContent(), EntityUtils.toString(response.getEntity()), false);
    }
}
