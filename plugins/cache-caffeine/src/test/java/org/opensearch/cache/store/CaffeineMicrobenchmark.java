/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cache.store;

import org.opensearch.common.cache.*;
import org.opensearch.common.cache.store.OpenSearchOnHeapCache;
import org.opensearch.common.cache.store.config.CacheConfig;
import org.opensearch.common.metrics.CounterMetric;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.FeatureFlags;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongBiFunction;


public class CaffeineMicrobenchmark extends OpenSearchTestCase {

    private final String dimensionName = "shardId";
    private static final int CACHE_SIZE_IN_BYTES = 1024 * 101;
    private static final int MOCK_WEIGHT = 1000;
    private static final int NUM_KEYS = 100000;

    public void testCaffeine() throws IOException {
        ToLongBiFunction<ICacheKey<String>, String> weigher = getMockWeigher();
        MockRemovalListener<String, String> removalListener = new MockRemovalListener<>();
        ICache<String, String> cache = new CaffeineHeapCache.Builder<String, String>().setDimensionNames(List.of(dimensionName))
            .setExpireAfterAccess(TimeValue.MAX_VALUE)
            .setMaximumWeightInBytes(CACHE_SIZE_IN_BYTES)
            .setWeigher(weigher)
            .setRemovalListener(removalListener)
            .build();

        Map<String, String> keyValueMap = new HashMap<>();
        for (int i = 0; i < NUM_KEYS; i++) {
            keyValueMap.put(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        }

        long start = System.nanoTime();
        for (Map.Entry<String, String> entry : keyValueMap.entrySet()) {
            ICacheKey<String> iCacheKey = getICacheKey(entry.getKey());
            cache.put(iCacheKey, entry.getValue());
        }
        for (Map.Entry<String, String> entry : keyValueMap.entrySet()) {
            cache.get(getICacheKey(entry.getKey()));
        }
        long end = System.nanoTime();
        System.out.println(end - start);
        System.out.println(cache.stats().getTotalHits());
    }

    public void testDefault() throws IOException {
        MockRemovalListener<String, String> removalListener = new MockRemovalListener<>();
        ICache<String, String> cache = getCache(removalListener, true);

        Map<String, String> keyValueMap = new HashMap<>();
        for (int i = 0; i < NUM_KEYS; i++) {
            keyValueMap.put(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        }

        long start = System.nanoTime();
        for (Map.Entry<String, String> entry : keyValueMap.entrySet()) {
            ICacheKey<String> iCacheKey = getICacheKey(entry.getKey());
            cache.put(iCacheKey, entry.getValue());
        }
        for (Map.Entry<String, String> entry : keyValueMap.entrySet()) {
            cache.get(getICacheKey(entry.getKey()));
        }
        long end = System.nanoTime();
        System.out.println(end - start);
        System.out.println(cache.stats().getTotalHits());
    }

    private OpenSearchOnHeapCache<String, String> getCache(
        MockRemovalListener<String, String> listener,
        boolean pluggableCachesSetting
    ) {
        ICache.Factory onHeapCacheFactory = new OpenSearchOnHeapCache.OpenSearchOnHeapCacheFactory();
        Settings settings = Settings.builder()
            .put(FeatureFlags.PLUGGABLE_CACHE, pluggableCachesSetting)
            .build();

        CacheConfig<String, String> cacheConfig = new CacheConfig.Builder<String, String>().setKeyType(String.class)
            .setValueType(String.class)
            .setWeigher(getMockWeigher())
            .setRemovalListener(listener)
            .setSettings(settings)
            .setDimensionNames(List.of(dimensionName))
            .setMaxSizeInBytes(CACHE_SIZE_IN_BYTES)
            .setExpireAfterAccess(TimeValue.MAX_VALUE)
            .build();
        return (OpenSearchOnHeapCache<String, String>) onHeapCacheFactory.create(cacheConfig, CacheType.INDICES_REQUEST_CACHE, null);
    }

    private ToLongBiFunction<ICacheKey<String>, String> getMockWeigher() {
        return (iCacheKey, value) -> { return MOCK_WEIGHT; };
    }

    private List<String> getMockDimensions() {
        return List.of("0");
    }

    private ICacheKey<String> getICacheKey(String key) {
        return new ICacheKey<>(key, getMockDimensions());
    }

    static class MockRemovalListener<K, V> implements RemovalListener<ICacheKey<K>, V> {
        CounterMetric evictionMetric = new CounterMetric();

        @Override
        public void onRemoval(RemovalNotification<ICacheKey<K>, V> notification) {
            evictionMetric.inc();
        }
    }
}


