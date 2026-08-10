/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.rest;

import java.util.List;
import java.util.Locale;
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
 * Integration tests for ASE deploy/rollback/disable lifecycle operations.
 *
 * Covers all pipeline ownership scenarios:
 * 1. ASE owns the pipeline (created fresh by enable) — deploy/rollback/disable
 * 2. ASE merged into customer's pipeline — deploy/rollback/disable
 * 3. Full lifecycle: enable → deploy → rollback → deploy → disable
 * 4. Error cases: deploy before enable, rollback when not deployed, etc.
 */
public class RestSemanticLifecycleIT extends BaseNeuralSearchIT {

    private static final String INDEX_NAME = "ase-lifecycle-it";
    private static final String CUSTOMER_INGEST_PIPELINE = "customer-lifecycle-ingest";
    private static final String CUSTOMER_SEARCH_PIPELINE = "customer-lifecycle-search";
    private static final String ASE_INGEST_PIPELINE = INDEX_NAME + "-ase-ingest-pipeline";
    private static final String ASE_SEARCH_PIPELINE = INDEX_NAME + "-ase-search-pipeline";

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        safeDelete(() -> deleteIndex(INDEX_NAME));
        safeDelete(() -> deleteSearchPipeline(ASE_SEARCH_PIPELINE));
        safeDelete(() -> deleteSearchPipeline(CUSTOMER_SEARCH_PIPELINE));
        safeDelete(() -> deleteIngestPipeline(ASE_INGEST_PIPELINE));
        safeDelete(() -> deleteIngestPipeline(CUSTOMER_INGEST_PIPELINE));
    }

    // ==========================================================================
    // DEPLOY — ASE owns the pipeline
    // ==========================================================================

    @SneakyThrows
    public void testDeploy_aseOwnsPipeline_swapsSetToRename() {
        // Setup: enable ASE (creates fresh pipelines)
        createIndex(INDEX_NAME, buildMapping("content", "text"));
        enableEnrichment(INDEX_NAME, "content", "content_semantic");

        // Verify pre-state: set processor
        Map<String, Object> pipeline = getIngestPipelineRaw(ASE_INGEST_PIPELINE);
        List<Map<String, Object>> procs = getProcessors(pipeline);
        assertTrue("Should have set processor before deploy", procs.get(0).containsKey("set"));

        // Deploy
        Response response = deployEnrichment(INDEX_NAME);
        assertEquals(200, response.getStatusLine().getStatusCode());
        Map<String, Object> body = toMap(response);
        assertEquals("DEPLOYED", body.get("state"));

        // Verify post-state: rename processor
        pipeline = getIngestPipelineRaw(ASE_INGEST_PIPELINE);
        procs = getProcessors(pipeline);
        assertEquals(1, procs.size());
        assertTrue("Should have rename processor after deploy", procs.get(0).containsKey("rename"));
        @SuppressWarnings("unchecked")
        Map<String, Object> renameConfig = (Map<String, Object>) procs.get(0).get("rename");
        assertEquals("content", renameConfig.get("field"));
        assertEquals("content_semantic", renameConfig.get("target_field"));
        assertEquals("ase_managed", renameConfig.get("tag"));
    }

    // ==========================================================================
    // DEPLOY — ASE merged into customer's pipeline
    // ==========================================================================

    @SneakyThrows
    public void testDeploy_mergedPipeline_swapsOnlyAseProcessors() {
        // Setup: customer has a pipeline, ASE merges into it
        putIngestPipelineRaw(
            CUSTOMER_INGEST_PIPELINE,
            "{\"description\":\"Customer pipeline\",\"processors\":[{\"lowercase\":{\"field\":\"content\"}}]}"
        );
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings("content", "text", "\"index.final_pipeline\":\"" + CUSTOMER_INGEST_PIPELINE + "\"")
        );
        enableEnrichment(INDEX_NAME, "content", "content_semantic");

        // Verify pre-state: customer processor + ASE set processor
        Map<String, Object> pipeline = getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE);
        List<Map<String, Object>> procs = getProcessors(pipeline);
        assertEquals(2, procs.size());
        assertTrue("Customer processor preserved", procs.get(0).containsKey("lowercase"));
        assertTrue("ASE set processor", procs.get(1).containsKey("set"));

        // Deploy
        Response response = deployEnrichment(INDEX_NAME);
        assertEquals(200, response.getStatusLine().getStatusCode());

        // Verify: customer processor unchanged, ASE swapped to rename
        pipeline = getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE);
        procs = getProcessors(pipeline);
        assertEquals(2, procs.size());
        assertTrue("Customer processor still there", procs.get(0).containsKey("lowercase"));
        assertTrue("ASE now has rename", procs.get(1).containsKey("rename"));
    }

    // ==========================================================================
    // ROLLBACK — ASE owns the pipeline
    // ==========================================================================

    @SneakyThrows
    public void testRollback_aseOwnsPipeline_swapsRenameBackToSet() {
        // Setup: enable + deploy
        createIndex(INDEX_NAME, buildMapping("content", "text"));
        enableEnrichment(INDEX_NAME, "content", "content_semantic");
        deployEnrichment(INDEX_NAME);

        // Verify pre-state: rename
        Map<String, Object> pipeline = getIngestPipelineRaw(ASE_INGEST_PIPELINE);
        assertTrue(getProcessors(pipeline).get(0).containsKey("rename"));

        // Rollback
        Response response = rollbackEnrichment(INDEX_NAME);
        assertEquals(200, response.getStatusLine().getStatusCode());
        Map<String, Object> body = toMap(response);
        assertEquals("ENRICHING", body.get("state"));

        // Verify post-state: set processor
        pipeline = getIngestPipelineRaw(ASE_INGEST_PIPELINE);
        List<Map<String, Object>> procs = getProcessors(pipeline);
        assertEquals(1, procs.size());
        assertTrue("Should have set processor after rollback", procs.get(0).containsKey("set"));
        @SuppressWarnings("unchecked")
        Map<String, Object> setConfig = (Map<String, Object>) procs.get(0).get("set");
        assertEquals("content_semantic", setConfig.get("field"));
        assertEquals("{{content}}", setConfig.get("value"));
    }

    // ==========================================================================
    // ROLLBACK — ASE merged into customer's pipeline
    // ==========================================================================

    @SneakyThrows
    public void testRollback_mergedPipeline_swapsOnlyAseProcessors() {
        // Setup: customer pipeline + enable + deploy
        putIngestPipelineRaw(
            CUSTOMER_INGEST_PIPELINE,
            "{\"description\":\"Customer pipeline\",\"processors\":[{\"lowercase\":{\"field\":\"content\"}}]}"
        );
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings("content", "text", "\"index.final_pipeline\":\"" + CUSTOMER_INGEST_PIPELINE + "\"")
        );
        enableEnrichment(INDEX_NAME, "content", "content_semantic");
        deployEnrichment(INDEX_NAME);

        // Rollback
        Response response = rollbackEnrichment(INDEX_NAME);
        assertEquals(200, response.getStatusLine().getStatusCode());

        // Verify: customer processor unchanged, ASE back to set
        Map<String, Object> pipeline = getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE);
        List<Map<String, Object>> procs = getProcessors(pipeline);
        assertEquals(2, procs.size());
        assertTrue("Customer processor intact", procs.get(0).containsKey("lowercase"));
        assertTrue("ASE back to set", procs.get(1).containsKey("set"));
    }

    // ==========================================================================
    // DISABLE — ASE owns the pipeline (should delete pipeline + clear settings)
    // ==========================================================================

    @SneakyThrows
    public void testDisable_aseOwnsPipeline_deletesPipelinesAndClearsSettings() {
        // Setup: enable
        createIndex(INDEX_NAME, buildMapping("content", "text"));
        enableEnrichment(INDEX_NAME, "content", "content_semantic");

        // Verify pipelines exist
        assertNotNull(getIngestPipelineRaw(ASE_INGEST_PIPELINE));
        assertNotNull(getSearchPipelineRaw(ASE_SEARCH_PIPELINE));

        // Disable
        Response response = disableEnrichment(INDEX_NAME);
        assertEquals(200, response.getStatusLine().getStatusCode());
        Map<String, Object> body = toMap(response);
        assertEquals("DISABLED", body.get("state"));

        // Verify pipelines deleted
        assertPipelineDoesNotExist(ASE_INGEST_PIPELINE);
        assertSearchPipelineDoesNotExist(ASE_SEARCH_PIPELINE);

        // Verify settings cleared
        Map<String, Object> settings = fetchSettings(INDEX_NAME);
        String finalPipeline = getNestedSetting(settings, "index.final_pipeline");
        assertTrue("final_pipeline should be cleared", finalPipeline == null || "_none".equals(finalPipeline));
    }

    // ==========================================================================
    // DISABLE — ASE merged into customer's pipeline (should remove only ASE processors)
    // ==========================================================================

    @SneakyThrows
    public void testDisable_mergedPipeline_removesOnlyAseProcessors() {
        // Setup: customer pipelines + enable
        putIngestPipelineRaw(
            CUSTOMER_INGEST_PIPELINE,
            "{\"description\":\"Customer pipeline\",\"processors\":[{\"lowercase\":{\"field\":\"content\"}}]}"
        );
        putSearchPipelineRaw(
            CUSTOMER_SEARCH_PIPELINE,
            "{\"request_processors\":[{\"filter_query\":{\"query\":{\"term\":{\"status\":\"published\"}}}}]}"
        );
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings(
                "content",
                "text",
                "\"index.final_pipeline\":\""
                    + CUSTOMER_INGEST_PIPELINE
                    + "\","
                    + "\"index.search.default_pipeline\":\""
                    + CUSTOMER_SEARCH_PIPELINE
                    + "\""
            )
        );
        enableEnrichment(INDEX_NAME, "content", "content_semantic");

        // Verify merged state before disable
        Map<String, Object> ingestPipeline = getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE);
        assertEquals(2, getProcessors(ingestPipeline).size()); // customer + ASE

        // Disable
        Response response = disableEnrichment(INDEX_NAME);
        assertEquals(200, response.getStatusLine().getStatusCode());

        // Verify: customer's ingest pipeline retains only customer processors
        ingestPipeline = getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE);
        List<Map<String, Object>> ingestProcs = getProcessors(ingestPipeline);
        assertEquals("Only customer processor should remain", 1, ingestProcs.size());
        assertTrue("Customer processor preserved", ingestProcs.get(0).containsKey("lowercase"));

        // Verify: customer's search pipeline retains only customer processors
        Map<String, Object> searchPipeline = getSearchPipelineRaw(CUSTOMER_SEARCH_PIPELINE);
        List<Map<String, Object>> searchProcs = getRequestProcessors(searchPipeline);
        assertEquals("Only customer processor should remain", 1, searchProcs.size());
        assertTrue("Customer processor preserved", searchProcs.get(0).containsKey("filter_query"));

        // Settings should still point to customer pipelines (not cleared)
        Map<String, Object> settings = fetchSettings(INDEX_NAME);
        assertEquals(CUSTOMER_INGEST_PIPELINE, getNestedSetting(settings, "index.final_pipeline"));
        assertEquals(CUSTOMER_SEARCH_PIPELINE, getNestedSetting(settings, "index.search.default_pipeline"));
    }

    // ==========================================================================
    // FULL LIFECYCLE: enable → deploy → rollback → deploy → disable
    // ==========================================================================

    @SneakyThrows
    public void testFullLifecycle_aseOwnsPipeline() {
        createIndex(INDEX_NAME, buildMapping("content", "text"));

        // Enable
        Response resp = enableEnrichment(INDEX_NAME, "content", "content_semantic");
        assertEquals(200, resp.getStatusLine().getStatusCode());
        assertEquals("ENRICHING", toMap(resp).get("state"));

        // Deploy
        resp = deployEnrichment(INDEX_NAME);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        assertEquals("DEPLOYED", toMap(resp).get("state"));
        assertTrue(getProcessors(getIngestPipelineRaw(ASE_INGEST_PIPELINE)).get(0).containsKey("rename"));

        // Rollback
        resp = rollbackEnrichment(INDEX_NAME);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        assertEquals("ENRICHING", toMap(resp).get("state"));
        assertTrue(getProcessors(getIngestPipelineRaw(ASE_INGEST_PIPELINE)).get(0).containsKey("set"));

        // Deploy again
        resp = deployEnrichment(INDEX_NAME);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        assertTrue(getProcessors(getIngestPipelineRaw(ASE_INGEST_PIPELINE)).get(0).containsKey("rename"));

        // Disable
        resp = disableEnrichment(INDEX_NAME);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        assertEquals("DISABLED", toMap(resp).get("state"));
        assertPipelineDoesNotExist(ASE_INGEST_PIPELINE);
        assertSearchPipelineDoesNotExist(ASE_SEARCH_PIPELINE);
    }

    @SneakyThrows
    public void testFullLifecycle_mergedPipeline() {
        // Setup with customer pipelines
        putIngestPipelineRaw(
            CUSTOMER_INGEST_PIPELINE,
            "{\"description\":\"Customer pipeline\",\"processors\":[{\"lowercase\":{\"field\":\"content\"}}]}"
        );
        putSearchPipelineRaw(
            CUSTOMER_SEARCH_PIPELINE,
            "{\"request_processors\":[{\"filter_query\":{\"query\":{\"term\":{\"status\":\"published\"}}}}]}"
        );
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings(
                "content",
                "text",
                "\"index.final_pipeline\":\""
                    + CUSTOMER_INGEST_PIPELINE
                    + "\","
                    + "\"index.search.default_pipeline\":\""
                    + CUSTOMER_SEARCH_PIPELINE
                    + "\""
            )
        );

        // Enable
        Response resp = enableEnrichment(INDEX_NAME, "content", "content_semantic");
        assertEquals(200, resp.getStatusLine().getStatusCode());

        // Deploy
        resp = deployEnrichment(INDEX_NAME);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        List<Map<String, Object>> procs = getProcessors(getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE));
        assertTrue("Customer proc preserved", procs.get(0).containsKey("lowercase"));
        assertTrue("ASE renamed", procs.get(1).containsKey("rename"));

        // Rollback
        resp = rollbackEnrichment(INDEX_NAME);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        procs = getProcessors(getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE));
        assertTrue("ASE back to set", procs.get(1).containsKey("set"));

        // Disable
        resp = disableEnrichment(INDEX_NAME);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        procs = getProcessors(getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE));
        assertEquals("Only customer proc remains", 1, procs.size());
        assertTrue("Customer proc intact", procs.get(0).containsKey("lowercase"));
    }

    // ==========================================================================
    // DEPLOY with multiple fields
    // ==========================================================================

    @SneakyThrows
    public void testDeploy_multipleFields_allSwapped() {
        createIndex(INDEX_NAME, "{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"},\"body\":{\"type\":\"text\"}}}}");
        enableMultipleFields(INDEX_NAME);

        // Verify enable created 2 set processors
        Map<String, Object> pipeline = getIngestPipelineRaw(ASE_INGEST_PIPELINE);
        List<Map<String, Object>> procs = getProcessors(pipeline);
        assertEquals(2, procs.size());
        assertTrue(procs.get(0).containsKey("set"));
        assertTrue(procs.get(1).containsKey("set"));

        // Deploy
        Response resp = deployEnrichment(INDEX_NAME);
        assertEquals(200, resp.getStatusLine().getStatusCode());

        // Verify both swapped to rename
        pipeline = getIngestPipelineRaw(ASE_INGEST_PIPELINE);
        procs = getProcessors(pipeline);
        assertEquals(2, procs.size());
        assertTrue(procs.get(0).containsKey("rename"));
        assertTrue(procs.get(1).containsKey("rename"));
    }

    // ==========================================================================
    // ERROR: deploy before enable
    // ==========================================================================

    @SneakyThrows
    public void testDeploy_noFinalPipeline_returns400() {
        createIndex(INDEX_NAME, buildMapping("content", "text"));

        ResponseException ex = expectThrows(ResponseException.class, () -> deployEnrichment(INDEX_NAME));
        assertEquals(400, ex.getResponse().getStatusLine().getStatusCode());
    }

    // ==========================================================================
    // ERROR: deploy on non-existent index
    // ==========================================================================

    @SneakyThrows
    public void testDeploy_nonExistentIndex_returns404() {
        ResponseException ex = expectThrows(ResponseException.class, () -> deployEnrichment("no-such-index"));
        assertEquals(404, ex.getResponse().getStatusLine().getStatusCode());
    }

    // ==========================================================================
    // ERROR: rollback before enable
    // ==========================================================================

    @SneakyThrows
    public void testRollback_noFinalPipeline_returns400() {
        createIndex(INDEX_NAME, buildMapping("content", "text"));

        ResponseException ex = expectThrows(ResponseException.class, () -> rollbackEnrichment(INDEX_NAME));
        assertEquals(400, ex.getResponse().getStatusLine().getStatusCode());
    }

    // ==========================================================================
    // DISABLE from deployed state (should still clean up correctly)
    // ==========================================================================

    @SneakyThrows
    public void testDisable_fromDeployedState_cleansUpCorrectly() {
        createIndex(INDEX_NAME, buildMapping("content", "text"));
        enableEnrichment(INDEX_NAME, "content", "content_semantic");
        deployEnrichment(INDEX_NAME);

        // Disable directly from deployed state (skip rollback)
        Response resp = disableEnrichment(INDEX_NAME);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        assertEquals("DISABLED", toMap(resp).get("state"));

        assertPipelineDoesNotExist(ASE_INGEST_PIPELINE);
        assertSearchPipelineDoesNotExist(ASE_SEARCH_PIPELINE);
    }

    // ==========================================================================
    // HELPER METHODS
    // ==========================================================================

    private Response enableEnrichment(String index, String originalField, String semanticField) throws Exception {
        String body = String.format(
            Locale.ROOT,
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

    private Response enableMultipleFields(String index) throws Exception {
        String body = "{\"fields\":["
            + "{\"original_field\":\"title\",\"semantic_field\":\"title_semantic\",\"model_type\":\"SPARSE\"},"
            + "{\"original_field\":\"body\",\"semantic_field\":\"body_semantic\",\"model_type\":\"SPARSE\"}"
            + "]}";
        return makeRequest(
            client(),
            "POST",
            NEURAL_BASE_URI + "/semantic/" + index + "/enable_semantic_enrichment",
            null,
            toHttpEntity(body),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    private Response deployEnrichment(String index) throws Exception {
        return makeRequest(
            client(),
            "POST",
            NEURAL_BASE_URI + "/semantic/" + index + "/deploy_semantic_enrichment",
            null,
            toHttpEntity("{}"),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    private Response rollbackEnrichment(String index) throws Exception {
        return makeRequest(
            client(),
            "POST",
            NEURAL_BASE_URI + "/semantic/" + index + "/rollback_semantic_enrichment",
            null,
            toHttpEntity("{}"),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    private Response disableEnrichment(String index) throws Exception {
        return makeRequest(
            client(),
            "POST",
            NEURAL_BASE_URI + "/semantic/" + index + "/disable_semantic_enrichment",
            null,
            toHttpEntity("{}"),
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

    private void putIngestPipelineRaw(String name, String body) throws Exception {
        makeRequest(
            client(),
            "PUT",
            "/_ingest/pipeline/" + name,
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> getIngestPipelineRaw(String name) throws Exception {
        Response response = makeRequest(
            client(),
            "GET",
            "/_ingest/pipeline/" + name,
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchSettings(String index) throws Exception {
        Response response = makeRequest(
            client(),
            "GET",
            "/" + index + "/_settings",
            null,
            null,
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
        Map<String, Object> responseMap = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(response.getEntity()),
            false
        );
        Map<String, Object> indexMap = (Map<String, Object>) responseMap.get(index);
        return (Map<String, Object>) indexMap.get("settings");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getProcessors(Map<String, Object> pipeline) {
        return (List<Map<String, Object>>) pipeline.get("processors");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getRequestProcessors(Map<String, Object> pipeline) {
        return (List<Map<String, Object>>) pipeline.get("request_processors");
    }

    @SuppressWarnings("unchecked")
    private String getNestedSetting(Map<String, Object> settings, String dotPath) {
        Map<String, Object> current = settings;
        String[] parts = dotPath.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (next instanceof Map) {
                current = (Map<String, Object>) next;
            } else {
                return null;
            }
        }
        Object val = current.get(parts[parts.length - 1]);
        return val != null ? val.toString() : null;
    }

    private void assertPipelineDoesNotExist(String name) {
        ResponseException ex = expectThrows(
            ResponseException.class,
            () -> makeRequest(
                client(),
                "GET",
                "/_ingest/pipeline/" + name,
                null,
                null,
                ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
            )
        );
        assertEquals(404, ex.getResponse().getStatusLine().getStatusCode());
    }

    private void assertSearchPipelineDoesNotExist(String name) {
        ResponseException ex = expectThrows(
            ResponseException.class,
            () -> makeRequest(
                client(),
                "GET",
                "/_search/pipeline/" + name,
                null,
                null,
                ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
            )
        );
        assertEquals(404, ex.getResponse().getStatusLine().getStatusCode());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Response response) throws Exception {
        return XContentHelper.convertToMap(XContentType.JSON.xContent(), EntityUtils.toString(response.getEntity()), false);
    }

    private String buildMapping(String fieldName, String fieldType) {
        return String.format(Locale.ROOT, "{\"mappings\":{\"properties\":{\"%s\":{\"type\":\"%s\"}}}}", fieldName, fieldType);
    }

    private String buildMappingWithSettings(String fieldName, String fieldType, String settings) {
        return String.format(
            Locale.ROOT,
            "{\"settings\":{%s},\"mappings\":{\"properties\":{\"%s\":{\"type\":\"%s\"}}}}",
            settings,
            fieldName,
            fieldType
        );
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void safeDelete(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception ignored) {}
    }
}
