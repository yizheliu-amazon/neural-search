/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor.semantic;

import java.util.HashMap;
import java.util.Map;

import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.Before;
import org.opensearch.client.Response;
import org.opensearch.neuralsearch.BaseNeuralSearchIT;

import com.google.common.collect.ImmutableList;

import static org.opensearch.neuralsearch.util.TestUtils.DEFAULT_USER_AGENT;

/**
 * End-to-end experiment with a REAL deployed model and REAL embeddings, to decide how ASE should
 * route data from original_field -> semantic_field.
 *
 * BACKGROUND
 * ----------
 * ASE's current approach puts a standard `set` (copy) or `rename` processor in the customer's
 * ingest pipeline. Embeddings are generated separately by SemanticFieldProcessor, a SYSTEM ingest
 * processor. Those two have different triggering rules for update operations.
 *
 * IngestPipelineUpdateSemanticsIT already proved (without needing a model) that neither
 * default_pipeline nor final_pipeline runs on a bulk partial update. This class supplies the other
 * half of the picture:
 *
 *   testSystemPipeline_bulkPartialUpdate_regeneratesEmbedding
 *       -> does the SYSTEM pipeline run on a bulk partial update?  (expected: YES)
 *
 *   testAseRoutingSimulation_bulkPartialUpdateOfSourceField_leavesEmbeddingStale
 *       -> the full ASE topology, showing the resulting silent staleness
 *
 * Taken together these tell us whether moving the copy/rename into a system processor would fix
 * the gap that the pipeline-based approach cannot.
 *
 * The ASE test here reproduces the post-enable index state directly (semantic field + `set`
 * processor as default_pipeline) rather than calling the enable API, because the enable API
 * resolves its own pretrained model and gives us no way to inject the test model.
 */
public class SemanticFieldUpdateSemanticsIT extends BaseNeuralSearchIT {

    private static final String PLAIN_INDEX = "semantic-update-plain-it";
    private static final String ROUTED_INDEX = "semantic-update-routed-it";
    private static final String ROUTED_FINAL_INDEX = "semantic-update-routed-final-it";
    private static final String ROUTING_PIPELINE = "ase-routing-sim-pipeline-it";

    private static final String SOURCE_FIELD = "content";
    private static final String SEMANTIC_FIELD = "content_semantic";
    private static final String EMBEDDING_PARENT = "content_semantic_semantic_info";
    private static final String EMBEDDING_FIELD = "embedding";

    private static final String ORIGINAL_TEXT = "A quick brown fox jumps over the lazy dog near the riverbank.";
    private static final String UPDATED_TEXT = "Quantum chromodynamics describes the strong interaction between quarks.";

    @Before
    public void setUp() throws Exception {
        super.setUp();
        updateClusterSettings();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        try {
            deleteIndex(PLAIN_INDEX);
        } catch (Exception ignored) {}
        try {
            deleteIndex(ROUTED_INDEX);
        } catch (Exception ignored) {}
        try {
            deleteIndex(ROUTED_FINAL_INDEX);
        } catch (Exception ignored) {}
        try {
            deleteIngestPipeline(ROUTING_PIPELINE);
        } catch (Exception ignored) {}
    }

    // ==========================================================================
    // Does the SYSTEM ingest pipeline run on a bulk partial update?
    // ==========================================================================

