/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.rest;

import com.google.common.collect.ImmutableList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.opensearch.action.search.GetSearchPipelineAction;
import org.opensearch.action.search.GetSearchPipelineRequest;
import org.opensearch.action.search.PutSearchPipelineAction;
import org.opensearch.action.search.PutSearchPipelineRequest;
import org.opensearch.action.ingest.GetPipelineRequest;
import org.opensearch.action.ingest.GetPipelineResponse;
import org.opensearch.action.ingest.PutPipelineRequest;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.neuralsearch.plugin.NeuralSearch;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.BytesRestResponse;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.RestChannel;
import org.opensearch.transport.client.node.NodeClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * REST handler for semantic enrichment lifecycle APIs.
 *
 * Uses a customer-visible default ingest pipeline with standard set(copy)/rename processors
 * for data routing. The semantic field's system ingest processor handles embedding generation
 * independently — zero changes to semantic field internals.
 *
 * POST /_plugins/_neural/semantic/{index}/enable_semantic_enrichment
 * POST /_plugins/_neural/semantic/{index}/disable_semantic_enrichment
 * POST /_plugins/_neural/semantic/{index}/deploy_semantic_enrichment
 * POST /_plugins/_neural/semantic/{index}/rollback_semantic_enrichment
 * GET  /_plugins/_neural/semantic/{index}/list_semantic_enrichment
 */
public class RestSemanticEnableHandler extends BaseRestHandler {

    private static final Logger log = LogManager.getLogger(RestSemanticEnableHandler.class);
    private static final String SEMANTIC_ENABLE_ACTION = "neural_semantic_enable_action";
    private static final String INGEST_PIPELINE_SUFFIX = "-ase-ingest-pipeline";
    private static final String SEARCH_PIPELINE_SUFFIX = "-ase-search-pipeline";
    private static final String FIELD_REPLACEMENT_PROCESSOR_TYPE = "field_replacement_processor";
    private static final Set<String> COMPATIBLE_TYPES = Set.of("text", "keyword", "match_only_text", "wildcard", "token_count", "binary");

    private final ClusterService clusterService;

