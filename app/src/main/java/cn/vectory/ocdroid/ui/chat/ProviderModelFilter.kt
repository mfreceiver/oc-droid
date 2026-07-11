package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.ProviderModel

/**
 * §round-B ④ (scheme G.2): pure filter backing the Composer ModelPickerSheet.
 *
 * Returns the (modelId, model) entries under [provider] that match the
 * free-text [query]. A blank query returns every model (the no-search
 * shape). A non-blank query matches iff ANY of:
 *  - the model id;
 *  - the model name;
 *  - the provider id;
 *  - the provider name
 * contains the query (case-insensitive) — so the user can search by
 * provider name (e.g. "anthropic") OR by model id/name. The previous
 * shell only matched modelId / model.name, which left provider-name
 * searches dead.
 *
 * Top-level pure fn so the rule is unit-tested (ComposerKt is excluded
 * from kover as a @Composable-only file — same extraction pattern as
 * [visiblePickerProviders] / [PickerProviderFilter]).
 */
fun filterProviderModels(
    provider: ConfigProvider,
    query: String,
): List<Pair<String, ProviderModel>> {
    val q = query.trim()
    if (q.isEmpty()) return provider.models.entries.map { (id, m) -> id to m }
    val providerHits = provider.id.contains(q, ignoreCase = true) ||
        provider.name?.contains(q, ignoreCase = true) == true
    return provider.models.entries
        .filter { (modelId, model) ->
            providerHits ||
                modelId.contains(q, ignoreCase = true) ||
                model.name?.contains(q, ignoreCase = true) == true
        }
        .map { (id, m) -> id to m }
}
