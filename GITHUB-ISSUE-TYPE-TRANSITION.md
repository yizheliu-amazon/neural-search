# GitHub Issue Draft

**Repo:** https://github.com/opensearch-project/OpenSearch/issues
**Title:** [FEATURE] Support field type transition from text/keyword to semantic field type

---

## Is your feature request related to a problem?

Currently, OpenSearch does not allow changing a field's type after index creation. If a customer has an existing index with `text` or `keyword` fields and later wants to enable semantic search (using the `semantic` field type from neural-search plugin), they must recreate the index from scratch and reindex all data. This is disruptive and impractical for large production indices.

Similarly, once a field is set to `type: semantic`, there is no way to revert it back to a plain `text` or `keyword` field without recreating the index.

## Describe the solution you'd like

### Enabling semantic: Allow type transition from text/keyword/... to semantic

Support changing an existing field's type from `text`, `keyword`, `match_only_text`, `wildcard`, `token_count`, or `binary` to `semantic` via PutMapping:

```json
PUT /my-index/_mapping
{
  "properties": {
    "title": { "type": "semantic" }
  }
}
```

This is safe because `semantic` is a specialization of these text-based field types — it uses the same Lucene inverted index format under the hood (via `raw_field_type`), and simply adds a companion field for embeddings. Existing indexed data remains fully valid and readable.

**Proposed Core change — Plugin-Extensible Type Transition Registry:**

Add a new extension point to `MapperPlugin` that allows plugins to declare permitted field type transitions. Core checks this registry before throwing the "mapper cannot be changed" error.

#### `MapperPlugin.java` — New SPI method

```java
/**
 * Returns allowed field type transitions declared by this plugin.
 * Key: source field type, Value: set of target types allowed from that source.
 */
default Map<String, Set<String>> getAllowedFieldTypeTransitions() {
    return Collections.emptyMap();
}
```

#### `MapperRegistry.java` — Store and query transitions

```java
private final Map<String, Set<String>> allowedFieldTypeTransitions;

public boolean isTransitionAllowed(String fromType, String toType) {
    Set<String> allowed = allowedFieldTypeTransitions.get(fromType);
    return allowed != null && allowed.contains(toType);
}
```

#### `IndicesModule.java` — Aggregate from plugins at startup

```java
// In constructor:
Map<String, Set<String>> transitions = getAllowedFieldTypeTransitions(mapperPlugins);
this.mapperRegistry = new MapperRegistry(mapperParsers, metadataMapperParsers, fieldFilter, transitions);
ParametrizedFieldMapper.setAllowedTypeTransitions(transitions);

// Aggregation method:
private static Map<String, Set<String>> getAllowedFieldTypeTransitions(List<MapperPlugin> mapperPlugins) {
    Map<String, Set<String>> transitions = new HashMap<>();
    for (MapperPlugin plugin : mapperPlugins) {
        for (Map.Entry<String, Set<String>> entry : plugin.getAllowedFieldTypeTransitions().entrySet()) {
            transitions.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
        }
    }
    return transitions;
}
```

#### `ParametrizedFieldMapper.java` — Check registry + validation hook

```java
// Static registry populated once at node startup
private static volatile Map<String, Set<String>> ALLOWED_TYPE_TRANSITIONS = Collections.emptyMap();

public static void setAllowedTypeTransitions(Map<String, Set<String>> transitions) {
    ALLOWED_TYPE_TRANSITIONS = transitions;
}

static boolean isTypeTransitionAllowed(String fromType, String toType) {
    Set<String> allowed = ALLOWED_TYPE_TRANSITIONS.get(fromType);
    return allowed != null && allowed.contains(toType);
}

/**
 * Hook for subclasses to reject specific transitions based on mapper state.
 * Called after registry allows the transition but before it is accepted.
 */
protected void validateTypeTransition(ParametrizedFieldMapper incoming) {
    // Default: no additional validation.
}

@Override
public ParametrizedFieldMapper merge(Mapper mergeWith) {
    // ... existing instanceof check ...
    String mergeWithContentType = ((FieldMapper) mergeWith).contentType();
    if (Objects.equals(this.getClass(), mergeWith.getClass()) == false) {
        if (isTypeTransitionAllowed(contentType(), mergeWithContentType)) {
            validateTypeTransition((ParametrizedFieldMapper) mergeWith);
            return (ParametrizedFieldMapper) mergeWith;
        }
        throw new IllegalArgumentException(
            "mapper [" + name() + "] cannot be changed from type [" + contentType() + "] to [" + mergeWithContentType + "]"
        );
    }
    // ... rest unchanged ...
}
```