    /**
     * Writes directly into a plain semantic field (no routing involved), then issues a bulk partial
     * update to that same field. If the embedding changes, the system ingest pipeline ran.
     */
    public void testSystemPipeline_bulkPartialUpdate_regeneratesEmbedding() throws Exception {
        String modelId = prepareSparseEncodingModel();
        loadAndWaitForModelToBeReady(modelId);
        createIndexWithModelId(PLAIN_INDEX, "semantic/AseUpdatePlainSemanticMapping.json", modelId);

        // Write text straight into the semantic field — the normal semantic field usage pattern.
        indexJson(PLAIN_INDEX, "1", "{\"" + SEMANTIC_FIELD + "\":\"" + ORIGINAL_TEXT + "\"}");

        Object embeddingBefore = getEmbedding(PLAIN_INDEX, "1");
        assertNotNull("Embedding should be generated on initial index", embeddingBefore);
        assertEquals("precondition: semantic field holds the original text", ORIGINAL_TEXT, getField(PLAIN_INDEX, "1", SEMANTIC_FIELD));

        // Bulk partial update of the semantic field itself.
        bulkPartialUpdate(PLAIN_INDEX, "1", SEMANTIC_FIELD, UPDATED_TEXT, false);

        assertEquals("Semantic field should hold the updated text", UPDATED_TEXT, getField(PLAIN_INDEX, "1", SEMANTIC_FIELD));

        Object embeddingAfter = getEmbedding(PLAIN_INDEX, "1");
        assertNotNull("Embedding should still be present after the update", embeddingAfter);
        assertNotEquals(
            "System ingest pipeline SHOULD run on a bulk partial update, so the embedding must be "
                + "regenerated for the new text. If this fails, system processors do not run on bulk "
                + "partial updates either, and moving routing into a system processor would NOT fix the gap.",
            embeddingBefore,
            embeddingAfter
        );
    }

    // ==========================================================================
    // Full ASE topology: the resulting silent staleness
    // ==========================================================================

    /**
     * Reproduces ASE copy mode: a `set` processor in default_pipeline copies content -> content_semantic,
     * and the system processor embeds content_semantic.
     *
     * A bulk partial update to the SOURCE field skips the copy (default_pipeline does not run) but
     * still triggers the system processor, which re-embeds the unchanged, now-stale semantic field.
     */
    public void testAseRoutingSimulation_bulkPartialUpdateOfSourceField_leavesEmbeddingStale() throws Exception {
        String modelId = prepareSparseEncodingModel();
        loadAndWaitForModelToBeReady(modelId);

        // ASE copy mode: set content_semantic = content
        putIngestPipeline(
            ROUTING_PIPELINE,
            "{\"description\":\"ASE copy mode simulation\",\"processors\":[{\"set\":{\"field\":\""
                + SEMANTIC_FIELD
                + "\",\"value\":\"{{"
                + SOURCE_FIELD
                + "}}\"}}]}"
        );
        createIndexWithModelId(ROUTED_INDEX, "semantic/AseUpdateRoutedSemanticMapping.json", modelId);

        // Normal index: routing copies source -> semantic, then the system processor embeds it.
        indexJson(ROUTED_INDEX, "1", "{\"" + SOURCE_FIELD + "\":\"" + ORIGINAL_TEXT + "\"}");

        assertEquals("Routing should copy source into the semantic field", ORIGINAL_TEXT, getField(ROUTED_INDEX, "1", SEMANTIC_FIELD));
        Object embeddingBefore = getEmbedding(ROUTED_INDEX, "1");
        assertNotNull("Embedding should be generated on initial index", embeddingBefore);

        // Bulk partial update of the SOURCE field.
        bulkPartialUpdate(ROUTED_INDEX, "1", SOURCE_FIELD, UPDATED_TEXT, false);

        String sourceAfter = getField(ROUTED_INDEX, "1", SOURCE_FIELD);
        String semanticAfter = getField(ROUTED_INDEX, "1", SEMANTIC_FIELD);
        Object embeddingAfter = getEmbedding(ROUTED_INDEX, "1");

        assertEquals("Source field should reflect the update", UPDATED_TEXT, sourceAfter);

        // The bug: routing was skipped, so the semantic field still holds the old text.
        assertEquals(
            String.format(
                java.util.Locale.ROOT,
                "Routing is skipped on bulk partial update, so the semantic field is STALE. " + "Observed source=[%s] semantic=[%s]",
                sourceAfter,
                semanticAfter
            ),
            ORIGINAL_TEXT,
            semanticAfter
        );

        // And the embedding therefore does not track the source field.
        assertEquals(
            "Embedding was regenerated from the unchanged stale semantic field, so it is identical to "
                + "before and does not reflect the updated source text.",
            embeddingBefore,
            embeddingAfter
        );
    }

