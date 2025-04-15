/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.interceptor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.http.Header;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HttpContext;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4aHttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

import static org.apache.http.protocol.HttpCoreContext.HTTP_TARGET_HOST;

/**
 * An {@link HttpRequestInterceptor} that signs requests using any AWS {@link AwsV4aHttpSigner}
 * and {@link AwsCredentialsProvider}.
 */
public class AWSHttpRequestSigningInterceptor implements HttpRequestInterceptor {
    /**
     * The service that we're connecting to. Technically not necessary.
     * Could be used by a future Signer, though.
     */
    private final String service;

    /**
     * The region where service that we're connecting to is located.
     */
    private final String region;

    /**
     * The particular signer implementation.
     */
    private final AwsV4HttpSigner signer;

    /**
     * The source of AWS credentials for signing.
     */
    private final AwsCredentialsIdentity awsCredentialsIdentity;

    private final String host;

    /**
     *
     * @param service service that we're connecting to
     * @param signer particular signer implementation
     * @param awsCredentialsIdentity source of AWS credentials for signing
     */
    public AWSHttpRequestSigningInterceptor(
        final String service,
        final String region,
        final AwsV4HttpSigner signer,
        final AwsCredentialsIdentity awsCredentialsIdentity,
        final String host
    ) {
        this.service = service;
        this.region = region;
        this.signer = signer;
        this.awsCredentialsIdentity = awsCredentialsIdentity;
        this.host = host;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void process(final HttpRequest request, final HttpContext context) throws HttpException, IOException {
        URIBuilder uriBuilder;
        URI uri;
        try {
            uriBuilder = new URIBuilder(request.getRequestLine().getUri());
            // uriBuilder = new URIBuilder(this.host);
            // uriBuilder.setHost(this.host);
            uri = uriBuilder.build();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URI", e);
        }
        // Copy Apache HttpRequest to AWS request
        SdkHttpFullRequest.Builder httpRequestBuilder = SdkHttpFullRequest.builder()
            .uri(uri)
            .method(SdkHttpMethod.fromValue(request.getRequestLine().getMethod()))
            .encodedPath(uri.getRawPath());

        if (request instanceof HttpEntityEnclosingRequest) {
            HttpEntityEnclosingRequest httpEntityEnclosingRequest = (HttpEntityEnclosingRequest) request;
            if (httpEntityEnclosingRequest.getEntity() != null) {
                httpRequestBuilder.contentStreamProvider(
                    ContentStreamProvider.fromInputStream(httpEntityEnclosingRequest.getEntity().getContent())
                );
            }
        }
        nvpToMapParams(uriBuilder.getQueryParams()).forEach(httpRequestBuilder::putRawQueryParameter);
        httpRequestBuilder.headers(headerArrayToMapOfList(request.getAllHeaders()));
        // temp fix
        httpRequestBuilder.protocol("https");
        HttpHost host = (HttpHost) context.getAttribute(HTTP_TARGET_HOST);
        httpRequestBuilder.host(host.toHostString());
        SdkHttpRequest httpRequest = httpRequestBuilder.build();

        // Copy Apache HttpRequest to AWS DefaultRequest
        // DefaultRequest<?> signableRequest = new DefaultRequest<>(service);

        // HttpHost host = (HttpHost) context.getAttribute(HTTP_TARGET_HOST);
        // if (host != null) {
        // signableRequest.setEndpoint(URI.create(host.toURI()));
        // }
        // final HttpMethodName httpMethod = HttpMethodName.fromValue(request.getRequestLine().getMethod());
        // signableRequest.setHttpMethod(httpMethod);
        // try {
        // signableRequest.setResourcePath(uri.getRawPath());
        // } catch (URISyntaxException e) {
        // throw new IOException("Invalid URI", e);
        // }
        //
        // if (request instanceof HttpEntityEnclosingRequest) {
        // HttpEntityEnclosingRequest httpEntityEnclosingRequest = (HttpEntityEnclosingRequest) request;
        // if (httpEntityEnclosingRequest.getEntity() != null) {
        // signableRequest.setContent(httpEntityEnclosingRequest.getEntity().getContent());
        // }
        // }
        // signableRequest.setParameters(nvpToMapParams(uriBuilder.getQueryParams()));
        // signableRequest.setHeaders(headerArrayToMap(request.getAllHeaders()));
        //
        // // Sign it
        // signer.sign(signableRequest, awsCredentialsIdentity.getCredentials());

        // sign the request
        SignedRequest signedRequest = signer.sign(
            r -> r.identity(this.awsCredentialsIdentity)
                .request(httpRequest)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, this.service)
                .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false)
                .putProperty(AwsV4HttpSigner.REGION_NAME, this.region)
        );

