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

    @SneakyThrows
    public void testEnableEnrichment_existingPipelineWithResponseProcessors_preserved() {
        createIndex(INDEX_NAME, "{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"}}}}");

        // Customer pipeline has both request and response processors
        String customerPipeline = "customer-pipeline-it";
        putSearchPipelineRaw(
            customerPipeline,
            "{\"request_processors\":[{\"filter_query\":{\"tag\":\"customer\",\"query\":{\"match_all\":{}}}}],"
                + "\"response_processors\":[{\"rename_field\":{\"field\":\"_score\",\"target_field\":\"relevance\"}}]}"
        );
        updateIndexSettings(INDEX_NAME, "{\"index.search.default_pipeline\":\"" + customerPipeline + "\"}");

        Response response = enableEnrichment(INDEX_NAME, "title", "title_semantic");

        assertEquals(200, response.getStatusLine().getStatusCode());

        // Verify response_processors survived the merge
        Map<String, Object> pipeline = getSearchPipelineRaw(INDEX_NAME + "-semantic-search-pipeline");
        java.util.List<Map<String, Object>> requestProcs = (java.util.List<Map<String, Object>>) pipeline.get("request_processors");
        assertTrue("ASE rewrite should be in merged pipeline", requestProcs.get(0).containsKey("semantic_search_rewrite_processor"));
        java.util.List<Map<String, Object>> responseProcs = (java.util.List<Map<String, Object>>) pipeline.get("response_processors");
        assertNotNull("response_processors should be preserved after merge", responseProcs);
        assertEquals(1, responseProcs.size());
        assertTrue(responseProcs.get(0).containsKey("rename_field"));
    }

    @SneakyThrows
    public void testEnableEnrichment_multipleFields_succeeds() {
        createIndex(INDEX_NAME, "{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"},\"body\":{\"type\":\"text\"}}}}");

        // Enable with multiple fields via the "fields" array syntax
        String body = "{\"fields\":["
            + "{\"original_field\":\"title\",\"semantic_field\":\"title_semantic\",\"model_type\":\"SPARSE\"},"
            + "{\"original_field\":\"body\",\"semantic_field\":\"body_semantic\",\"model_type\":\"SPARSE\"}"
            + "]}";
        Response response = makeRequest(
            client(),
            "POST",
            NEURAL_BASE_URI + "/semantic/" + INDEX_NAME + "/enable_semantic_enrichment",
            null,
            toHttpEntity(body),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );

        assertEquals(200, response.getStatusLine().getStatusCode());

        // Verify the pipeline was created with both fields in the field_map
        Map<String, Object> pipeline = getSearchPipelineRaw(INDEX_NAME + "-semantic-search-pipeline");
        java.util.List<Map<String, Object>> procs = (java.util.List<Map<String, Object>>) pipeline.get("request_processors");
        assertNotNull(procs);
        assertTrue(procs.get(0).containsKey("semantic_search_rewrite_processor"));
        @SuppressWarnings("unchecked")
        Map<String, Object> rewriteConfig = (Map<String, Object>) procs.get(0).get("semantic_search_rewrite_processor");
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldMap = (Map<String, Object>) rewriteConfig.get("field_map");
        assertTrue("field_map should contain title_semantic", fieldMap.containsKey("title_semantic"));
        assertTrue("field_map should contain body_semantic", fieldMap.containsKey("body_semantic"));
    }

    @SneakyThrows
    public void testEnableEnrichment_nonExistentSourceField_returns400() {
        createIndex(INDEX_NAME, "{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"}}}}");

        ResponseException ex = expectThrows(
            ResponseException.class,
            () -> enableEnrichment(INDEX_NAME, "nonexistent_field", "nonexistent_semantic")
        );

        assertEquals(400, ex.getResponse().getStatusLine().getStatusCode());
        String responseBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue(responseBody.contains("does not exist"));
    }

    @SneakyThrows
    public void testEnableEnrichment_incompatibleFieldType_returns400() {
        // Create index with a boolean field (not in COMPATIBLE_TYPES)
        createIndex(INDEX_NAME, "{\"mappings\":{\"properties\":{\"is_active\":{\"type\":\"boolean\"}}}}");

        ResponseException ex = expectThrows(ResponseException.class, () -> enableEnrichment(INDEX_NAME, "is_active", "is_active_semantic"));

        assertEquals(400, ex.getResponse().getStatusLine().getStatusCode());
        String responseBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue(responseBody.contains("not compatible"));
    }

    @SneakyThrows
    public void testEnableEnrichment_nonExistentIndex_returns404() {
        ResponseException ex = expectThrows(
            ResponseException.class,
            () -> enableEnrichment("index-that-does-not-exist", "title", "title_semantic")
        );

        assertEquals(404, ex.getResponse().getStatusLine().getStatusCode());
        String responseBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue(responseBody.contains("does not exist"));
    }

    @SneakyThrows
    public void testEnableEnrichment_existingPipelineWithMultipleConflicts_returns409() {
        createIndex(INDEX_NAME, "{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"}}}}");

        // Pipeline with BOTH ASE-managed processors already present — simulates calling
        // enable twice without disable in between. Both should be detected as conflicts.
        String aseNamedPipeline = INDEX_NAME + "-semantic-search-pipeline";
        putSearchPipelineRaw(
            aseNamedPipeline,
            "{\"request_processors\":["
                + "{\"semantic_search_rewrite_processor\":{\"tag\":\"ase_managed\",\"field_map\":{\"old_field\":{\"type\":\"sparse\",\"target_field\":\"old_embedding\",\"model_id\":\"old-model\"}}}},"
                + "{\"neural_sparse_two_phase_processor\":{\"tag\":\"ase_managed\",\"enabled\":true}}"
                + "]}"
        );

        ResponseException ex = expectThrows(ResponseException.class, () -> enableEnrichment(INDEX_NAME, "title", "title_semantic"));

        assertEquals(409, ex.getResponse().getStatusLine().getStatusCode());
        String responseBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue("Should mention ASE-managed conflict", responseBody.contains("ASE-managed"));
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
