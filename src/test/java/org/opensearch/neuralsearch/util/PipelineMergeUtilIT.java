/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.util;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.HashSet;

import lombok.SneakyThrows;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicHeader;
import org.opensearch.client.Response;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.neuralsearch.BaseNeuralSearchIT;

import com.google.common.collect.ImmutableList;

import static org.opensearch.neuralsearch.util.PipelineMergeUtil.ASE_MANAGED_TAG;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.FIELD_MAP_KEY;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.PROCESSORS_KEY;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.REQUEST_PROCESSORS_KEY;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.SPARSE_ENCODING_PROCESSOR;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.TAG_KEY;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.mergeIngestPipeline;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.mergeSearchPipeline;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.scanIngestConflicts;
import static org.opensearch.neuralsearch.util.PipelineMergeUtil.MergeResult;
import static org.opensearch.neuralsearch.util.TestUtils.DEFAULT_USER_AGENT;

/**
 * Integration tests for PipelineMergeUtil.
 * Tests merge logic against a real OpenSearch cluster.
 */
public class PipelineMergeUtilIT extends BaseNeuralSearchIT {

    private static final String INGEST_PIPELINE = "merge-it-ingest";
    private static final String SEARCH_PIPELINE = "merge-it-search";

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        try {
            deleteIngestPipeline(INGEST_PIPELINE);
        } catch (Exception ignored) {}
        try {
            deleteIngestPipeline(INGEST_PIPELINE + "-sub");
        } catch (Exception ignored) {}
        try {
            deleteSearchPipeline(SEARCH_PIPELINE);
        } catch (Exception ignored) {}
    }

    @SneakyThrows
    public void testMergeSearchPipeline_customerFilterQuery_mergedSuccessfully() {
        String pipelineBody = "{\"request_processors\": ["
            + "{\"filter_query\": {\"tag\": \"customer\", \"query\": {\"term\": {\"status\": \"published\"}}}}"
            + "]}";
        putSearchPipeline(SEARCH_PIPELINE, pipelineBody);

        Map<String, Object> existing = getSearchPipelineConfig(SEARCH_PIPELINE);
        List<Map<String, Object>> aseProcessors = List.of(
            Map.of("neural_sparse_two_phase_processor", Map.of(TAG_KEY, ASE_MANAGED_TAG, "enabled", true))
        );

        MergeResult result = mergeSearchPipeline(existing, aseProcessors);

        assertTrue("Merge should succeed", result.canMerge());
        assertTrue("Conflicts should be empty", result.getConflicts().isEmpty());

        List<Map<String, Object>> merged = (List<Map<String, Object>>) result.getMergedPipeline().get(REQUEST_PROCESSORS_KEY);
        assertEquals(2, merged.size());
        assertTrue(merged.get(0).containsKey("neural_sparse_two_phase_processor"));
        assertTrue(merged.get(1).containsKey("filter_query"));

        // PUT merged pipeline back - verify it's accepted
        XContentBuilder builder = XContentFactory.jsonBuilder().map(result.getMergedPipeline());
        putSearchPipeline(SEARCH_PIPELINE, builder.toString());

        Map<String, Object> readBack = getSearchPipelineConfig(SEARCH_PIPELINE);
        List<Map<String, Object>> readBackProcs = (List<Map<String, Object>>) readBack.get(REQUEST_PROCESSORS_KEY);
        assertEquals(2, readBackProcs.size());
    }

    @SneakyThrows
    public void testMergeIngestPipeline_customerLowercase_aseAppended() {
        String pipelineBody = "{\"description\": \"Customer cleanup\", \"processors\": [" + "{\"lowercase\": {\"field\": \"text\"}}" + "]}";
        putIngestPipeline(INGEST_PIPELINE, pipelineBody);

        Map<String, Object> existing = getIngestPipelineConfig(INGEST_PIPELINE);
        Map<String, Object> aseProcessor = Map.of(
            SPARSE_ENCODING_PROCESSOR,
            Map.of(TAG_KEY, ASE_MANAGED_TAG, "model_id", "test-model", FIELD_MAP_KEY, Map.of("text", "text_sparse"))
        );

        MergeResult result = mergeIngestPipeline(existing, Set.of("text"), aseProcessor);

        assertTrue(result.canMerge());
        List<Map<String, Object>> merged = (List<Map<String, Object>>) result.getMergedPipeline().get(PROCESSORS_KEY);
        assertEquals(2, merged.size());
        assertTrue(merged.get(0).containsKey("lowercase"));
        assertTrue(merged.get(1).containsKey(SPARSE_ENCODING_PROCESSOR));
    }

    @SneakyThrows
    public void testMergeIngestPipeline_removeConflict_blocked() {
        String pipelineBody = "{\"processors\": ["
            + "{\"set\": {\"field\": \"x\", \"value\": true}},"
            + "{\"remove\": {\"field\": \"text\"}}"
            + "]}";
        putIngestPipeline(INGEST_PIPELINE, pipelineBody);

        Map<String, Object> existing = getIngestPipelineConfig(INGEST_PIPELINE);
        Map<String, Object> aseProcessor = Map.of(
            SPARSE_ENCODING_PROCESSOR,
            Map.of(TAG_KEY, ASE_MANAGED_TAG, "model_id", "test-model", FIELD_MAP_KEY, Map.of("text", "text_sparse"))
        );

        MergeResult result = mergeIngestPipeline(existing, Set.of("text"), aseProcessor);

        assertFalse("Should be blocked", result.canMerge());
        assertEquals(1, result.getConflicts().size());
        assertTrue(result.getConflicts().get(0).contains("text"));
    }

    @SneakyThrows
    public void testMergeSearchPipeline_aseAlreadyPresent_blocked() {
        String pipelineBody = "{\"request_processors\": ["
            + "{\"neural_sparse_two_phase_processor\": {\"tag\": \"ase_managed\", \"enabled\": true}},"
            + "{\"filter_query\": {\"tag\": \"customer\", \"query\": {\"term\": {\"status\": \"active\"}}}}"
            + "]}";
        putSearchPipeline(SEARCH_PIPELINE, pipelineBody);

        Map<String, Object> existing = getSearchPipelineConfig(SEARCH_PIPELINE);
        List<Map<String, Object>> aseProcessors = List.of(
            Map.of("neural_sparse_two_phase_processor", Map.of(TAG_KEY, ASE_MANAGED_TAG, "enabled", true))
        );

        MergeResult result = mergeSearchPipeline(existing, aseProcessors);

        assertFalse(result.canMerge());
        assertTrue(result.getConflicts().stream().anyMatch(c -> c.contains("ASE-managed")));
    }

    @SneakyThrows
    public void testScanIngestConflicts_subPipelineRemove_detected() {
        String subBody = "{\"processors\": [{\"remove\": {\"field\": \"text\"}}]}";
        putIngestPipeline(INGEST_PIPELINE + "-sub", subBody);

        String parentBody = "{\"processors\": ["
            + "{\"set\": {\"field\": \"level\", \"value\": \"top\"}},"
            + "{\"pipeline\": {\"name\": \""
            + INGEST_PIPELINE
            + "-sub\"}}"
            + "]}";
        putIngestPipeline(INGEST_PIPELINE, parentBody);

        Map<String, Object> parent = getIngestPipelineConfig(INGEST_PIPELINE);
        List<Map<String, Object>> processors = (List<Map<String, Object>>) parent.get(PROCESSORS_KEY);

        Function<String, Map<String, Object>> resolver = name -> {
            try {
                return getIngestPipelineConfig(name);
            } catch (Exception e) {
                return null;
            }
        };

        List<String> conflicts = scanIngestConflicts(processors, Set.of("text"), resolver, new HashSet<>());

        assertFalse(conflicts.isEmpty());
        assertTrue(conflicts.get(0).contains("sub-pipeline"));
    }

    // --- Helpers ---

    private void putSearchPipeline(String name, String body) throws Exception {
        makeRequest(
            client(),
            "PUT",
            "/_search/pipeline/" + name,
            null,
            toHttpEntity(body),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    private void putIngestPipeline(String name, String body) throws Exception {
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
    private Map<String, Object> getSearchPipelineConfig(String name) throws Exception {
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
    private Map<String, Object> getIngestPipelineConfig(String name) throws Exception {
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
}
