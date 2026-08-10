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
 * Integration tests for the enable_semantic_enrichment flow, focusing on:
 * 1. Pipeline merge behavior when existing ingest/search pipelines are present
 * 2. Conflict detection that blocks before PutMapping
 * 3. Validation that no mutations occur on conflict (mapping unchanged, pipelines untouched)
 *
 * Test matrix:
 * - No existing pipelines → fresh create (happy path)
 * - Existing ingest pipeline only → merge ingest, create search
 * - Existing search pipeline only → create ingest, merge search
 * - Both existing pipelines → merge both
 * - Ingest conflict (remove source field) → 409, no mutations
 * - Search conflict (existing rewrite processor) → 409, no mutations
 */
public class RestSemanticEnableHandlerIT extends BaseNeuralSearchIT {

    private static final String INDEX_NAME = "ase-enable-it-index";
    private static final String CUSTOMER_INGEST_PIPELINE = "customer-ingest-it";
    private static final String CUSTOMER_SEARCH_PIPELINE = "customer-search-it";
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
    // HAPPY PATH: No existing pipelines — fresh create
    // ==========================================================================

    @SneakyThrows
    public void testEnable_noExistingPipelines_createsBoth() {
        createIndex(INDEX_NAME, buildMapping("title", "text"));

        Response response = enableEnrichment(INDEX_NAME, "title", "title_semantic");

        assertEquals(200, response.getStatusLine().getStatusCode());
        Map<String, Object> body = toMap(response);
        assertEquals(Boolean.TRUE, body.get("acknowledged"));
        assertEquals("ENRICHING", body.get("state"));

        // Verify ingest pipeline was created with set processor
        Map<String, Object> ingestPipeline = getIngestPipelineRaw(ASE_INGEST_PIPELINE);
        assertNotNull("ASE ingest pipeline should exist", ingestPipeline);
        List<Map<String, Object>> ingestProcessors = getProcessors(ingestPipeline);
        assertEquals("Should have 1 set processor", 1, ingestProcessors.size());
        assertTrue("First processor should be 'set'", ingestProcessors.get(0).containsKey("set"));

        // Verify search pipeline was created with match_to_neural_rewrite + two_phase
        Map<String, Object> searchPipeline = getSearchPipelineRaw(ASE_SEARCH_PIPELINE);
        assertNotNull("ASE search pipeline should exist", searchPipeline);
        List<Map<String, Object>> searchProcessors = getRequestProcessors(searchPipeline);
        assertEquals("Should have 2 request processors", 2, searchProcessors.size());
        assertTrue(
            "First should be match_to_neural_rewrite_processor",
            searchProcessors.get(0).containsKey("match_to_neural_rewrite_processor")
        );
        assertTrue(
            "Second should be neural_sparse_two_phase_processor",
            searchProcessors.get(1).containsKey("neural_sparse_two_phase_processor")
        );

        // Verify mapping has the semantic field
        Map<String, Object> mapping = fetchMapping(INDEX_NAME);
        Map<String, Object> props = getProperties(mapping);
        assertTrue("Should have title_semantic field", props.containsKey("title_semantic"));
        Map<String, Object> semField = (Map<String, Object>) props.get("title_semantic");
        assertEquals("semantic", semField.get("type"));

        // Verify index settings point to ASE pipelines
        Map<String, Object> settings = fetchSettings(INDEX_NAME);
        assertEquals(ASE_INGEST_PIPELINE, getNestedSetting(settings, "index.final_pipeline"));
        assertEquals(ASE_SEARCH_PIPELINE, getNestedSetting(settings, "index.search.default_pipeline"));
    }

    // ==========================================================================
    // MERGE: Existing ingest pipeline only — merge ingest, create fresh search
    // ==========================================================================

    @SneakyThrows
    public void testEnable_existingFinalPipelineOnly_mergesIngestCreatesFreshSearch() {
        // Customer has a safe ingest pipeline (lowercase — doesn't conflict)
        putIngestPipelineRaw(
            CUSTOMER_INGEST_PIPELINE,
            "{\"description\":\"Customer pipeline\",\"processors\":[{\"lowercase\":{\"field\":\"title\"}}]}"
        );
        createIndex(INDEX_NAME, buildMappingWithSettings("title", "text", "\"index.final_pipeline\":\"" + CUSTOMER_INGEST_PIPELINE + "\""));

        Response response = enableEnrichment(INDEX_NAME, "title", "title_semantic");

        assertEquals(200, response.getStatusLine().getStatusCode());

        // Verify ASE processors were APPENDED to the customer's existing ingest pipeline
        Map<String, Object> ingestPipeline = getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE);
        List<Map<String, Object>> ingestProcessors = getProcessors(ingestPipeline);
        assertEquals("Should have 2 processors (customer lowercase + ASE set)", 2, ingestProcessors.size());
        assertTrue("First should be customer's lowercase", ingestProcessors.get(0).containsKey("lowercase"));
        assertTrue("Second should be ASE set", ingestProcessors.get(1).containsKey("set"));
        // Verify ASE processor is tagged
        Map<String, Object> setConfig = (Map<String, Object>) ingestProcessors.get(1).get("set");
        assertEquals("ase_managed", setConfig.get("tag"));

