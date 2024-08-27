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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongBiFunction;


public class CaffeineMicrobenchmark extends OpenSearchTestCase {

    private final String dimensionName = "shardId";
    private static final int CACHE_SIZE_IN_BYTES = 5000000;
    private static final int MOCK_WEIGHT = 10;
    private static final int NUM_THREADS = 8;
    private static final int[] ITERATIONS = {100000, 1000000, 10000000};
    private static final int RUNS = 10;

    public void testAllMisses() throws Exception {
        MockRemovalListener<String, String> removalListener = new MockRemovalListener<>();
        ICache<String, String> cache;
        ArrayList<ICacheKey<String>> keys;
        List<ICacheKey<String>> keysForHits;
        List<ICacheKey<String>> keysForMisses;
        Map<ICacheKey<String>, String> keyValueMap;
        long start;
        long end;
        Thread[] threads;
        Phaser phaser;
        CountDownLatch countDownLatch;
        int j;

        for (int x = 1; x <= RUNS ; x++) {
            for (int iterations : ITERATIONS) {
                int num_keys = iterations / 10;
                keys = new ArrayList<>();
                keyValueMap = new HashMap<>();
                for (int i = 0; i < num_keys; i++) {
                    ICacheKey<String> key = getICacheKey(UUID.randomUUID().toString());
                    keyValueMap.put(key, UUID.randomUUID().toString());
                    keys.add(key);
                }
                keysForHits = keys.subList(0, num_keys / 10);
                keysForMisses = keys.subList(num_keys / 10, num_keys);

                // Caffeine
                cache = getCaffeineCache(removalListener, true);

                // Prepopulate the cache
                for (int i = 0; i < keysForHits.size(); i++) {
                    ICacheKey<String> key = keysForHits.get(i);
                    String value = keyValueMap.get(key);
                    cache.put(key, value);
                }

                threads = new Thread[NUM_THREADS];
                phaser = new Phaser(NUM_THREADS + 1);
                countDownLatch = new CountDownLatch(NUM_THREADS);
                j = 0;
                start = System.nanoTime();
                for (int i = 0; i < NUM_THREADS; i++) {
                    List<ICacheKey<String>> finalKeysForMisses = keysForMisses;
                    ICache<String, String> finalCache = cache;
                    CountDownLatch finalCountDownLatch = countDownLatch;
                    Phaser finalPhaser = phaser;
                    threads[j] = new Thread(() -> {
                        finalPhaser.arriveAndAwaitAdvance();
                        reads(finalCache, finalKeysForMisses, iterations);
                        finalCountDownLatch.countDown();
                    });
                    threads[j].start();
                    j++;
                }
                phaser.arriveAndAwaitAdvance(); // Will trigger parallel gets above
                countDownLatch.await(); // Wait for all threads to finish
                end = System.nanoTime();

                // Print caffeine results
                System.out.println(
                    x + ", "
                        + "caffeine, "
                        + iterations + ", "
                        + num_keys + ", "
                        + (end - start) + ", "
                        + cache.stats().getTotalHits() + ", "
                        + cache.stats().getTotalMisses() + ", "
                        + cache.stats().getTotalEvictions()
                );

                // Default
                cache = getDefaultCache(removalListener, true);

                // Prepopulate the cache
                for (int i = 0; i < keysForHits.size(); i++) {
                    ICacheKey<String> key = keysForHits.get(i);
                    String value = keyValueMap.get(key);
                    cache.put(key, value);
                }

                threads = new Thread[NUM_THREADS];
                phaser = new Phaser(NUM_THREADS + 1);
                countDownLatch = new CountDownLatch(NUM_THREADS);
                j = 0;

                start = System.nanoTime();
                for (int i = 0; i < NUM_THREADS; i++) {
                    List<ICacheKey<String>> finalKeysForMisses = keysForMisses;
                    ICache<String, String> finalCache = cache;
                    CountDownLatch finalCountDownLatch = countDownLatch;
                    Phaser finalPhaser = phaser;
                    threads[j] = new Thread(() -> {
                        finalPhaser.arriveAndAwaitAdvance();
                        reads(finalCache, finalKeysForMisses, iterations);
                        finalCountDownLatch.countDown();
                    });
                    threads[j].start();
                    j++;
                }
                phaser.arriveAndAwaitAdvance(); // Will trigger parallel gets above
                countDownLatch.await(); // Wait for all threads to finish
                end = System.nanoTime();

                // Print default results
                System.out.println(
                    x + ", "
                        + "default, "
                        + iterations + ", "
                        + num_keys + ", "
                        + (end - start) + ", "
                        + cache.stats().getTotalHits() + ", "
                        + cache.stats().getTotalMisses() + ", "
                        + cache.stats().getTotalEvictions()
                );
            }
        }
    }

