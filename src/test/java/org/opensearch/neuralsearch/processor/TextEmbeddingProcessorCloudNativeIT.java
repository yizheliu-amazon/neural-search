/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.opensearch.client.Response;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.neuralsearch.BaseNeuralSearchCloudNativeIT;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TextEmbeddingProcessorCloudNativeIT extends BaseNeuralSearchCloudNativeIT {
    private static final String INDEX_NAME = "text_embedding_index";

    private static final String PIPELINE_NAME = "pipeline-hybrid";
    protected static final String QUERY_TEXT = "hello";
    protected static final String LEVEL_1_FIELD = "nested_passages";
    protected static final String LEVEL_2_FIELD = "level_2";
    protected static final String LEVEL_3_FIELD_TEXT = "level_3_text";
    protected static final String LEVEL_3_FIELD_CONTAINER = "level_3_container";
    protected static final String LEVEL_3_FIELD_EMBEDDING = "level_3_embedding";
    protected static final String TEXT_FIELD_VALUE_1 = "hello";
    protected static final String TEXT_FIELD_VALUE_2 = "clown";
    protected static final String TEXT_FIELD_VALUE_3 = "abc";
    private final String INGEST_DOC1 = Files.readString(Path.of(classLoader.getResource("processor/ingest_doc1.json").toURI()));
    private final String INGEST_DOC2 = Files.readString(Path.of(classLoader.getResource("processor/ingest_doc2.json").toURI()));
    private final String INGEST_DOC3 = Files.readString(Path.of(classLoader.getResource("processor/ingest_doc3.json").toURI()));
    private final String INGEST_DOC4 = Files.readString(Path.of(classLoader.getResource("processor/ingest_doc4.json").toURI()));
    private final String BULK_ITEM_TEMPLATE = Files.readString(
        Path.of(classLoader.getResource("processor/bulk_item_template.json").toURI())
    );

    public TextEmbeddingProcessorCloudNativeIT() throws IOException, URISyntaxException {}

    public void testTextEmbeddingProcessor_batch() throws Exception {
        // String modelId = null;
        try {
            // modelId = uploadTextEmbeddingModel();
            // loadModel(modelId);
            createPipelineProcessor(MODEL_ID, PIPELINE_NAME, BaseNeuralSearchCloudNativeIT.ProcessorType.TEXT_EMBEDDING);
            createTextEmbeddingIndex();
            // [_bulk] endpoint does not allow param in AOSS
            // even after I removed params, AOSS colletion complains about bad request
            // however, I am able to i
            ingestBatchDocumentWithBulk("batch_", 2, 2, Collections.emptySet(), Collections.emptySet());
            assertEquals(2, getDocCount(INDEX_NAME));

            ingestDocument(String.format(LOCALE, INGEST_DOC1, "success"), "1");
            ingestDocument(String.format(LOCALE, INGEST_DOC2, "success"), "2");

            // assertEquals(getDocById(INDEX_NAME, "1").get("_source"), getDocById(INDEX_NAME, "batch_1").get("_source"));
            // assertEquals(getDocById(INDEX_NAME, "2").get("_source"), getDocById(INDEX_NAME, "batch_2").get("_source"));
        } catch (Exception e) {
            System.out.println("Exception is caught during testTextEmbeddingProcessor_batch");
            e.printStackTrace();
            throw e;
        } finally {
            // wipeOfTestResources(INDEX_NAME, PIPELINE_NAME, modelId, null);
        }
    }

    private void createTextEmbeddingIndex() throws Exception {
        createIndexWithConfiguration(
            INDEX_NAME,
            Files.readString(Path.of(classLoader.getResource("processor/IndexMappings.json").toURI())),
            PIPELINE_NAME
        );
    }

    private void ingestDocument(String doc, String id) throws Exception {
        String endpoint;
        if (StringUtils.isEmpty(id)) {
            endpoint = INDEX_NAME + "/_doc?refresh";
        } else {
            endpoint = INDEX_NAME + "/_doc/" + id + "?refresh";
        }
        Response response = makeRequest(
            client(),
            "POST",
            endpoint,
            null,
            toHttpEntity(doc),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, "Kibana"))
        );
        Map<String, Object> map = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(response.getEntity()),
            false
        );
        assertEquals("created", map.get("result"));
    }

    private void ingestBatchDocumentWithBulk(String idPrefix, int docCount, int batchSize, Set<Integer> failedIds, Set<Integer> droppedIds)
        throws Exception {
        StringBuilder payloadBuilder = new StringBuilder();
        for (int i = 0; i < docCount; ++i) {
            String docTemplate = List.of(INGEST_DOC1, INGEST_DOC2).get(i % 2);
            if (failedIds.contains(i)) {
                docTemplate = String.format(LOCALE, docTemplate, "fail");
            } else if (droppedIds.contains(i)) {
                docTemplate = String.format(LOCALE, docTemplate, "drop");
            } else {
                docTemplate = String.format(LOCALE, docTemplate, "success");
            }
            String doc = docTemplate.replace("\n", "");
            final String id = idPrefix + (i + 1);
            String item = BULK_ITEM_TEMPLATE.replace("{{index}}", INDEX_NAME).replace("{{id}}", id).replace("{{doc}}", doc);
            payloadBuilder.append(item).append("\n");
        }
        final String payload = payloadBuilder.toString();
        Map<String, String> params = new HashMap<>();
        // params.put("refresh", "true");
        // params.put("batch_size", String.valueOf(batchSize));
        Response response = makeRequest(
            client(),
            "POST",
            "_bulk",
            null,// params,
            toHttpEntity(payload),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, "Kibana"))
        );
        Map<String, Object> map = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(response.getEntity()),
            false
        );
        System.out.println("====================== Sleeping 3 mins after ingestion ====================== ");
        Thread.sleep(180 * 1000);
        assertEquals(!failedIds.isEmpty(), map.get("errors"));
        assertEquals(docCount, ((List) map.get("items")).size());
    }
}
