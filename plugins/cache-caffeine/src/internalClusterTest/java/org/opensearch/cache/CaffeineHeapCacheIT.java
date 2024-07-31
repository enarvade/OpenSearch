/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cache;

import org.opensearch.action.admin.cluster.node.info.NodeInfo;
import org.opensearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.opensearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.opensearch.action.admin.cluster.node.info.PluginsAndModules;
import org.opensearch.action.admin.cluster.node.stats.NodesStatsRequest;
import org.opensearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.opensearch.action.admin.indices.cache.clear.ClearIndicesCacheRequest;
import org.opensearch.action.admin.indices.cache.clear.ClearIndicesCacheResponse;
import org.opensearch.action.admin.indices.forcemerge.ForceMergeResponse;
import org.opensearch.action.admin.indices.stats.CommonStatsFlags;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.SearchType;
import org.opensearch.cache.store.CaffeineHeapCache;
import org.opensearch.client.Client;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.cache.CacheType;
import org.opensearch.common.cache.service.NodeCacheStats;
import org.opensearch.common.cache.settings.CacheSettings;
import org.opensearch.common.cache.stats.ImmutableCacheStats;
import org.opensearch.common.cache.stats.ImmutableCacheStatsHolder;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.FeatureFlags;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.cache.request.RequestCacheStats;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.indices.IndicesRequestCache;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.PluginInfo;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.hamcrest.OpenSearchAssertions;
import org.junit.Assert;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.opensearch.indices.IndicesService.INDICES_CACHE_CLEAN_INTERVAL_SETTING;
import static org.opensearch.search.aggregations.AggregationBuilders.dateHistogram;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertNoFailures;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Based on EhcacheDiskIT.
 */