**Neural-search plugin registers the transitions:**
```java
@Override
public Map<String, Set<String>> getAllowedFieldTypeTransitions() {
    Set<String> supportedRawTypes = Set.of("text", "keyword", "match_only_text", "wildcard", "token_count", "binary");
    return Map.of(
        "text", Set.of("semantic"),
        "keyword", Set.of("semantic"),
        "match_only_text", Set.of("semantic"),
        "wildcard", Set.of("semantic"),
        "token_count", Set.of("semantic"),
        "binary", Set.of("semantic"),
        "semantic", supportedRawTypes
    );
}
```

**SemanticFieldMapper validates revert direction:**
```java
@Override
protected void validateTypeTransition(ParametrizedFieldMapper incoming) {
    String targetType = incoming.fieldType().typeName();
    String rawType = this.semanticParameters.getRawFieldType();
    if (!targetType.equals(rawType)) {
        throw new IllegalArgumentException(
            "mapper [" + name() + "] of type [semantic] with raw_field_type [" + rawType
            + "] can only be changed back to type [" + rawType + "], not [" + targetType + "]"
        );
    }
}
```

---

### Disabling semantic: Two options

#### Option 1: Revert semantic back to original field type

```json
PUT /my-index/_mapping
{
  "properties": {
    "title": { "type": "text" }
  }
}
```

The semantic field reverts to its original type. New documents are no longer enriched with embeddings. The companion `_semantic_info` field remains in the mapping (mappings are append-only) but stops being populated.

#### Option 2: Add a `status` parameter to semantic field

```json
// Disable
PUT /my-index/_mapping
{
  "properties": {
    "title": { "type": "semantic", "status": "DISABLED" }
  }
}

// Re-enable
PUT /my-index/_mapping
{
  "properties": {
    "title": { "type": "semantic", "status": "ENABLED" }
  }
}
```

When `status: DISABLED`, the ingest processor skips embedding generation and query rewrite ignores the field. The field stays `type: semantic` but behaves as a plain text field.

#### Comparison

| Dimension | Option 1: Revert type | Option 2: Status parameter |
| --- | --- | --- |
| **Customer intuitiveness** | Moderate — type change feels "heavy" | High — feels like flipping a switch |
| **Re-enable experience** | Another PutMapping type change (text → semantic again) | Simple: set `status: ENABLED` |
| **Requires Core change** | Yes — uses the type transition registry | No — purely neural-search plugin |
| **Implementation complexity** | Higher — Core + plugin changes | Lower — single updateable `Parameter` |
| **Field type in mapping** | Changes back to `text`/`keyword` | Stays `semantic` (with `status: DISABLED`) |
| **Backward compat** | Field reverts to original behavior completely | Field is still `semantic` type — may confuse tools expecting `text` |
| **Orphaned companion field** | `_semantic_info` stays in mapping (can't remove) | Same — `_semantic_info` stays but stops being populated |
| **Works for "enable on existing text field"** | Yes — same mechanism enables and disables | No — still need the type change to go from `text → semantic` initially |

**Note:** Option 2 only covers the disable/re-enable case. The initial "enable semantic on existing text field" (`text → semantic`) still requires the Core type transition registry from Option 1. The two options are not mutually exclusive — Option 2 can complement Option 1 by providing a lighter-weight toggle once the field is already semantic.

---

## POC Verification

A working POC has been implemented and verified end-to-end:
- `text → semantic`: PutMapping succeeds, companion field created, model resolved, new documents get embeddings, neural search works
- `semantic → text`: PutMapping succeeds, mapping reverts, new documents do not get embeddings, BM25 continues working
- `semantic(raw_field_type=text) → keyword`: Correctly rejected ("can only be changed back to type [text], not [keyword]")
- `keyword → semantic`: Correctly allowed
- `semantic(raw_field_type=keyword) → keyword`: Correctly allowed

## Design considerations

- **No `@PublicApi` breakage** — `Mapper.merge(Mapper)` signature is unchanged
- **Generic and reusable** — any plugin can register allowed transitions (not specific to semantic)
- **Narrow scope** — only registered transitions pass; all other type changes still throw
- **Backward compatible** — `getAllowedFieldTypeTransitions()` returns empty map by default
- **Old data not backfilled** — after enabling semantic, only new/updated documents get embeddings. Existing documents need `_update_by_query` for backfill.

## Related components

- OpenSearch Core (server module): `MapperPlugin`, `MapperRegistry`, `IndicesModule`, `ParametrizedFieldMapper`
- neural-search plugin: `NeuralSearch`, `SemanticFieldMapper`
