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
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.function.ToLongBiFunction;


public class CaffeineMicrobenchmark extends OpenSearchTestCase {

    private final String dimensionName = "shardId";
    private static final int CACHE_SIZE_IN_BYTES = 20971520;
    private static final int MOCK_WEIGHT = 10;
    private static final int NUM_KEYS = 1000000;
    private static final int NUM_THREADS = 10;
    private static final int ITERATIONS = 1000000;

    public void test() throws IOException {
        ToLongBiFunction<ICacheKey<String>, String> weigher = getMockWeigher();
        MockRemovalListener<String, String> removalListener = new MockRemovalListener<>();
        ICache<String, String> cache;
        ArrayList<ICacheKey<String>> keys;
        Map<ICacheKey<String>, String> keyValueMap;
        long caffeine_result;
        long default_result;

        for (int x = 0; x < 10; x++) {
            // Caffeine Cache Run
            cache = new CaffeineHeapCache.Builder<String, String>().setDimensionNames(List.of(dimensionName))
                .setExpireAfterAccess(TimeValue.MAX_VALUE)
                .setMaximumWeightInBytes(CACHE_SIZE_IN_BYTES)
                .setWeigher(weigher)
                .setRemovalListener(removalListener)
                .setStatsTrackingEnabled(true)
                .build();

            keys = new ArrayList<>();
            keyValueMap = new HashMap<>();
            for (int i = 0; i < NUM_KEYS; i++) {
                ICacheKey<String> key = getICacheKey(UUID.randomUUID().toString());
                keyValueMap.put(key, UUID.randomUUID().toString());
                keys.add(key);
            }

            Random rnd = new Random();
            long start = System.nanoTime();
            for (int i = 0; i < NUM_KEYS / 2; i++) {
                int index1 = rnd.nextInt(NUM_KEYS);
                int index2 = rnd.nextInt(NUM_KEYS);
                ICacheKey<String> key1 = keys.get(index1);
                ICacheKey<String> key2 = keys.get(index2);
                cache.put(key1, keyValueMap.get(key1));
                cache.get(key2);
            }
            long end = System.nanoTime();
            caffeine_result = end - start;

            // Default Cache Run
            cache = getCache(removalListener, true);
            rnd = new Random();
            start = System.nanoTime();
            for (int i = 0; i < NUM_KEYS / 2; i++) {
                int index1 = rnd.nextInt(NUM_KEYS);
                int index2 = rnd.nextInt(NUM_KEYS);
                ICacheKey<String> key1 = keys.get(index1);
                ICacheKey<String> key2 = keys.get(index2);
                cache.put(key1, keyValueMap.get(key1));
                cache.get(key2);
            }
            end = System.nanoTime();
            default_result = end - start;
            System.out.println((float) default_result / (float) caffeine_result);
        }
    }