    public RestSemanticEnableHandler(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    @Override
    public String getName() {
        return SEMANTIC_ENABLE_ACTION;
    }

    @Override
    public List<Route> routes() {
        String base = NeuralSearch.NEURAL_BASE_URI + "/semantic/{index}";
        return ImmutableList.of(
            new Route(RestRequest.Method.POST, base + "/enable_semantic_enrichment"),
            new Route(RestRequest.Method.POST, base + "/disable_semantic_enrichment"),
            new Route(RestRequest.Method.POST, base + "/deploy_semantic_enrichment"),
            new Route(RestRequest.Method.POST, base + "/rollback_semantic_enrichment"),
            new Route(RestRequest.Method.GET, base + "/list_semantic_enrichment")
        );
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        String index = request.param("index");
        String path = request.path();
        if (path.endsWith("/list_semantic_enrichment")) return channel -> handleList(index, client, channel);
        Map<String, Object> body = request.contentParser().map();
        if (path.endsWith("/enable_semantic_enrichment")) return channel -> handleEnable(index, body, client, channel);
        if (path.endsWith("/disable_semantic_enrichment")) return channel -> handleDisable(index, body, client, channel);
        if (path.endsWith("/deploy_semantic_enrichment")) return channel -> handleDeploy(index, body, client, channel);
        if (path.endsWith("/rollback_semantic_enrichment")) return channel -> handleRollback(index, body, client, channel);
        return channel -> sendError(channel, RestStatus.BAD_REQUEST, "Unknown action: " + path);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractFields(Map<String, Object> body) {
        Object fieldsObj = body.get("fields");
        if (fieldsObj instanceof List<?>) return (List<Map<String, Object>>) fieldsObj;
        if (body.containsKey("original_field")) return List.of(body);
        return null;
    }

    // ========================= enable =========================

    @SuppressWarnings("unchecked")
    private void handleEnable(String index, Map<String, Object> body, NodeClient client, RestChannel channel) {
        List<Map<String, Object>> fields = extractFields(body);
        if (fields == null || fields.isEmpty()) {
            sendError(channel, RestStatus.BAD_REQUEST, "fields required");
            return;
        }

        IndexMetadata indexMetadata = clusterService.state().metadata().index(index);
        if (indexMetadata == null) {
            sendError(channel, RestStatus.NOT_FOUND, "Index [" + index + "] does not exist");
            return;
        }

        Map<String, Object> properties = indexMetadata.mapping() != null
            ? (Map<String, Object>) indexMetadata.mapping().sourceAsMap().get("properties")
            : Map.of();

        List<String[]> validated = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            String orig = (String) f.get("original_field");
            String sem = (String) f.get("semantic_field");
            String lang = (String) f.getOrDefault("language", "ENGLISH");
            String mtype = (String) f.getOrDefault("model_type", "SPARSE");
            if (orig == null || orig.isEmpty()) {
                sendError(channel, RestStatus.BAD_REQUEST, "original_field required");
                return;
            }
            if (sem == null || sem.isEmpty()) {
                sendError(channel, RestStatus.BAD_REQUEST, "semantic_field required");
                return;
            }
            if (properties.get(orig) == null) {
                sendError(channel, RestStatus.BAD_REQUEST, "original_field [" + orig + "] not found");
                return;
            }
            validated.add(new String[] { orig, sem, lang, mtype });
        }

        // PutMapping: create semantic field
        StringBuilder mb = new StringBuilder("{\"properties\":{");
        boolean first = true;
        for (String[] v : validated) {
            if (!first) mb.append(",");
            mb.append(
                String.format(
                    Locale.ROOT,
                    "\"%s\":{\"type\":\"semantic\",\"original_field\":\"%s\",\"language\":\"%s\",\"model_type\":\"%s\",\"status\":\"ENABLED\"}",
                    v[1],
                    v[0],
                    v[2].toUpperCase(Locale.ROOT),
                    v[3].toUpperCase(Locale.ROOT)
                )
            );
            first = false;
        }
        mb.append("}}");

        PutMappingRequest pmr = new PutMappingRequest(index);
        pmr.source(mb.toString(), XContentType.JSON);
        client.admin().indices().putMapping(pmr, ActionListener.wrap(resp -> {
            if (!resp.isAcknowledged()) {
                sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "PutMapping not acknowledged");
                return;
            }
            createEnrichingIngestPipeline(index, validated, client, channel);
        }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "PutMapping failed: " + e.getMessage())));
    }

    private void createEnrichingIngestPipeline(String index, List<String[]> validated, NodeClient client, RestChannel channel) {
        String pipeName = index + INGEST_PIPELINE_SUFFIX;
        String body = buildIngestPipelineBody(index, validated, false);
        PutPipelineRequest req = new PutPipelineRequest(pipeName, new BytesArray(body), XContentType.JSON);
        client.admin().cluster().putPipeline(req, ActionListener.wrap(resp -> {
            createSearchPipelineAndAttach(index, validated, client, channel, pipeName);
        }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Ingest pipeline failed: " + e.getMessage())));
    }

    @SuppressWarnings("unchecked")
    private void createSearchPipelineAndAttach(
        String index,
        List<String[]> validated,
        NodeClient client,
        RestChannel channel,
        String ingestPipeName
    ) {
        IndexMetadata updated = clusterService.state().metadata().index(index);
        Map<String, Object> props = updated != null && updated.mapping() != null
            ? (Map<String, Object>) updated.mapping().sourceAsMap().get("properties")
            : Map.of();

        StringJoiner fmEntries = new StringJoiner(",");
        for (String[] v : validated) {
            String mid = "unknown";
            if (props.get(v[1]) instanceof Map m) {
                Object o = m.get("model_id");
                if (o != null) mid = o.toString();
            }
            String target = v[1] + "_semantic_info.embedding";
            String type = "SPARSE".equalsIgnoreCase(v[3]) ? "sparse" : "dense";
            String analyzer = "SPARSE".equalsIgnoreCase(v[3]) ? ",\"analyzer\":\"bert-uncased\"" : "";
            fmEntries.add(
                String.format(
                    Locale.ROOT,
                    "\"%s\":{\"type\":\"%s\",\"target_field\":\"%s\",\"model_id\":\"%s\"%s}",
                    v[1],
                    type,
                    target,
                    mid,
                    analyzer
                )
            );
        }

        String searchPipeName = index + SEARCH_PIPELINE_SUFFIX;
        String searchBody = String.format(
            Locale.ROOT,
            "{\"request_processors\":[{\"semantic_search_rewrite_processor\":{\"field_map\":{%s}}},{\"neural_sparse_two_phase_processor\":{\"enabled\":true}}]}",
            fmEntries
        );

        PutSearchPipelineRequest spr = new PutSearchPipelineRequest(searchPipeName, new BytesArray(searchBody), XContentType.JSON);
        client.admin().cluster().execute(PutSearchPipelineAction.INSTANCE, spr, ActionListener.wrap(resp -> {
            // Attach both pipelines
            var sr = new org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest(index);
            sr.settings(
                org.opensearch.common.settings.Settings.builder()
                    .put("index.default_pipeline", ingestPipeName)
                    .put("index.search.default_pipeline", searchPipeName)
                    .build()
            );
            client.admin().indices().updateSettings(sr, ActionListener.wrap(settResp -> {
                sendResponse(
                    channel,
                    RestStatus.OK,
                    String.format(
                        Locale.ROOT,
                        "{\"acknowledged\":true,\"index\":\"%s\",\"ingest_pipeline\":\"%s\",\"search_pipeline\":\"%s\",\"state\":\"ENRICHING\"}",
                        index,
                        ingestPipeName,
                        searchPipeName
                    )
                );
            }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Settings failed: " + e.getMessage())));
        }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Search pipeline failed: " + e.getMessage())));
    }

    // ========================= deploy =========================

    private void handleDeploy(String index, Map<String, Object> body, NodeClient client, RestChannel channel) {
        List<String[]> fp = extractFieldPairs(body, channel);
        if (fp == null) return;
        String pipeName = index + INGEST_PIPELINE_SUFFIX;
        String pipeBody = buildIngestPipelineBody(index, fp, true);
        PutPipelineRequest req = new PutPipelineRequest(pipeName, new BytesArray(pipeBody), XContentType.JSON);
        client.admin().cluster().putPipeline(req, ActionListener.wrap(resp -> {
            addFieldReplacementProcessor(
                index,
                fp,
                client,
                channel,
                () -> sendResponse(channel, RestStatus.OK, "{\"acknowledged\":true,\"index\":\"" + index + "\",\"state\":\"DEPLOYED\"}")
            );
        }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Deploy failed: " + e.getMessage())));
    }

    // ========================= rollback =========================

    private void handleRollback(String index, Map<String, Object> body, NodeClient client, RestChannel channel) {
        List<String[]> fp = extractFieldPairs(body, channel);
        if (fp == null) return;
        String pipeName = index + INGEST_PIPELINE_SUFFIX;
        String pipeBody = buildIngestPipelineBody(index, fp, false);
        PutPipelineRequest req = new PutPipelineRequest(pipeName, new BytesArray(pipeBody), XContentType.JSON);
        client.admin().cluster().putPipeline(req, ActionListener.wrap(resp -> {
            removeFieldReplacementProcessor(
                index,
                client,
                channel,
                () -> sendResponse(channel, RestStatus.OK, "{\"acknowledged\":true,\"index\":\"" + index + "\",\"state\":\"ENRICHING\"}")
            );
        }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Rollback failed: " + e.getMessage())));
    }

    // ========================= disable =========================

    private void handleDisable(String index, Map<String, Object> body, NodeClient client, RestChannel channel) {
        List<String[]> fp = extractFieldPairs(body, channel);
        if (fp == null) return;
        removeFieldReplacementProcessor(index, client, channel, () -> {
            // Empty the ingest pipeline
            String pipeName = index + INGEST_PIPELINE_SUFFIX;
            String emptyBody = "{\"description\":\"ASE (DISABLED)\",\"processors\":[]}";
            PutPipelineRequest req = new PutPipelineRequest(pipeName, new BytesArray(emptyBody), XContentType.JSON);
            client.admin().cluster().putPipeline(req, ActionListener.wrap(resp -> {
                // Set status=DISABLED
                StringBuilder mb = new StringBuilder("{\"properties\":{");
                boolean mf = true;
                for (String[] f : fp) {
                    if (!mf) mb.append(",");
                    mb.append(String.format(Locale.ROOT, "\"%s\":{\"type\":\"semantic\",\"status\":\"DISABLED\"}", f[1]));
                    mf = false;
                }
                mb.append("}}");
                PutMappingRequest pmr = new PutMappingRequest(index);
                pmr.source(mb.toString(), XContentType.JSON);
                client.admin()
                    .indices()
                    .putMapping(
                        pmr,
                        ActionListener.wrap(
                            mr -> sendResponse(
                                channel,
                                RestStatus.OK,
                                "{\"acknowledged\":true,\"index\":\"" + index + "\",\"state\":\"DISABLED\"}"
                            ),
                            e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Disable mapping failed: " + e.getMessage())
                        )
                    );
            }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Disable pipeline failed: " + e.getMessage())));
        });
    }

    // ========================= list =========================

    @SuppressWarnings("unchecked")
    private void handleList(String index, NodeClient client, RestChannel channel) {
        IndexMetadata im = clusterService.state().metadata().index(index);
        if (im == null) {
            sendError(channel, RestStatus.NOT_FOUND, "Index not found");
            return;
        }
        Map<String, Object> props = im.mapping() != null ? (Map<String, Object>) im.mapping().sourceAsMap().get("properties") : Map.of();

        String pipeName = index + INGEST_PIPELINE_SUFFIX;
        client.admin().cluster().getPipeline(new GetPipelineRequest(pipeName), new ActionListener<GetPipelineResponse>() {
            public void onResponse(GetPipelineResponse r) {
                buildListResponse(channel, index, props, hasRename(r, pipeName));
            }

            public void onFailure(Exception e) {
                buildListResponse(channel, index, props, false);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private boolean hasRename(GetPipelineResponse r, String name) {
        if (r == null || r.pipelines() == null) return false;
        for (var c : r.pipelines()) {
            if (name.equals(c.getId())) {
                Object procs = c.getConfigAsMap().get("processors");
                if (procs instanceof List<?> l) {
                    for (Object p : l) {
                        if (p instanceof Map<?, ?> m && m.containsKey("rename")) return true;
                    }
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void buildListResponse(RestChannel channel, String index, Map<String, Object> props, boolean hasRename) {
        StringBuilder sb = new StringBuilder("{\"index\":\"" + index + "\",\"fields\":[");
        boolean first = true;
        for (var e : props.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> fd = (Map<String, Object>) e.getValue();
            if (!"semantic".equals(fd.get("type"))) continue;
            String of = (String) fd.get("original_field");
            if (of == null) continue;
            String status = (String) fd.getOrDefault("status", "ENABLED");
            String state = "DISABLED".equalsIgnoreCase(status) ? "DISABLED" : (hasRename ? "DEPLOYED" : "ENRICHING");
            if (!first) sb.append(",");
            sb.append(
                String.format(Locale.ROOT, "{\"original_field\":\"%s\",\"semantic_field\":\"%s\",\"state\":\"%s\"}", of, e.getKey(), state)
            );
            first = false;
        }
        sb.append("]}");
        sendResponse(channel, RestStatus.OK, sb.toString());
    }

    // ========================= Pipeline builders =========================

    private String buildIngestPipelineBody(String index, List<String[]> fieldPairs, boolean deployMode) {
        StringBuilder procs = new StringBuilder("[");
        boolean first = true;
        for (String[] fp : fieldPairs) {
            if (!first) procs.append(",");
            if (deployMode) {
                // rename: moves original_field → semantic_field
                procs.append(String.format(Locale.ROOT, "{\"rename\":{\"field\":\"%s\",\"target_field\":\"%s\"}}", fp[0], fp[1]));
            } else {
                // set with mustache template: copies original_field → semantic_field (preserves original)
                procs.append(String.format(Locale.ROOT, "{\"set\":{\"field\":\"%s\",\"value\":\"{{%s}}\"}}", fp[1], fp[0]));
            }
            first = false;
        }
        procs.append("]");
        String desc = deployMode ? "ASE ingest (DEPLOYED)" : "ASE ingest (ENRICHING)";
        return String.format(Locale.ROOT, "{\"description\":\"%s for %s\",\"processors\":%s}", desc, index, procs);
    }

    private List<String[]> extractFieldPairs(Map<String, Object> body, RestChannel channel) {
        List<Map<String, Object>> fields = extractFields(body);
        if (fields == null || fields.isEmpty()) {
            sendError(channel, RestStatus.BAD_REQUEST, "fields required");
            return null;
        }
        List<String[]> pairs = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            String o = (String) f.get("original_field");
            String s = (String) f.get("semantic_field");
            if (o == null || s == null) {
                sendError(channel, RestStatus.BAD_REQUEST, "original_field and semantic_field required");
                return null;
            }
            pairs.add(new String[] { o, s });
        }
        return pairs;
    }

    // ========================= Search pipeline helpers =========================

    @SuppressWarnings("unchecked")
    private void addFieldReplacementProcessor(String index, List<String[]> fp, NodeClient client, RestChannel channel, Runnable onSuccess) {
        String name = index + SEARCH_PIPELINE_SUFFIX;
        client.admin().cluster().execute(GetSearchPipelineAction.INSTANCE, new GetSearchPipelineRequest(name), ActionListener.wrap(gr -> {
            if (gr.pipelines() == null || gr.pipelines().isEmpty()) {
                sendError(channel, RestStatus.BAD_REQUEST, "Search pipeline missing");
                return;
            }
            Map<String, Object> cfg = new LinkedHashMap<>(gr.pipelines().get(0).getConfigAsMap());
            Map<String, Object> fm = new LinkedHashMap<>();
            for (String[] f : fp)
                fm.put(f[0], f[1]);
            Map<String, Object> frc = Map.of("field_map", fm);
            List<Map<String, Object>> rp = cfg.get("request_processors") instanceof List<?> l
                ? new ArrayList<>((List<Map<String, Object>>) l)
                : new ArrayList<>();
            rp.removeIf(p -> p.containsKey(FIELD_REPLACEMENT_PROCESSOR_TYPE));
            rp.add(0, Map.of(FIELD_REPLACEMENT_PROCESSOR_TYPE, frc));
            cfg.put("request_processors", rp);
            putSearchPipeline(name, cfg, client, channel, onSuccess);
        }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Get search pipeline failed: " + e.getMessage())));
    }

    @SuppressWarnings("unchecked")
    private void removeFieldReplacementProcessor(String index, NodeClient client, RestChannel channel, Runnable onSuccess) {
        String name = index + SEARCH_PIPELINE_SUFFIX;
        client.admin().cluster().execute(GetSearchPipelineAction.INSTANCE, new GetSearchPipelineRequest(name), ActionListener.wrap(gr -> {
            if (gr.pipelines() == null || gr.pipelines().isEmpty()) {
                onSuccess.run();
                return;
            }
            Map<String, Object> cfg = new LinkedHashMap<>(gr.pipelines().get(0).getConfigAsMap());
            if (cfg.get("request_processors") instanceof List<?> l) {
                List<Map<String, Object>> rp = new ArrayList<>((List<Map<String, Object>>) l);
                rp.removeIf(p -> p.containsKey(FIELD_REPLACEMENT_PROCESSOR_TYPE));
                cfg.put("request_processors", rp);
            }
            putSearchPipeline(name, cfg, client, channel, onSuccess);
        }, e -> onSuccess.run()));
    }

    private void putSearchPipeline(String name, Map<String, Object> cfg, NodeClient client, RestChannel channel, Runnable onSuccess) {
        try {
            var builder = org.opensearch.core.xcontent.XContentBuilder.builder(XContentType.JSON.xContent());
            builder.map(cfg);
            var bytes = org.opensearch.core.common.bytes.BytesReference.bytes(builder);
            client.admin()
                .cluster()
                .execute(
                    PutSearchPipelineAction.INSTANCE,
                    new PutSearchPipelineRequest(name, bytes, XContentType.JSON),
                    ActionListener.wrap(r -> {
                        if (r.isAcknowledged()) onSuccess.run();
                        else sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Not acknowledged");
                    }, e -> sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Search pipeline update failed: " + e.getMessage()))
                );
        } catch (Exception e) {
            sendError(channel, RestStatus.INTERNAL_SERVER_ERROR, "Serialize failed: " + e.getMessage());
        }
    }

    // ========================= Error/Response helpers =========================

    private void sendError(RestChannel ch, RestStatus st, String msg) {
        try {
            ch.sendResponse(
                new BytesRestResponse(
                    st,
                    "application/json",
                    String.format(Locale.ROOT, "{\"error\":{\"reason\":\"%s\"},\"status\":%d}", msg, st.getStatus())
                )
            );
        } catch (Exception e) {
            log.error("Send error failed", e);
        }
    }

    private void sendResponse(RestChannel ch, RestStatus st, String body) {
        try {
            ch.sendResponse(new BytesRestResponse(st, "application/json", body));
        } catch (Exception e) {
            log.error("Send response failed", e);
        }
    }
}