    /**
     * Control: the same topology with doc_as_upsert DOES run the pipeline, so routing happens and
     * the embedding tracks the source field. This is the documented workaround.
     */
    public void testAseRoutingSimulation_bulkPartialUpdateWithDocAsUpsert_staysConsistent() throws Exception {
        String modelId = prepareSparseEncodingModel();
        loadAndWaitForModelToBeReady(modelId);

        putIngestPipeline(
            ROUTING_PIPELINE,
            "{\"description\":\"ASE copy mode simulation\",\"processors\":[{\"set\":{\"field\":\""
                + SEMANTIC_FIELD
                + "\",\"value\":\"{{"
                + SOURCE_FIELD
                + "}}\"}}]}"
        );
        createIndexWithModelId(ROUTED_INDEX, "semantic/AseUpdateRoutedSemanticMapping.json", modelId);

        indexJson(ROUTED_INDEX, "1", "{\"" + SOURCE_FIELD + "\":\"" + ORIGINAL_TEXT + "\"}");
        Object embeddingBefore = getEmbedding(ROUTED_INDEX, "1");

        bulkPartialUpdate(ROUTED_INDEX, "1", SOURCE_FIELD, UPDATED_TEXT, true);

        assertEquals("Source field should reflect the update", UPDATED_TEXT, getField(ROUTED_INDEX, "1", SOURCE_FIELD));
        assertEquals(
            "With doc_as_upsert the pipeline runs, so routing keeps the semantic field in sync",
            UPDATED_TEXT,
            getField(ROUTED_INDEX, "1", SEMANTIC_FIELD)
        );
        assertNotEquals("Embedding should be regenerated for the new text", embeddingBefore, getEmbedding(ROUTED_INDEX, "1"));
    }

    // ==========================================================================
    // final_pipeline variant of the ASE topology
    // ==========================================================================

    /**
     * Confirms final_pipeline works at all for ASE: routing runs before the system processor, so a
     * normal index produces a populated semantic field and a real embedding.
     */
    public void testAseRoutingFinalPipeline_normalIndex_producesEmbedding() throws Exception {
        String modelId = prepareSparseEncodingModel();
        loadAndWaitForModelToBeReady(modelId);

        putIngestPipeline(
            ROUTING_PIPELINE,
            "{\"description\":\"ASE copy mode simulation\",\"processors\":[{\"set\":{\"field\":\""
                + SEMANTIC_FIELD
                + "\",\"value\":\"{{"
                + SOURCE_FIELD
                + "}}\"}}]}"
        );
        createIndexWithModelId(ROUTED_FINAL_INDEX, "semantic/AseUpdateRoutedFinalSemanticMapping.json", modelId);

        indexJson(ROUTED_FINAL_INDEX, "1", "{\"" + SOURCE_FIELD + "\":\"" + ORIGINAL_TEXT + "\"}");

        assertEquals(
            "final_pipeline routing should copy source into the semantic field",
            ORIGINAL_TEXT,
            getField(ROUTED_FINAL_INDEX, "1", SEMANTIC_FIELD)
        );
        assertNotNull(
            "Embedding should be generated -- confirms final_pipeline runs BEFORE the system processor",
            getEmbedding(ROUTED_FINAL_INDEX, "1")
        );
    }

    /** final_pipeline has the same staleness gap on bulk partial update. */
    public void testAseRoutingFinalPipeline_bulkPartialUpdate_leavesEmbeddingStale() throws Exception {
        String modelId = prepareSparseEncodingModel();
        loadAndWaitForModelToBeReady(modelId);

        putIngestPipeline(
            ROUTING_PIPELINE,
            "{\"description\":\"ASE copy mode simulation\",\"processors\":[{\"set\":{\"field\":\""
                + SEMANTIC_FIELD
                + "\",\"value\":\"{{"
                + SOURCE_FIELD
                + "}}\"}}]}"
        );
        createIndexWithModelId(ROUTED_FINAL_INDEX, "semantic/AseUpdateRoutedFinalSemanticMapping.json", modelId);

        indexJson(ROUTED_FINAL_INDEX, "1", "{\"" + SOURCE_FIELD + "\":\"" + ORIGINAL_TEXT + "\"}");
        Object embeddingBefore = getEmbedding(ROUTED_FINAL_INDEX, "1");

        bulkPartialUpdate(ROUTED_FINAL_INDEX, "1", SOURCE_FIELD, UPDATED_TEXT, false);

        assertEquals("Source field should reflect the update", UPDATED_TEXT, getField(ROUTED_FINAL_INDEX, "1", SOURCE_FIELD));
        assertEquals(
            "final_pipeline is also skipped on bulk partial update, so the semantic field is STALE",
            ORIGINAL_TEXT,
            getField(ROUTED_FINAL_INDEX, "1", SEMANTIC_FIELD)
        );
        assertEquals("Embedding does not track the source field", embeddingBefore, getEmbedding(ROUTED_FINAL_INDEX, "1"));
    }