@OpenSearchIntegTestCase.ClusterScope(numDataNodes = 0, scope = OpenSearchIntegTestCase.Scope.TEST)
public class CaffeineHeapCacheIT extends OpenSearchIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(CaffeineCachePlugin.class);
    }

    @Override
    protected Settings featureFlagSettings() {
        return Settings.builder().put(super.featureFlagSettings()).put(FeatureFlags.PLUGGABLE_CACHE, "true").build();
    }

    private Settings defaultSettings() {
        try (NodeEnvironment env = newNodeEnvironment(Settings.EMPTY)) {
            return Settings.builder()
                .put(
                    CacheSettings.getConcreteStoreNameSettingForCacheType(CacheType.INDICES_REQUEST_CACHE).getKey(),
                    CaffeineHeapCache.CaffeineHeapCacheFactory.NAME
                )
                .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void testPluginsAreInstalled() {
        internalCluster().startNode(Settings.builder().put(defaultSettings()).build());
        NodesInfoRequest nodesInfoRequest = new NodesInfoRequest();
        nodesInfoRequest.addMetric(NodesInfoRequest.Metric.PLUGINS.metricName());
        NodesInfoResponse nodesInfoResponse = OpenSearchIntegTestCase.client().admin().cluster().nodesInfo(nodesInfoRequest).actionGet();
        List<PluginInfo> pluginInfos = nodesInfoResponse.getNodes()
            .stream()
            .flatMap(
                (Function<NodeInfo, Stream<PluginInfo>>) nodeInfo -> nodeInfo.getInfo(PluginsAndModules.class).getPluginInfos().stream()
            )
            .collect(Collectors.toList());
        Assert.assertTrue(
            pluginInfos.stream().anyMatch(pluginInfo -> pluginInfo.getName().equals("org.opensearch.cache.CaffeineCachePlugin"))
        );
    }

    public void testSanityChecksWithIndicesRequestCache() throws InterruptedException, IOException {
        internalCluster().startNode(Settings.builder().put(defaultSettings()).build());
        Client client = client();
        assertAcked(
            client.admin()
                .indices()
                .prepareCreate("index")
                .setMapping("f", "type=date")
                .setSettings(
                    Settings.builder()
                        .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                        .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                        .put(IndicesRequestCache.INDEX_CACHE_REQUEST_ENABLED_SETTING.getKey(), true)
                        .build()
                )
                .get()
        );
        indexRandom(
            true,
            client.prepareIndex("index").setSource("f", "2014-03-10T00:00:00.000Z"),
            client.prepareIndex("index").setSource("f", "2014-05-13T00:00:00.000Z")
        );
        ensureSearchable("index");

        // This is not a random example: serialization with time zones writes shared strings
        // which used to not work well with the query cache because of the handles stream output
        // see #9500
        final SearchResponse r1 = client.prepareSearch("index")
            .setSize(0)
            .setSearchType(SearchType.QUERY_THEN_FETCH)
            .addAggregation(
                dateHistogram("histo").field("f")
                    .timeZone(ZoneId.of("+01:00"))
                    .minDocCount(0)
                    .dateHistogramInterval(DateHistogramInterval.MONTH)
            )
            .get();
        assertSearchResponse(r1);

        // New CacheStats API
        ImmutableCacheStatsHolder indicesStats = getNodeCacheStatsResult(client, List.of(IndicesRequestCache.INDEX_DIMENSION_NAME));
        List<String> indexDimensions = List.of("index");
        ImmutableCacheStats expectedStats = new ImmutableCacheStats(0, 1, 0, 0, 1);
        checkCacheStatsAPIResponse(indicesStats, indexDimensions, expectedStats, false, true);

        // The cache is actually used
        assertThat(
            client.admin().indices().prepareStats("index").setRequestCache(true).get().getTotal().getRequestCache().getMemorySizeInBytes(),
            greaterThan(0L)
        );
    }

    public void testInvalidationWithIndicesRequestCache() throws Exception {
        internalCluster().startNode(
            Settings.builder().put(defaultSettings()).put(INDICES_CACHE_CLEAN_INTERVAL_SETTING.getKey(), new TimeValue(1)).build()
        );
        Client client = client();
        assertAcked(
            client.admin()
                .indices()
                .prepareCreate("index")
                .setMapping("k", "type=keyword")
                .setSettings(
                    Settings.builder()
                        .put(IndicesRequestCache.INDEX_CACHE_REQUEST_ENABLED_SETTING.getKey(), true)
                        .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                        .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                        .put("index.refresh_interval", -1)
                )
                .get()
        );
        int numberOfIndexedItems = randomIntBetween(5, 10);
        for (int iterator = 0; iterator < numberOfIndexedItems; iterator++) {
            indexRandom(true, client.prepareIndex("index").setSource("k" + iterator, "hello" + iterator));
        }
        ensureSearchable("index");
        refreshAndWaitForReplication();
        // Force merge the index to ensure there can be no background merges during the subsequent searches that would invalidate the cache
        ForceMergeResponse forceMergeResponse = client.admin().indices().prepareForceMerge("index").setFlush(true).get();
        OpenSearchAssertions.assertAllSuccessful(forceMergeResponse);
        long perQuerySizeInCacheInBytes = -1;
        for (int iterator = 0; iterator < numberOfIndexedItems; iterator++) {
            SearchResponse resp = client.prepareSearch("index")
                .setRequestCache(true)
                .setQuery(QueryBuilders.termQuery("k" + iterator, "hello" + iterator))
                .get();
            if (perQuerySizeInCacheInBytes == -1) {
                RequestCacheStats requestCacheStats = getRequestCacheStats(client, "index");
                perQuerySizeInCacheInBytes = requestCacheStats.getMemorySizeInBytes();
            }
            assertSearchResponse(resp);
        }
        RequestCacheStats requestCacheStats = getRequestCacheStats(client, "index");
        assertEquals(numberOfIndexedItems, requestCacheStats.getMissCount());
        assertEquals(0, requestCacheStats.getHitCount());
        assertEquals(0, requestCacheStats.getEvictions());
        assertEquals(perQuerySizeInCacheInBytes * numberOfIndexedItems, requestCacheStats.getMemorySizeInBytes());

        // New CacheStats API
        ImmutableCacheStatsHolder indicesStats = getNodeCacheStatsResult(client, List.of(IndicesRequestCache.INDEX_DIMENSION_NAME));
        List<String> indexDimensions = List.of("index");
        ImmutableCacheStats expectedStats = new ImmutableCacheStats(0, numberOfIndexedItems, 0, perQuerySizeInCacheInBytes * numberOfIndexedItems, numberOfIndexedItems);
        checkCacheStatsAPIResponse(indicesStats, indexDimensions, expectedStats, true, true);

        for (int iterator = 0; iterator < numberOfIndexedItems; iterator++) {
            SearchResponse resp = client.prepareSearch("index")
                .setRequestCache(true)
                .setQuery(QueryBuilders.termQuery("k" + iterator, "hello" + iterator))
                .get();
            assertSearchResponse(resp);
        }
        requestCacheStats = getRequestCacheStats(client, "index");
        assertEquals(numberOfIndexedItems, requestCacheStats.getHitCount());
        assertEquals(numberOfIndexedItems, requestCacheStats.getMissCount());
        assertEquals(perQuerySizeInCacheInBytes * numberOfIndexedItems, requestCacheStats.getMemorySizeInBytes());
        assertEquals(0, requestCacheStats.getEvictions());

        // New CacheStats API
        indicesStats = getNodeCacheStatsResult(client, List.of(IndicesRequestCache.INDEX_DIMENSION_NAME));
        expectedStats = new ImmutableCacheStats(numberOfIndexedItems, numberOfIndexedItems, 0, perQuerySizeInCacheInBytes * numberOfIndexedItems, numberOfIndexedItems);
        checkCacheStatsAPIResponse(indicesStats, indexDimensions, expectedStats, true, true);

        // Explicit refresh would invalidate cache entries.
        refreshAndWaitForReplication();
        assertBusy(() -> {
            // Explicit refresh should clear up cache entries
            assertTrue(getRequestCacheStats(client, "index").getMemorySizeInBytes() == 0);
        }, 1, TimeUnit.SECONDS);
        requestCacheStats = getRequestCacheStats(client, "index");
        assertEquals(0, requestCacheStats.getMemorySizeInBytes());
        // Hits and misses stats shouldn't get cleared up.
        assertEquals(numberOfIndexedItems, requestCacheStats.getHitCount());
        assertEquals(numberOfIndexedItems, requestCacheStats.getMissCount());

        // New CacheStats API
        indicesStats = getNodeCacheStatsResult(client, List.of(IndicesRequestCache.INDEX_DIMENSION_NAME));
        expectedStats = new ImmutableCacheStats(numberOfIndexedItems, numberOfIndexedItems, 0, 0, 0);
        checkCacheStatsAPIResponse(indicesStats, indexDimensions, expectedStats, true, true);
    }

    public void testExplicitCacheClearWithIndicesRequestCache() throws Exception {
        internalCluster().startNode(
            Settings.builder().put(defaultSettings()).put(INDICES_CACHE_CLEAN_INTERVAL_SETTING.getKey(), new TimeValue(1)).build()
        );
        Client client = client();
        assertAcked(
            client.admin()
                .indices()
                .prepareCreate("index")
                .setMapping("k", "type=keyword")
                .setSettings(
                    Settings.builder()
                        .put(IndicesRequestCache.INDEX_CACHE_REQUEST_ENABLED_SETTING.getKey(), true)
                        .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                        .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                        .put("index.refresh_interval", -1)
                )
                .get()
        );
        int numberOfIndexedItems = randomIntBetween(5, 10);
        for (int iterator = 0; iterator < numberOfIndexedItems; iterator++) {
            indexRandom(true, client.prepareIndex("index").setSource("k" + iterator, "hello" + iterator));
        }
        ensureSearchable("index");
        refreshAndWaitForReplication();
        // Force merge the index to ensure there can be no background merges during the subsequent searches that would invalidate the cache
        ForceMergeResponse forceMergeResponse = client.admin().indices().prepareForceMerge("index").setFlush(true).get();
        OpenSearchAssertions.assertAllSuccessful(forceMergeResponse);

        long perQuerySizeInCacheInBytes = -1;
        for (int iterator = 0; iterator < numberOfIndexedItems; iterator++) {
            SearchResponse resp = client.prepareSearch("index")
                .setRequestCache(true)
                .setQuery(QueryBuilders.termQuery("k" + iterator, "hello" + iterator))
                .get();
            if (perQuerySizeInCacheInBytes == -1) {
                RequestCacheStats requestCacheStats = getRequestCacheStats(client, "index");
                perQuerySizeInCacheInBytes = requestCacheStats.getMemorySizeInBytes();
            }
            assertSearchResponse(resp);
        }
        RequestCacheStats requestCacheStats = getRequestCacheStats(client, "index");
        assertEquals(numberOfIndexedItems, requestCacheStats.getMissCount());
        assertEquals(0, requestCacheStats.getHitCount());
        assertEquals(0, requestCacheStats.getEvictions());
        assertEquals(perQuerySizeInCacheInBytes * numberOfIndexedItems, requestCacheStats.getMemorySizeInBytes());

        // New CacheStats API
        ImmutableCacheStatsHolder indicesStats = getNodeCacheStatsResult(client, List.of(IndicesRequestCache.INDEX_DIMENSION_NAME));
        List<String> indexDimensions = List.of("index");
        ImmutableCacheStats expectedStats = new ImmutableCacheStats(0, numberOfIndexedItems, 0, perQuerySizeInCacheInBytes * numberOfIndexedItems, numberOfIndexedItems);
        checkCacheStatsAPIResponse(indicesStats, indexDimensions, expectedStats, true, true);

        // Explicit clear the cache.
        ClearIndicesCacheRequest request = new ClearIndicesCacheRequest("index");
        ClearIndicesCacheResponse response = client.admin().indices().clearCache(request).get();
        assertNoFailures(response);

        assertBusy(() -> {
            // All entries should get cleared up.
            assertTrue(getRequestCacheStats(client, "index").getMemorySizeInBytes() == 0);
        }, 1, TimeUnit.SECONDS);

        // New CacheStats API
        indicesStats = getNodeCacheStatsResult(client, List.of(IndicesRequestCache.INDEX_DIMENSION_NAME));
        expectedStats = new ImmutableCacheStats(0, numberOfIndexedItems, 0, 0, 0);
        checkCacheStatsAPIResponse(indicesStats, indexDimensions, expectedStats, true, true);
    }

    private static void checkCacheStatsAPIResponse(
        ImmutableCacheStatsHolder statsHolder,
        List<String> dimensionValues,
        ImmutableCacheStats expectedStats,
        boolean checkMemorySize,
        boolean checkEntries
    ) {
        ImmutableCacheStats aggregatedStatsResponse = statsHolder.getStatsForDimensionValues(dimensionValues);
        assertNotNull(aggregatedStatsResponse);
        assertEquals(expectedStats.getHits(), (int) aggregatedStatsResponse.getHits());
        assertEquals(expectedStats.getMisses(), (int) aggregatedStatsResponse.getMisses());
        assertEquals(expectedStats.getEvictions(), (int) aggregatedStatsResponse.getEvictions());
        if (checkMemorySize) {
            assertEquals(expectedStats.getSizeInBytes(), (int) aggregatedStatsResponse.getSizeInBytes());
        }
        if (checkEntries) {
            assertEquals(expectedStats.getItems(), (int) aggregatedStatsResponse.getItems());
        }
    }

    private static ImmutableCacheStatsHolder getNodeCacheStatsResult(Client client, List<String> aggregationLevels) throws IOException {
        CommonStatsFlags statsFlags = new CommonStatsFlags();
        statsFlags.includeAllCacheTypes();
        String[] flagsLevels;
        if (aggregationLevels == null) {
            flagsLevels = null;
        } else {
            flagsLevels = aggregationLevels.toArray(new String[0]);
        }
        statsFlags.setLevels(flagsLevels);

        NodesStatsResponse nodeStatsResponse = client.admin()
            .cluster()
            .prepareNodesStats("data:true")
            .addMetric(NodesStatsRequest.Metric.CACHE_STATS.metricName())
            .setIndices(statsFlags)
            .get();
        // Can always get the first data node as there's only one in this test suite
        assertEquals(1, nodeStatsResponse.getNodes().size());
        NodeCacheStats ncs = nodeStatsResponse.getNodes().get(0).getNodeCacheStats();
        return ncs.getStatsByCache(CacheType.INDICES_REQUEST_CACHE);
    }

    private RequestCacheStats getRequestCacheStats(Client client, String indexName) {
        return client.admin().indices().prepareStats(indexName).setRequestCache(true).get().getTotal().getRequestCache();
    }
}
