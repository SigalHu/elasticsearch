/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.action.search;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.OriginalIndices;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.LocalTransportAddress;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.search.internal.AliasFilter;
import org.elasticsearch.search.internal.ShardSearchTransportRequest;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.VersionUtils;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.local.LocalTransport;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class CanMatchPreFilterSearchPhaseTests extends ESTestCase {


    public void testFilterShards() throws InterruptedException {

        final TransportSearchAction.SearchTimeProvider timeProvider = new TransportSearchAction.SearchTimeProvider(0, System.nanoTime(),
            System::nanoTime);

        Map<String, Transport.Connection> lookup = new ConcurrentHashMap<>();
        DiscoveryNode primaryNode = new DiscoveryNode("node_1", LocalTransportAddress.buildUnique(), Version.CURRENT);
        DiscoveryNode replicaNode = new DiscoveryNode("node_2", LocalTransportAddress.buildUnique(), Version.CURRENT);
        lookup.put("node1", new SearchAsyncActionTests.MockConnection(primaryNode));
        lookup.put("node2", new SearchAsyncActionTests.MockConnection(replicaNode));
        final boolean shard1 = randomBoolean();
        final boolean shard2 = randomBoolean();

        SearchTransportService searchTransportService = new SearchTransportService(
            Settings.builder().put("search.remote.connect", false).build(), null) {

            @Override
            public void sendCanMatch(Transport.Connection connection, ShardSearchTransportRequest request, SearchTask task,
                                     ActionListener<CanMatchResponse> listener) {
                new Thread(() -> listener.onResponse(new CanMatchResponse(request.shardId().id() == 0 ? shard1 :
                    shard2))).start();
            }
        };

        AtomicReference<GroupShardsIterator<SearchShardIterator>> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        GroupShardsIterator<SearchShardIterator> shardsIter = SearchAsyncActionTests.getShardsIter("idx",
            new OriginalIndices(new String[]{"idx"}, IndicesOptions.strictExpandOpenAndForbidClosed()),
            2, randomBoolean(), primaryNode, replicaNode);
        CanMatchPreFilterSearchPhase canMatchPhase = new CanMatchPreFilterSearchPhase(logger,
            searchTransportService,
            (clusterAlias, node) -> lookup.get(node),
            Collections.singletonMap("_na_", new AliasFilter(null, Strings.EMPTY_ARRAY)),
            Collections.emptyMap(), EsExecutors.newDirectExecutorService(),
            new SearchRequest(), null, shardsIter, timeProvider, 0, null,
            (iter) -> new SearchPhase("test") {
                    @Override
                    public void run() throws IOException {
                        result.set(iter);
                        latch.countDown();
                    }});

        canMatchPhase.start();
        latch.await();

        if (shard1 && shard2) {
            for (SearchShardIterator i : result.get()) {
                assertFalse(i.skip());
            }
        } else if (shard1 == false &&  shard2 == false) {
            assertFalse(result.get().get(0).skip());
            assertTrue(result.get().get(1).skip());
        } else {
            assertEquals(0, result.get().get(0).shardId().id());
            assertEquals(1, result.get().get(1).shardId().id());
            assertEquals(shard1, !result.get().get(0).skip());
            assertEquals(shard2, !result.get().get(1).skip());
        }
    }

    public void testOldNodesTriggerException() {
        SearchTransportService searchTransportService = new SearchTransportService(
            Settings.builder().put("search.remote.connect", false).build(), null);
        DiscoveryNode node = new DiscoveryNode("node_1", LocalTransportAddress.buildUnique(),
            VersionUtils.getPreviousVersion(Version.V_5_6_0_UNRELEASED));
        SearchAsyncActionTests.MockConnection mockConnection = new SearchAsyncActionTests.MockConnection(node);
        IllegalArgumentException illegalArgumentException = expectThrows(IllegalArgumentException.class,
            () -> searchTransportService.sendCanMatch(mockConnection, null, null, null));
        assertEquals("can_match is not supported on pre 5.6.0 nodes", illegalArgumentException.getMessage());
    }

    public void testFilterWithFailure() throws InterruptedException {
        final TransportSearchAction.SearchTimeProvider timeProvider = new TransportSearchAction.SearchTimeProvider(0, System.nanoTime(),
            System::nanoTime);
        Map<String, Transport.Connection> lookup = new ConcurrentHashMap<>();
        DiscoveryNode primaryNode = new DiscoveryNode("node_1", LocalTransportAddress.buildUnique(), Version.CURRENT);
        DiscoveryNode replicaNode = new DiscoveryNode("node_2", LocalTransportAddress.buildUnique(), Version.CURRENT);
        lookup.put("node1", new SearchAsyncActionTests.MockConnection(primaryNode));
        lookup.put("node2", new SearchAsyncActionTests.MockConnection(replicaNode));
        final boolean shard1 = randomBoolean();
        SearchTransportService searchTransportService = new SearchTransportService(
            Settings.builder().put("search.remote.connect", false).build(), null) {

            @Override
            public void sendCanMatch(Transport.Connection connection, ShardSearchTransportRequest request, SearchTask task,
                                     ActionListener<CanMatchResponse> listener) {
                boolean throwException = request.shardId().id() != 0;
                if (throwException && randomBoolean()) {
                    throw new IllegalArgumentException("boom");
                } else {
                    new Thread(() -> {
                        if (throwException == false) {
                            listener.onResponse(new CanMatchResponse(shard1));
                        } else {
                            listener.onFailure(new NullPointerException());
                        }
                    }).start();
                }
            }
        };

        AtomicReference<GroupShardsIterator<SearchShardIterator>> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        GroupShardsIterator<SearchShardIterator> shardsIter = SearchAsyncActionTests.getShardsIter("idx",
            new OriginalIndices(new String[]{"idx"}, IndicesOptions.strictExpandOpenAndForbidClosed()),
            2, randomBoolean(), primaryNode, replicaNode);
        CanMatchPreFilterSearchPhase canMatchPhase = new CanMatchPreFilterSearchPhase(logger,
            searchTransportService,
            (clusterAlias, node) -> lookup.get(node),
            Collections.singletonMap("_na_", new AliasFilter(null, Strings.EMPTY_ARRAY)),
            Collections.emptyMap(), EsExecutors.newDirectExecutorService(),
            new SearchRequest(), null, shardsIter, timeProvider, 0, null,
            (iter) -> new SearchPhase("test") {
                @Override
                public void run() throws IOException {
                    result.set(iter);
                    latch.countDown();
                }});

        canMatchPhase.start();
        latch.await();

        assertEquals(0, result.get().get(0).shardId().id());
        assertEquals(1, result.get().get(1).shardId().id());
        assertEquals(shard1, !result.get().get(0).skip());
        assertFalse(result.get().get(1).skip()); // never skip the failure
    }
}