    /** THE VIABILITY TEST: final_pipeline + doc_as_upsert keeps routing and embedding consistent. */
    public void testAseRoutingFinalPipeline_bulkPartialUpdateWithDocAsUpsert_staysConsistent() throws Exception {
        String modelId = prepareSparseEncodingModel();
        loadAndWaitForModelToBeReady(modelId);

        putIngestPipeline(
            ROUTING_PIPELINE,
            "{\"description\":\"ASE copy mode simulation\",\"processors\":[{\"set\":{\"field\":\""
                + SEMANTIC_FIELD
                + "\",\"value\":\"{{"
                + SOURCE_FIELD
                + "}}\"}}]}"
        );
        createIndexWithModelId(ROUTED_FINAL_INDEX, "semantic/AseUpdateRoutedFinalSemanticMapping.json", modelId);

        indexJson(ROUTED_FINAL_INDEX, "1", "{\"" + SOURCE_FIELD + "\":\"" + ORIGINAL_TEXT + "\"}");
        Object embeddingBefore = getEmbedding(ROUTED_FINAL_INDEX, "1");

        bulkPartialUpdate(ROUTED_FINAL_INDEX, "1", SOURCE_FIELD, UPDATED_TEXT, true);

        assertEquals("Source field should reflect the update", UPDATED_TEXT, getField(ROUTED_FINAL_INDEX, "1", SOURCE_FIELD));
        assertEquals(
            "With doc_as_upsert, final_pipeline runs and routing keeps the semantic field in sync",
            UPDATED_TEXT,
            getField(ROUTED_FINAL_INDEX, "1", SEMANTIC_FIELD)
        );
        assertNotEquals(
            "Embedding should be regenerated from the updated text -- this is what makes " + "final_pipeline + doc_as_upsert a viable path",
            embeddingBefore,
            getEmbedding(ROUTED_FINAL_INDEX, "1")
        );
    }

    // ==========================================================================
    // Helpers
    // ==========================================================================

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

    private void indexJson(String index, String id, String json) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("refresh", "true");
        makeRequest(
            client(),
            "PUT",
            "/" + index + "/_doc/" + id,
            params,
            toHttpEntity(json),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    /** Bulk partial update of a single field. Newline-delimited with trailing newline. */
    private void bulkPartialUpdate(String index, String id, String field, String value, boolean docAsUpsert) throws Exception {
        String action = "{\"update\":{\"_index\":\"" + index + "\",\"_id\":\"" + id + "\"}}\n";
        String doc = docAsUpsert
            ? "{\"doc\":{\"" + field + "\":\"" + value + "\"},\"doc_as_upsert\":true}\n"
            : "{\"doc\":{\"" + field + "\":\"" + value + "\"}}\n";

        Map<String, String> params = new HashMap<>();
        params.put("refresh", "true");
        Response response = makeRequest(
            client(),
            "POST",
            "/_bulk",
            params,
            toHttpEntity(action + doc),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
        String body = EntityUtils.toString(response.getEntity());
        assertFalse("bulk update reported an error: " + body, body.contains("\"error\""));
    }

    @SuppressWarnings("unchecked")
    private String getField(String index, String id, String field) {
        Map<String, Object> source = (Map<String, Object>) getDocById(index, id).get("_source");
        Object value = source == null ? null : source.get(field);
        return value == null ? null : value.toString();
    }

    /** Pull the embedding out of {semanticField}_semantic_info.embedding. */
    @SuppressWarnings("unchecked")
    private Object getEmbedding(String index, String id) {
        Map<String, Object> source = (Map<String, Object>) getDocById(index, id).get("_source");
        if (source == null) return null;
        Object parent = source.get(EMBEDDING_PARENT);
        if (parent instanceof Map<?, ?> parentMap) {
            return parentMap.get(EMBEDDING_FIELD);
        }
        return null;
    }
}