    public void testAllHits() throws Exception {
        MockRemovalListener<String, String> removalListener = new MockRemovalListener<>();
        ICache<String, String> cache;
        ArrayList<ICacheKey<String>> keys;
        List<ICacheKey<String>> keysForHits;
        List<ICacheKey<String>> keysForMisses;
        Map<ICacheKey<String>, String> keyValueMap;
        long start;
        long end;
        Thread[] threads;
        Phaser phaser;
        CountDownLatch countDownLatch;
        int j;

        for (int x = 1; x <= RUNS ; x++) {
            for (int iterations : ITERATIONS) {
                int num_keys = iterations / 10;
                keys = new ArrayList<>();
                keyValueMap = new HashMap<>();
                for (int i = 0; i < num_keys; i++) {
                    ICacheKey<String> key = getICacheKey(UUID.randomUUID().toString());
                    keyValueMap.put(key, UUID.randomUUID().toString());
                    keys.add(key);
                }
                keysForHits = keys.subList(0, num_keys / 10);
                keysForMisses = keys.subList(num_keys / 10, num_keys);

                // Caffeine
                cache = getCaffeineCache(removalListener, true);

                // Prepopulate the cache
                for (int i = 0; i < keysForHits.size(); i++) {
                    ICacheKey<String> key = keysForHits.get(i);
                    String value = keyValueMap.get(key);
                    cache.put(key, value);
                }

                threads = new Thread[NUM_THREADS];
                phaser = new Phaser(NUM_THREADS + 1);
                countDownLatch = new CountDownLatch(NUM_THREADS);
                j = 0;
                start = System.nanoTime();
                for (int i = 0; i < NUM_THREADS; i++) {
                    List<ICacheKey<String>> finalKeysForHits = keysForHits;
                    ICache<String, String> finalCache = cache;
                    CountDownLatch finalCountDownLatch = countDownLatch;
                    Phaser finalPhaser = phaser;
                    threads[j] = new Thread(() -> {
                        finalPhaser.arriveAndAwaitAdvance();
                        reads(finalCache, finalKeysForHits, iterations);
                        finalCountDownLatch.countDown();
                    });
                    threads[j].start();
                    j++;
                }
                phaser.arriveAndAwaitAdvance(); // Will trigger parallel gets above
                countDownLatch.await(); // Wait for all threads to finish
                end = System.nanoTime();

                // Print caffeine results
                System.out.println(
                    x + ", "
                        + "caffeine, "
                        + iterations + ", "
                        + num_keys + ", "
                        + (end - start) + ", "
                        + cache.stats().getTotalHits() + ", "
                        + cache.stats().getTotalMisses() + ", "
                        + cache.stats().getTotalEvictions()
                );

                // Default
                cache = getDefaultCache(removalListener, true);

                // Prepopulate the cache
                for (int i = 0; i < keysForHits.size(); i++) {
                    ICacheKey<String> key = keysForHits.get(i);
                    String value = keyValueMap.get(key);
                    cache.put(key, value);
                }

                threads = new Thread[NUM_THREADS];
                phaser = new Phaser(NUM_THREADS + 1);
                countDownLatch = new CountDownLatch(NUM_THREADS);
                j = 0;

                start = System.nanoTime();
                for (int i = 0; i < NUM_THREADS; i++) {
                    List<ICacheKey<String>> finalKeysForHits = keysForHits;
                    ICache<String, String> finalCache = cache;
                    CountDownLatch finalCountDownLatch = countDownLatch;
                    Phaser finalPhaser = phaser;
                    threads[j] = new Thread(() -> {
                        finalPhaser.arriveAndAwaitAdvance();
                        reads(finalCache, finalKeysForHits, iterations);
                        finalCountDownLatch.countDown();
                    });
                    threads[j].start();
                    j++;
                }
                phaser.arriveAndAwaitAdvance(); // Will trigger parallel gets above
                countDownLatch.await(); // Wait for all threads to finish
                end = System.nanoTime();

                // Print default results
                System.out.println(
                    x + ", "
                        + "default, "
                        + iterations + ", "
                        + num_keys + ", "
                        + (end - start) + ", "
                        + cache.stats().getTotalHits() + ", "
                        + cache.stats().getTotalMisses() + ", "
                        + cache.stats().getTotalEvictions()
                );
            }
        }
    }