        signedRequest.request().headers().forEach((key, values) -> {
            if (values.size() > 1) {
                System.out.println(" key: " + key + " has more than 1 values in headers");
            }
            // values.forEach(value -> System.out.println(" " + key + ": " + value));
        });

        // Now copy everything back
        request.setHeaders(mapToHeaderArray(signedRequest.request().headers()));
        if (request instanceof HttpEntityEnclosingRequest) {
            HttpEntityEnclosingRequest httpEntityEnclosingRequest = (HttpEntityEnclosingRequest) request;
            if (httpEntityEnclosingRequest.getEntity() != null) {
                BasicHttpEntity basicHttpEntity = new BasicHttpEntity();
                ContentStreamProvider contentStreamProvider = signedRequest.payload().orElse(null);
                if (contentStreamProvider != null) {
                    InputStream content = contentStreamProvider.newStream();
                    basicHttpEntity.setContent(content);
                    httpEntityEnclosingRequest.setEntity(basicHttpEntity);
                }
            }
        }
    }

    /**
     *
     * @param params list of HTTP query params as NameValuePairs
     * @return a multimap of HTTP query params
     */
    private static Map<String, List<String>> nvpToMapParams(final List<NameValuePair> params) {
        Map<String, List<String>> parameterMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (NameValuePair nvp : params) {
            List<String> argsList = parameterMap.computeIfAbsent(nvp.getName(), k -> new ArrayList<>());
            argsList.add(nvp.getValue());
        }
        return parameterMap;
    }

    /**
     * @param headers modeled Header objects
     * @return a Map of header entries
     */
    private static Map<String, String> headerArrayToMap(final Header[] headers) {
        Map<String, String> headersMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header header : headers) {
            if (!skipHeader(header)) {
                headersMap.put(header.getName(), header.getValue());
            }
        }
        return headersMap;
    }

    /**
     * @param headers modeled Header objects
     * @return a Map of header entries
     */
    private static Map<String, List<String>> headerArrayToMapOfList(final Header[] headers) {
        Map<String, List<String>> headersMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header header : headers) {
            if (!skipHeader(header)) {
                headersMap.put(header.getName(), Collections.singletonList(header.getValue()));
            }
        }
        return headersMap;
    }

    /**
     * @param header header line to check
     * @return true if the given header should be excluded when signing
     */
    private static boolean skipHeader(final Header header) {
        return ("content-length".equalsIgnoreCase(header.getName()) && "0".equals(header.getValue())) // Strip Content-Length: 0
            || "host".equalsIgnoreCase(header.getName()); // Host comes from endpoint
    }

    /**
     * @param mapHeaders Map of header entries
     * @return modeled Header objects
     */
    private static Header[] mapToHeaderArray(final Map<String, List<String>> mapHeaders) {
        // System.out.println("Copying signed headers back to apache");
        Header[] headers = new Header[mapHeaders.size()];
        int i = 0;
        for (Map.Entry<String, List<String>> headerEntry : mapHeaders.entrySet()) {
            // System.out.println("header key[headerEntry.getKey()]: " + headerEntry.getKey() + " value: " + headerEntry.getValue());
            headers[i++] = new BasicHeader(headerEntry.getKey(), headerEntry.getValue().get(0));
        }
        return headers;
    }
}