        // Verify fresh search pipeline was created (no existing search pipeline)
        Map<String, Object> searchPipeline = getSearchPipelineRaw(ASE_SEARCH_PIPELINE);
        assertNotNull("ASE search pipeline should be created", searchPipeline);
        List<Map<String, Object>> searchProcessors = getRequestProcessors(searchPipeline);
        assertEquals(2, searchProcessors.size());
        assertTrue(searchProcessors.get(0).containsKey("match_to_neural_rewrite_processor"));

        // index.final_pipeline should still point to customer's pipeline (unchanged)
        Map<String, Object> settings = fetchSettings(INDEX_NAME);
        assertEquals(
            "Should still reference customer final pipeline",
            CUSTOMER_INGEST_PIPELINE,
            getNestedSetting(settings, "index.final_pipeline")
        );
    }

    // ==========================================================================
    // MOST COMMON CASE: customer has a default_pipeline, no final_pipeline.
    // ASE must create a fresh final_pipeline and leave the default pipeline ALONE.
    // ==========================================================================

    @SneakyThrows
    public void testEnable_existingDefaultPipeline_isConflictCheckedButNeverModified() {
        putIngestPipelineRaw(
            CUSTOMER_INGEST_PIPELINE,
            "{\"description\":\"Customer pipeline\",\"processors\":[{\"lowercase\":{\"field\":\"title\"}}]}"
        );
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings("title", "text", "\"index.default_pipeline\":\"" + CUSTOMER_INGEST_PIPELINE + "\"")
        );

        Map<String, Object> defaultPipelineBefore = getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE);

        Response response = enableEnrichment(INDEX_NAME, "title", "title_semantic");
        assertEquals(200, response.getStatusLine().getStatusCode());

        // The customer's default pipeline must be byte-for-byte untouched.
        assertEquals(
            "ASE must not modify the customer default_pipeline",
            defaultPipelineBefore,
            getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE)
        );

        // ASE created its own pipeline and attached it as the FINAL pipeline.
        Map<String, Object> asePipeline = getIngestPipelineRaw(ASE_INGEST_PIPELINE);
        assertNotNull("ASE should create its own ingest pipeline", asePipeline);
        List<Map<String, Object>> aseProcessors = getProcessors(asePipeline);
        assertEquals("ASE pipeline should hold one set processor", 1, aseProcessors.size());
        assertTrue(aseProcessors.get(0).containsKey("set"));

        Map<String, Object> settings = fetchSettings(INDEX_NAME);
        assertEquals(
            "default_pipeline should still be the customer's",
            CUSTOMER_INGEST_PIPELINE,
            getNestedSetting(settings, "index.default_pipeline")
        );
        assertEquals("final_pipeline should be ASE's", ASE_INGEST_PIPELINE, getNestedSetting(settings, "index.final_pipeline"));
    }

    // ==========================================================================
    // MERGE: Existing search pipeline only — create fresh ingest, merge search
    // ==========================================================================

    @SneakyThrows
    public void testEnable_existingSearchPipelineOnly_createsFreshIngestMergesSearch() {
        // Customer has a compatible search pipeline (filter_query — no conflict)
        putSearchPipelineRaw(
            CUSTOMER_SEARCH_PIPELINE,
            "{\"request_processors\":[{\"filter_query\":{\"tag\":\"customer\",\"query\":{\"match_all\":{}}}}]}"
        );
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings("title", "text", "\"index.search.default_pipeline\":\"" + CUSTOMER_SEARCH_PIPELINE + "\"")
        );

        Response response = enableEnrichment(INDEX_NAME, "title", "title_semantic");

        assertEquals(200, response.getStatusLine().getStatusCode());

        // Verify ASE search processors were PREPENDED to customer's existing search pipeline
        Map<String, Object> searchPipeline = getSearchPipelineRaw(CUSTOMER_SEARCH_PIPELINE);
        List<Map<String, Object>> searchProcessors = getRequestProcessors(searchPipeline);
        assertEquals("Should have 3 processors (2 ASE + 1 customer)", 3, searchProcessors.size());
        assertTrue(
            "First should be match_to_neural_rewrite_processor",
            searchProcessors.get(0).containsKey("match_to_neural_rewrite_processor")
        );
        assertTrue(
            "Second should be neural_sparse_two_phase_processor",
            searchProcessors.get(1).containsKey("neural_sparse_two_phase_processor")
        );
        assertTrue("Third should be customer's filter_query", searchProcessors.get(2).containsKey("filter_query"));

        // Verify ASE processors are tagged
        Map<String, Object> rewriteConfig = (Map<String, Object>) searchProcessors.get(0).get("match_to_neural_rewrite_processor");
        assertEquals("ase_managed", rewriteConfig.get("tag"));

        // Verify fresh ingest pipeline was created
        Map<String, Object> ingestPipeline = getIngestPipelineRaw(ASE_INGEST_PIPELINE);
        assertNotNull("ASE ingest pipeline should be created", ingestPipeline);

        // index.search.default_pipeline should still point to customer's pipeline
        Map<String, Object> settings = fetchSettings(INDEX_NAME);
        assertEquals(
            "Should still reference customer search pipeline",
            CUSTOMER_SEARCH_PIPELINE,
            getNestedSetting(settings, "index.search.default_pipeline")
        );
    }

    // ==========================================================================
    // MERGE: Both existing pipelines — merge both
    // ==========================================================================

    @SneakyThrows
    public void testEnable_bothExistingPipelines_mergesBoth() {
        putIngestPipelineRaw(
            CUSTOMER_INGEST_PIPELINE,
            "{\"description\":\"Customer ingest\",\"processors\":[{\"lowercase\":{\"field\":\"title\"}}]}"
        );
        putSearchPipelineRaw(
            CUSTOMER_SEARCH_PIPELINE,
            "{\"request_processors\":[{\"filter_query\":{\"tag\":\"customer\",\"query\":{\"match_all\":{}}}}]}"
        );
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings(
                "title",
                "text",
                "\"index.final_pipeline\":\""
                    + CUSTOMER_INGEST_PIPELINE
                    + "\","
                    + "\"index.search.default_pipeline\":\""
                    + CUSTOMER_SEARCH_PIPELINE
                    + "\""
            )
        );

        Response response = enableEnrichment(INDEX_NAME, "title", "title_semantic");

        assertEquals(200, response.getStatusLine().getStatusCode());

        // Verify ingest merge: customer processor + ASE processor
        Map<String, Object> ingestPipeline = getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE);
        List<Map<String, Object>> ingestProcessors = getProcessors(ingestPipeline);
        assertEquals(2, ingestProcessors.size());
        assertTrue(ingestProcessors.get(0).containsKey("lowercase"));
        assertTrue(ingestProcessors.get(1).containsKey("set"));

        // Verify search merge: ASE processors prepended to customer's
        Map<String, Object> searchPipeline = getSearchPipelineRaw(CUSTOMER_SEARCH_PIPELINE);
        List<Map<String, Object>> searchProcessors = getRequestProcessors(searchPipeline);
        assertEquals(3, searchProcessors.size());
        assertTrue(searchProcessors.get(0).containsKey("match_to_neural_rewrite_processor"));
        assertTrue(searchProcessors.get(1).containsKey("neural_sparse_two_phase_processor"));
        assertTrue(searchProcessors.get(2).containsKey("filter_query"));

        // Mapping should have the semantic field
        Map<String, Object> props = getProperties(fetchMapping(INDEX_NAME));
        assertTrue(props.containsKey("title_semantic"));
    }

    // ==========================================================================
    // CONFLICT: Ingest pipeline removes source field — 409, no mutations
    // ==========================================================================

    @SneakyThrows
    public void testEnable_ingestConflict_removesSourceField_returns409NoMutations() {
        putIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE, "{\"processors\":[{\"remove\":{\"field\":\"title\"}}]}");
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings("title", "text", "\"index.default_pipeline\":\"" + CUSTOMER_INGEST_PIPELINE + "\"")
        );

        // Capture state before
        Map<String, Object> mappingBefore = fetchMapping(INDEX_NAME);
        Map<String, Object> ingestBefore = getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE);

        ResponseException ex = expectThrows(ResponseException.class, () -> enableEnrichment(INDEX_NAME, "title", "title_semantic"));

        assertEquals(409, ex.getResponse().getStatusLine().getStatusCode());
        String errorBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue("Should mention ingest pipeline", errorBody.contains("ingest pipeline"));
        assertTrue("Should mention removes field", errorBody.contains("removes field"));

        // Verify NO mutations occurred
        Map<String, Object> mappingAfter = fetchMapping(INDEX_NAME);
        Map<String, Object> propsAfter = getProperties(mappingAfter);
        assertFalse("Semantic field should NOT have been created", propsAfter.containsKey("title_semantic"));

        Map<String, Object> ingestAfter = getIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE);
        assertEquals("Ingest pipeline should be unchanged", ingestBefore, ingestAfter);

        // ASE pipelines should NOT exist
        assertPipelineDoesNotExist(ASE_INGEST_PIPELINE);
        assertSearchPipelineDoesNotExist(ASE_SEARCH_PIPELINE);
    }

    // ==========================================================================
    // CONFLICT: Ingest pipeline renames source field — 409, no mutations
    // ==========================================================================

    @SneakyThrows
    public void testEnable_ingestConflict_renamesSourceField_returns409NoMutations() {
        putIngestPipelineRaw(
            CUSTOMER_INGEST_PIPELINE,
            "{\"processors\":[{\"rename\":{\"field\":\"title\",\"target_field\":\"renamed_title\"}}]}"
        );
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings("title", "text", "\"index.default_pipeline\":\"" + CUSTOMER_INGEST_PIPELINE + "\"")
        );

        ResponseException ex = expectThrows(ResponseException.class, () -> enableEnrichment(INDEX_NAME, "title", "title_semantic"));

        assertEquals(409, ex.getResponse().getStatusLine().getStatusCode());
        String errorBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue("Should mention renames", errorBody.contains("renames"));

        // Verify no mapping mutation
        Map<String, Object> propsAfter = getProperties(fetchMapping(INDEX_NAME));
        assertFalse("Semantic field should NOT exist", propsAfter.containsKey("title_semantic"));
    }

    // ==========================================================================
    // CONFLICT: Search pipeline has existing rewrite processor — 409, no mutations
    // ==========================================================================

    @SneakyThrows
    public void testEnable_searchConflict_existingRewriteProcessor_returns409NoMutations() {
        // Customer pipeline has a non-ASE match_to_neural_rewrite_processor
        putSearchPipelineRaw(
            CUSTOMER_SEARCH_PIPELINE,
            "{\"request_processors\":[{\"match_to_neural_rewrite_processor\":{\"fields\":[\"some_field\"]}}]}"
        );
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings("title", "text", "\"index.search.default_pipeline\":\"" + CUSTOMER_SEARCH_PIPELINE + "\"")
        );

        // Capture state before
        Map<String, Object> searchBefore = getSearchPipelineRaw(CUSTOMER_SEARCH_PIPELINE);

        ResponseException ex = expectThrows(ResponseException.class, () -> enableEnrichment(INDEX_NAME, "title", "title_semantic"));

        assertEquals(409, ex.getResponse().getStatusLine().getStatusCode());
        String errorBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue("Should mention search pipeline", errorBody.contains("search pipeline"));
        assertTrue("Should mention double-rewriting", errorBody.contains("double-rewriting"));

        // Verify NO mutations occurred
        Map<String, Object> propsAfter = getProperties(fetchMapping(INDEX_NAME));
        assertFalse("Semantic field should NOT have been created", propsAfter.containsKey("title_semantic"));

        Map<String, Object> searchAfter = getSearchPipelineRaw(CUSTOMER_SEARCH_PIPELINE);
        assertEquals("Search pipeline should be unchanged", searchBefore, searchAfter);
    }

    // ==========================================================================
    // CONFLICT: Search pipeline has ASE-managed processors (double-enable) — 409
    // ==========================================================================

    @SneakyThrows
    public void testEnable_searchConflict_alreadyHasAseManaged_returns409() {
        putSearchPipelineRaw(
            CUSTOMER_SEARCH_PIPELINE,
            "{\"request_processors\":["
                + "{\"match_to_neural_rewrite_processor\":{\"tag\":\"ase_managed\",\"fields\":[\"old_field\"]}},"
                + "{\"neural_sparse_two_phase_processor\":{\"tag\":\"ase_managed\",\"enabled\":true}}"
                + "]}"
        );
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings("title", "text", "\"index.search.default_pipeline\":\"" + CUSTOMER_SEARCH_PIPELINE + "\"")
        );

        ResponseException ex = expectThrows(ResponseException.class, () -> enableEnrichment(INDEX_NAME, "title", "title_semantic"));

        assertEquals(409, ex.getResponse().getStatusLine().getStatusCode());
        String errorBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue("Should mention ASE-managed", errorBody.contains("ASE-managed"));
    }

    // ==========================================================================
    // CONFLICT: Ingest pipeline has ASE-managed processors (double-enable) — 409
    // ==========================================================================

    @SneakyThrows
    public void testEnable_ingestConflict_alreadyHasAseManaged_returns409() {
        // Simulate a previous enable that left ASE-managed set processors in the ingest pipeline
        putIngestPipelineRaw(
            CUSTOMER_INGEST_PIPELINE,
            "{\"processors\":[{\"set\":{\"tag\":\"ase_managed\",\"field\":\"title_semantic\",\"value\":\"{{title}}\"}}]}"
        );
        createIndex(INDEX_NAME, buildMappingWithSettings("title", "text", "\"index.final_pipeline\":\"" + CUSTOMER_INGEST_PIPELINE + "\""));

        ResponseException ex = expectThrows(ResponseException.class, () -> enableEnrichment(INDEX_NAME, "title", "title_semantic"));

        assertEquals(409, ex.getResponse().getStatusLine().getStatusCode());
        String errorBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue("Should mention ASE-managed", errorBody.contains("ASE-managed"));

        // Verify no mapping mutation
        Map<String, Object> propsAfter = getProperties(fetchMapping(INDEX_NAME));
        assertFalse("Semantic field should NOT exist", propsAfter.containsKey("title_semantic"));
    }

    // ==========================================================================
    // SAFE: Ingest pipeline with safe processor (lowercase) — should succeed
    // ==========================================================================

    @SneakyThrows
    public void testEnable_ingestPipelineSafe_succeeds() {
        putIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE, "{\"processors\":[{\"lowercase\":{\"field\":\"title\"}}]}");
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings("title", "text", "\"index.default_pipeline\":\"" + CUSTOMER_INGEST_PIPELINE + "\"")
        );

        Response response = enableEnrichment(INDEX_NAME, "title", "title_semantic");
        assertEquals(200, response.getStatusLine().getStatusCode());

        // Verify semantic field was created
        Map<String, Object> props = getProperties(fetchMapping(INDEX_NAME));
        assertTrue(props.containsKey("title_semantic"));
    }

    // ==========================================================================
    // CONFLICT: Both pipelines conflict — 409 on first check (ingest), no mutations
    // ==========================================================================

    @SneakyThrows
    public void testEnable_bothConflict_failsOnIngestFirst_noMutations() {
        putIngestPipelineRaw(CUSTOMER_INGEST_PIPELINE, "{\"processors\":[{\"remove\":{\"field\":\"title\"}}]}");
        putSearchPipelineRaw(
            CUSTOMER_SEARCH_PIPELINE,
            "{\"request_processors\":[{\"match_to_neural_rewrite_processor\":{\"fields\":[\"x\"]}}]}"
        );
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings(
                "title",
                "text",
                "\"index.default_pipeline\":\""
                    + CUSTOMER_INGEST_PIPELINE
                    + "\","
                    + "\"index.search.default_pipeline\":\""
                    + CUSTOMER_SEARCH_PIPELINE
                    + "\""
            )
        );

        ResponseException ex = expectThrows(ResponseException.class, () -> enableEnrichment(INDEX_NAME, "title", "title_semantic"));

        assertEquals(409, ex.getResponse().getStatusLine().getStatusCode());
        // Should fail on ingest check first
        String errorBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue("Should mention ingest pipeline", errorBody.contains("ingest pipeline"));

        // Verify no mutations
        Map<String, Object> propsAfter = getProperties(fetchMapping(INDEX_NAME));
        assertFalse(propsAfter.containsKey("title_semantic"));
    }

    // ==========================================================================
    // MERGE: Existing search pipeline with response_processors — preserved
    // ==========================================================================

    @SneakyThrows
    public void testEnable_existingSearchPipelineWithResponseProcessors_preserved() {
        // Customer pipeline has both request and response processors
        putSearchPipelineRaw(
            CUSTOMER_SEARCH_PIPELINE,
            "{\"request_processors\":[{\"filter_query\":{\"tag\":\"customer\",\"query\":{\"match_all\":{}}}}],"
                + "\"response_processors\":[{\"rename_field\":{\"field\":\"_score\",\"target_field\":\"relevance\"}}]}"
        );
        createIndex(
            INDEX_NAME,
            buildMappingWithSettings("title", "text", "\"index.search.default_pipeline\":\"" + CUSTOMER_SEARCH_PIPELINE + "\"")
        );

        Response response = enableEnrichment(INDEX_NAME, "title", "title_semantic");

        assertEquals(200, response.getStatusLine().getStatusCode());

        // Verify response_processors survived the merge
        Map<String, Object> pipeline = getSearchPipelineRaw(CUSTOMER_SEARCH_PIPELINE);
        List<Map<String, Object>> requestProcs = getRequestProcessors(pipeline);
        assertEquals("Should have 3 request processors (2 ASE + 1 customer)", 3, requestProcs.size());
        assertTrue(requestProcs.get(0).containsKey("match_to_neural_rewrite_processor"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> responseProcs = (List<Map<String, Object>>) pipeline.get("response_processors");
        assertNotNull("response_processors should be preserved after merge", responseProcs);
        assertEquals(1, responseProcs.size());
        assertTrue(responseProcs.get(0).containsKey("rename_field"));
    }

    // ==========================================================================
    // MULTI-FIELD: Multiple fields in single enable request
    // ==========================================================================

    @SneakyThrows
    public void testEnable_multipleFields_succeeds() {
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

        // Verify both semantic fields were created in mapping
        Map<String, Object> props = getProperties(fetchMapping(INDEX_NAME));
        assertTrue("Should have title_semantic", props.containsKey("title_semantic"));
        assertTrue("Should have body_semantic", props.containsKey("body_semantic"));

        // Verify search pipeline has both fields in the rewrite processor
        Map<String, Object> pipeline = getSearchPipelineRaw(ASE_SEARCH_PIPELINE);
        List<Map<String, Object>> procs = getRequestProcessors(pipeline);
        @SuppressWarnings("unchecked")
        Map<String, Object> rewriteConfig = (Map<String, Object>) procs.get(0).get("match_to_neural_rewrite_processor");
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) rewriteConfig.get("fields");
        assertTrue("fields should contain title_semantic", fields.contains("title_semantic"));
        assertTrue("fields should contain body_semantic", fields.contains("body_semantic"));

        // Verify ingest pipeline has 2 set processors
        Map<String, Object> ingestPipeline = getIngestPipelineRaw(ASE_INGEST_PIPELINE);
        List<Map<String, Object>> ingestProcs = getProcessors(ingestPipeline);
        assertEquals("Should have 2 set processors", 2, ingestProcs.size());
        assertTrue(ingestProcs.get(0).containsKey("set"));
        assertTrue(ingestProcs.get(1).containsKey("set"));
    }

    // ==========================================================================
    // VALIDATION: Non-existent source field — 400
    // ==========================================================================

    @SneakyThrows
    public void testEnable_nonExistentSourceField_returns400() {
        createIndex(INDEX_NAME, buildMapping("title", "text"));

        ResponseException ex = expectThrows(
            ResponseException.class,
            () -> enableEnrichment(INDEX_NAME, "nonexistent_field", "nonexistent_semantic")
        );

        assertEquals(400, ex.getResponse().getStatusLine().getStatusCode());
        String responseBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue(responseBody.contains("not found"));
    }

    // ==========================================================================
    // VALIDATION: Non-existent index — 404
    // ==========================================================================

    @SneakyThrows
    public void testEnable_nonExistentIndex_returns404() {
        ResponseException ex = expectThrows(
            ResponseException.class,
            () -> enableEnrichment("index-that-does-not-exist", "title", "title_semantic")
        );

        assertEquals(404, ex.getResponse().getStatusLine().getStatusCode());
        String responseBody = EntityUtils.toString(ex.getResponse().getEntity());
        assertTrue(responseBody.contains("does not exist"));
    }

    // ==========================================================================
    // Helpers
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
    private Map<String, Object> fetchMapping(String index) throws Exception {
        Response response = makeRequest(
            client(),
            "GET",
            "/" + index + "/_mapping",
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
        return (Map<String, Object>) indexMap.get("mappings");
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
    private Map<String, Object> getProperties(Map<String, Object> mapping) {
        return (Map<String, Object>) mapping.get("properties");
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
        // Navigate settings like "index" -> "default_pipeline"
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