    public void testHalfHits() throws Exception {
        MockRemovalListener<String, String> removalListener = new MockRemovalListener<>();
        ICache<String, String> cache;
        ArrayList<ICacheKey<String>> keys;
        List<ICacheKey<String>> keysForHits;
        List<ICacheKey<String>> keysForMisses;
        Map<ICacheKey<String>, String> keyValueMap;
        long start;
        long end;
        Thread[] threads;
        Phaser phaser;
        CountDownLatch countDownLatch;
        int j;

        for (int x = 1; x <= RUNS ; x++) {
            for (int iterations : ITERATIONS) {
                int num_keys = iterations / 10;
                keys = new ArrayList<>();
                keyValueMap = new HashMap<>();
                for (int i = 0; i < num_keys; i++) {
                    ICacheKey<String> key = getICacheKey(UUID.randomUUID().toString());
                    keyValueMap.put(key, UUID.randomUUID().toString());
                    keys.add(key);
                }
                keysForHits = keys.subList(0, num_keys / 10);
                keysForMisses = keys.subList(num_keys / 10, num_keys);

                // Caffeine
                cache = getCaffeineCache(removalListener, true);

                // Prepopulate the cache
                for (int i = 0; i < keysForHits.size(); i++) {
                    ICacheKey<String> key = keysForHits.get(i);
                    String value = keyValueMap.get(key);
                    cache.put(key, value);
                }

                threads = new Thread[NUM_THREADS];
                phaser = new Phaser(NUM_THREADS + 1);
                countDownLatch = new CountDownLatch(NUM_THREADS);
                j = 0;
                start = System.nanoTime();
                for (int i = 0; i < NUM_THREADS; i++) {
                    List<ICacheKey<String>> finalKeysForHits = keysForHits;
                    List<ICacheKey<String>> finalKeysForMisses= keysForMisses;
                    ICache<String, String> finalCache = cache;
                    CountDownLatch finalCountDownLatch = countDownLatch;
                    Phaser finalPhaser = phaser;
                    threads[j] = new Thread(() -> {
                        finalPhaser.arriveAndAwaitAdvance();
                        reads(finalCache, finalKeysForHits, iterations / 2);
                        reads(finalCache, finalKeysForMisses, iterations / 2);
                        finalCountDownLatch.countDown();
                    });
                    threads[j].start();
                    j++;
                }
                phaser.arriveAndAwaitAdvance(); // Will trigger parallel gets above
                countDownLatch.await(); // Wait for all threads to finish
                end = System.nanoTime();

                // Print caffeine results
                System.out.println(
                    x + ", "
                        + "caffeine, "
                        + iterations + ", "
                        + num_keys + ", "
                        + (end - start) + ", "
                        + cache.stats().getTotalHits() + ", "
                        + cache.stats().getTotalMisses() + ", "
                        + cache.stats().getTotalEvictions()
                );

                // Default
                cache = getDefaultCache(removalListener, true);

                // Prepopulate the cache
                for (int i = 0; i < keysForHits.size(); i++) {
                    ICacheKey<String> key = keysForHits.get(i);
                    String value = keyValueMap.get(key);
                    cache.put(key, value);
                }

                threads = new Thread[NUM_THREADS];
                phaser = new Phaser(NUM_THREADS + 1);
                countDownLatch = new CountDownLatch(NUM_THREADS);
                j = 0;

                start = System.nanoTime();
                for (int i = 0; i < NUM_THREADS; i++) {
                    List<ICacheKey<String>> finalKeysForHits = keysForHits;
                    List<ICacheKey<String>> finalKeysForMisses= keysForMisses;
                    ICache<String, String> finalCache = cache;
                    CountDownLatch finalCountDownLatch = countDownLatch;
                    Phaser finalPhaser = phaser;
                    threads[j] = new Thread(() -> {
                        finalPhaser.arriveAndAwaitAdvance();
                        reads(finalCache, finalKeysForHits, iterations / 2);
                        reads(finalCache, finalKeysForMisses, iterations / 2);
                        finalCountDownLatch.countDown();
                    });
                    threads[j].start();
                    j++;
                }
                phaser.arriveAndAwaitAdvance(); // Will trigger parallel gets above
                countDownLatch.await(); // Wait for all threads to finish
                end = System.nanoTime();

                // Print default results
                System.out.println(
                    x + ", "
                        + "default, "
                        + iterations + ", "
                        + num_keys + ", "
                        + (end - start) + ", "
                        + cache.stats().getTotalHits() + ", "
                        + cache.stats().getTotalMisses() + ", "
                        + cache.stats().getTotalEvictions()
                );
            }
        }
    }

    private void reads(ICache<String, String> cache, List<ICacheKey<String>> keys, Integer iterations) {
        Random rnd = new Random();
        for (int k = 0; k < iterations / NUM_THREADS; k++) {
            int index = rnd.nextInt(keys.size());
            ICacheKey<String> key = keys.get(index);
            cache.get(key);
        }
    }

    private CaffeineHeapCache<String, String> getCaffeineCache(
        MockRemovalListener<String, String> listener,
        boolean pluggableCachesSetting
    ) {
        ICache.Factory caffeineCacheFactory = new CaffeineHeapCache.CaffeineHeapCacheFactory();
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
        return (CaffeineHeapCache<String, String>) caffeineCacheFactory.create(cacheConfig, CacheType.INDICES_REQUEST_CACHE, null);
    }

    private OpenSearchOnHeapCache<String, String> getDefaultCache(
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