    public void testConcurrent() throws Exception {
        ToLongBiFunction<ICacheKey<String>, String> weigher = getMockWeigher();
        MockRemovalListener<String, String> removalListener = new MockRemovalListener<>();
        ICache<String, String> cache;
        ArrayList<ICacheKey<String>> keys;
        Map<ICacheKey<String>, String> keyValueMap;
        long caffeine_result;
        long default_result;

        keys = new ArrayList<>();
        keyValueMap = new HashMap<>();
        for (int i = 0; i < NUM_KEYS; i++) {
            ICacheKey<String> key = getICacheKey(UUID.randomUUID().toString());
            keyValueMap.put(key, UUID.randomUUID().toString());
            keys.add(key);
        }

        for (int x = 0; x < 10; x++) {
            // Caffeine Cache Run
            cache = new CaffeineHeapCache.Builder<String, String>().setDimensionNames(List.of(dimensionName))
                .setExpireAfterAccess(TimeValue.MAX_VALUE)
                .setMaximumWeightInBytes(CACHE_SIZE_IN_BYTES)
                .setWeigher(weigher)
                .setRemovalListener(removalListener)
                .setStatsTrackingEnabled(true)
                .build();

            Thread[] threads = new Thread[NUM_THREADS];
            Phaser phaser = new Phaser(NUM_THREADS + 1);
            CountDownLatch countDownLatch = new CountDownLatch(NUM_THREADS);

            int j = 0;
            long start = System.nanoTime();
            for (int i = 0; i < NUM_THREADS; i++) {
                ArrayList<ICacheKey<String>> finalKeys = keys;
                Map<ICacheKey<String>, String> finalKeyValueMap = keyValueMap;
                ICache<String, String> finalCache = cache;
                threads[j] = new Thread(() -> {
                    phaser.arriveAndAwaitAdvance();
                    Random rnd = new Random();
                    int index1 = rnd.nextInt(NUM_KEYS);
                    int index2 = rnd.nextInt(NUM_KEYS);
                    ICacheKey<String> key1 = finalKeys.get(index1);
                    ICacheKey<String> key2 = finalKeys.get(index2);
                    finalCache.put(key1, finalKeyValueMap.get(key1));
                    finalCache.get(key2);
                    countDownLatch.countDown();
                });
                threads[j].start();
                j++;
            }
            phaser.arriveAndAwaitAdvance(); // Will trigger parallel puts above.
            countDownLatch.await(); // Wait for all threads to finish
            long end = System.nanoTime();
            caffeine_result = end - start;

            j = 0;
            cache = getCache(removalListener, true);
            start = System.nanoTime();
            for (int i = 0; i < NUM_THREADS; i++) {
                ArrayList<ICacheKey<String>> finalKeys = keys;
                Map<ICacheKey<String>, String> finalKeyValueMap = keyValueMap;
                ICache<String, String> finalCache = cache;
                threads[j] = new Thread(() -> {
                    phaser.arriveAndAwaitAdvance();
                    Random rnd = new Random();
                    int index1 = rnd.nextInt(NUM_KEYS);
                    int index2 = rnd.nextInt(NUM_KEYS);
                    ICacheKey<String> key1 = finalKeys.get(index1);
                    ICacheKey<String> key2 = finalKeys.get(index2);
                    finalCache.put(key1, finalKeyValueMap.get(key1));
                    finalCache.get(key2);
                    countDownLatch.countDown();
                });
                threads[j].start();
                j++;
            }
            phaser.arriveAndAwaitAdvance(); // Will trigger parallel puts above.
            countDownLatch.await(); // Wait for all threads to finish
            end = System.nanoTime();
            default_result = end - start;
            System.out.println((float) default_result / (float) caffeine_result);
        }
    }

    public void testGets() throws Exception {
        ToLongBiFunction<ICacheKey<String>, String> weigher = getMockWeigher();
        MockRemovalListener<String, String> removalListener = new MockRemovalListener<>();
        ICache<String, String> cache;
        ArrayList<ICacheKey<String>> keys;
        Map<ICacheKey<String>, String> keyValueMap;
        long caffeine_result;
        long default_result;

        for (int x = 0; x < 10; x++) {
            // Caffeine Cache Run
            cache = new CaffeineHeapCache.Builder<String, String>().setDimensionNames(List.of(dimensionName))
                .setExpireAfterAccess(TimeValue.MAX_VALUE)
                .setMaximumWeightInBytes(CACHE_SIZE_IN_BYTES)
                .setWeigher(weigher)
                .setRemovalListener(removalListener)
                .setStatsTrackingEnabled(true)
                .build();

            ICacheKey<String> key = getICacheKey(UUID.randomUUID().toString());
            cache.put(key, UUID.randomUUID().toString());

            long start = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                cache.get(key);
            }
            long end = System.nanoTime();
            caffeine_result = end - start;

            // Default Cache Run
            cache = getCache(removalListener, true);
            cache.put(key, UUID.randomUUID().toString());
            start = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                cache.get(key);
            }
            end = System.nanoTime();
            default_result = end - start;
            System.out.println((float) default_result / (float) caffeine_result);
        }
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
            .setStatsTrackingEnabled(true)
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


