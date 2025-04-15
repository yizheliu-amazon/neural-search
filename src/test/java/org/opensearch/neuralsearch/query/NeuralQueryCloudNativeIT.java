/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.query;

import lombok.extern.log4j.Log4j2;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.junit.Test;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.neuralsearch.BaseNeuralSearchCloudNativeIT;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

import static org.apache.http.entity.ContentType.APPLICATION_JSON;

@Log4j2
public class NeuralQueryCloudNativeIT extends BaseNeuralSearchCloudNativeIT {
    private static final String SERVICE_NAME = "aoss";
    private static final String ACCOUNT_ID = "058264223758";
    private static final String COLLECTION_ID = "iqgbxpohpaemd8jwyui2";

    @Test
    public void testGetModel() throws IOException {
        try {
            System.out.println("Before sending request");
            // Change the hard code model id to env variable
            Response response = client().performRequest(new Request("GET", "/_plugins/_ml/models/46803f57-f27a-4526-a8b5-2bcb226bbf96"));
            System.out.println("Have successfully got response");
            System.out.println(EntityUtils.toString(response.getEntity()));
            // throw new ResponseException(response);
        } catch (ResponseException e) {
            System.out.println("Exception is caught during getModel:");
            System.out.println(Arrays.toString(e.getResponse().getHeaders()) + ' ' + EntityUtils.toString(e.getResponse().getEntity()));
        }
    }

    @Test
    public void testGetIndex() throws IOException {
        log.info("Hello world");
        try {
            // Change the hard code model id to env variable
            StringBuilder stringBuilderForContentBody = new StringBuilder();
            stringBuilderForContentBody.append("{\"description\": \"Post processor pipeline\",")
                .append("\"phase_results_processors\": [{ ")
                .append("\"normalization-processor\": {")
                .append("\"normalization\": {")
                .append("\"technique\": \"%s\"")
                .append("},")
                .append("\"combination\": {")
                .append("\"technique\": \"%s\"");
            stringBuilderForContentBody.append("}").append("}}]}");
            Request request = new Request("PUT", "/_search/pipeline/phase-results-pipeline");
            request.setEntity(
                new StringEntity(
                    String.format(Locale.ROOT, stringBuilderForContentBody.toString(), "min_max", "arithmetic_mean"),
                    APPLICATION_JSON
                )
            );
            Response putResponse = client().performRequest(request);
            System.out.println("Have successfully got putResponse");
            System.out.println(EntityUtils.toString(putResponse.getEntity()));
            Response getResponse = client().performRequest(new Request("GET", "/_search/pipeline/phase-results-pipeline"));
            System.out.println("Have successfully got getResponse");
            System.out.println(EntityUtils.toString(getResponse.getEntity()));
        } catch (ResponseException e) {
            System.out.println("Exception is caught during getIndex:");
            System.out.println(Arrays.toString(e.getResponse().getHeaders()) + ' ' + EntityUtils.toString(e.getResponse().getEntity()));
        }
    }

    @Test
    public void testHybridSearch() throws IOException {
        log.info("Hello world");
        try {
            // Change the hard code model id to env variable
            Response response = client().performRequest(new Request("GET", "/_plugins/_ml/models/eec0cfe2-64b6-4a52-9d62-94007e1d3b16"));
        } catch (ResponseException e) {
            System.out.println(Arrays.toString(e.getResponse().getHeaders()) + ' ' + EntityUtils.toString(e.getResponse().getEntity()));
        }
    }

}
