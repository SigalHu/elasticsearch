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

package org.elasticsearch.transport.netty3;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Booleans;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.network.NetworkService.TcpSettings;
import org.elasticsearch.common.network.NetworkUtils;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.ConnectTransportException;
import org.elasticsearch.transport.ConnectionProfile;
import org.elasticsearch.transport.TcpTransport;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportServiceAdapter;
import org.elasticsearch.transport.TransportSettings;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.AdaptiveReceiveBufferSizePredictorFactory;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.FixedReceiveBufferSizePredictorFactory;
import org.jboss.netty.channel.ReceiveBufferSizePredictorFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.channel.socket.oio.OioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.oio.OioServerSocketChannelFactory;
import org.jboss.netty.util.HashedWheelTimer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

import static org.elasticsearch.common.settings.Setting.byteSizeSetting;
import static org.elasticsearch.common.settings.Setting.intSetting;
import static org.elasticsearch.common.util.concurrent.ConcurrentCollections.newConcurrentMap;
import static org.elasticsearch.common.util.concurrent.EsExecutors.daemonThreadFactory;

/**
 * There are 4 types of connections per node, low/med/high/ping. Low if for batch oriented APIs (like recovery or
 * batch) with high payload that will cause regular request. (like search or single index) to take
 * longer. Med is for the typical search / single doc index. And High for things like cluster state. Ping is reserved for
 * sending out ping requests to other nodes.
 */
public class Netty3Transport extends TcpTransport<Channel> {

    static {
        Netty3Utils.setup();
    }

    public static final Setting<Integer> WORKER_COUNT =
        new Setting<>("transport.netty.worker_count",
            (s) -> Integer.toString(EsExecutors.boundedNumberOfProcessors(s) * 2),
            (s) -> Setting.parseInt(s, 1, "transport.netty.worker_count"), Property.NodeScope, Property.Shared);

    public static final Setting<ByteSizeValue> NETTY_MAX_CUMULATION_BUFFER_CAPACITY =
        Setting.byteSizeSetting(
                "transport.netty.max_cumulation_buffer_capacity",
                new ByteSizeValue(-1),
                Property.NodeScope,
                Property.Shared);
    public static final Setting<Integer> NETTY_MAX_COMPOSITE_BUFFER_COMPONENTS =
        Setting.intSetting("transport.netty.max_composite_buffer_components", -1, -1, Property.NodeScope, Property.Shared);

    // See AdaptiveReceiveBufferSizePredictor#DEFAULT_XXX for default values in netty..., we can use higher ones for us, even fixed one
    public static final Setting<ByteSizeValue> NETTY_RECEIVE_PREDICTOR_SIZE = Setting.byteSizeSetting(
            "transport.netty.receive_predictor_size",
            settings -> {
                long defaultReceiverPredictor = 512 * 1024;
                if (JvmInfo.jvmInfo().getMem().getDirectMemoryMax().getBytes() > 0) {
                    // we can guess a better default...
                    long l = (long) ((0.3 * JvmInfo.jvmInfo().getMem().getDirectMemoryMax().getBytes()) / WORKER_COUNT.get(settings));
                    defaultReceiverPredictor = Math.min(defaultReceiverPredictor, Math.max(l, 64 * 1024));
                }
                return new ByteSizeValue(defaultReceiverPredictor).toString();
            }, Property.NodeScope,
            Property.Shared);
    public static final Setting<ByteSizeValue> NETTY_RECEIVE_PREDICTOR_MIN =
        byteSizeSetting("transport.netty.receive_predictor_min", NETTY_RECEIVE_PREDICTOR_SIZE, Property.NodeScope, Property.Shared);
    public static final Setting<ByteSizeValue> NETTY_RECEIVE_PREDICTOR_MAX =
        byteSizeSetting("transport.netty.receive_predictor_max", NETTY_RECEIVE_PREDICTOR_SIZE, Property.NodeScope, Property.Shared);
    public static final Setting<Integer> NETTY_BOSS_COUNT =
        intSetting("transport.netty.boss_count", 1, 1, Property.NodeScope, Property.Shared);


    protected final ByteSizeValue maxCumulationBufferCapacity;
    protected final int maxCompositeBufferComponents;
    protected final ReceiveBufferSizePredictorFactory receiveBufferSizePredictorFactory;
    protected final int workerCount;
    protected final ByteSizeValue receivePredictorMin;
    protected final ByteSizeValue receivePredictorMax;
    // package private for testing
    volatile Netty3OpenChannelsHandler serverOpenChannels;
    protected volatile ClientBootstrap clientBootstrap;
    protected final Map<String, ServerBootstrap> serverBootstraps = newConcurrentMap();

    public Netty3Transport(Settings settings, ThreadPool threadPool, NetworkService networkService, BigArrays bigArrays,
                           NamedWriteableRegistry namedWriteableRegistry, CircuitBreakerService circuitBreakerService) {
        super("netty3", settings, threadPool, bigArrays, circuitBreakerService, namedWriteableRegistry, networkService);
        deprecationLogger.deprecated(deprecationMessage());
        this.workerCount = WORKER_COUNT.get(settings);
        this.maxCumulationBufferCapacity = NETTY_MAX_CUMULATION_BUFFER_CAPACITY.get(settings);
        this.maxCompositeBufferComponents = NETTY_MAX_COMPOSITE_BUFFER_COMPONENTS.get(settings);

        // See AdaptiveReceiveBufferSizePredictor#DEFAULT_XXX for default values in netty..., we can use higher ones for us, even fixed one
        this.receivePredictorMin = NETTY_RECEIVE_PREDICTOR_MIN.get(settings);
        this.receivePredictorMax = NETTY_RECEIVE_PREDICTOR_MAX.get(settings);
        if (receivePredictorMax.getBytes() == receivePredictorMin.getBytes()) {
            receiveBufferSizePredictorFactory = new FixedReceiveBufferSizePredictorFactory((int) receivePredictorMax.getBytes());
        } else {
            receiveBufferSizePredictorFactory = new AdaptiveReceiveBufferSizePredictorFactory((int) receivePredictorMin.getBytes(),
                (int) receivePredictorMin.getBytes(), (int) receivePredictorMax.getBytes());
        }
    }

    protected String deprecationMessage() {
        return "transport type [netty3] is deprecated";
    }

    TransportServiceAdapter transportServiceAdapter() {
        return transportServiceAdapter;
    }

    @Override
    protected void doStart() {
        boolean success = false;
        try {
            clientBootstrap = createClientBootstrap();
            if (NetworkService.NETWORK_SERVER.get(settings)) {
                final Netty3OpenChannelsHandler openChannels = new Netty3OpenChannelsHandler(logger);
                this.serverOpenChannels = openChannels;
                // loop through all profiles and start them up, special handling for default one
                for (Map.Entry<String, Settings> entry : buildProfileSettings().entrySet()) {
                    // merge fallback settings with default settings with profile settings so we have complete settings with default values
                    final Settings settings = Settings.builder()
                        .put(createFallbackSettings())
                        .put(entry.getValue()).build();
                    createServerBootstrap(entry.getKey(), settings);
                    bindServer(entry.getKey(), settings);
                }
            }
            super.doStart();
            success = true;
        } finally {
            if (success == false) {
                doStop();
            }
        }
    }

    private ClientBootstrap createClientBootstrap() {
        // this doPrivileged is for SelectorUtil.java that tries to set "sun.nio.ch.bugLevel"
        if (blockingClient) {
            clientBootstrap = new ClientBootstrap(new OioClientSocketChannelFactory(
                Executors.newCachedThreadPool(daemonThreadFactory(settings, TRANSPORT_CLIENT_WORKER_THREAD_NAME_PREFIX))));
        } else {
            int bossCount = NETTY_BOSS_COUNT.get(settings);
            clientBootstrap = new ClientBootstrap(
                new NioClientSocketChannelFactory(
                    Executors.newCachedThreadPool(daemonThreadFactory(settings, TRANSPORT_CLIENT_BOSS_THREAD_NAME_PREFIX)),
                    bossCount,
                    new NioWorkerPool(Executors.newCachedThreadPool(
                        daemonThreadFactory(settings, TRANSPORT_CLIENT_WORKER_THREAD_NAME_PREFIX)), workerCount),
                    new HashedWheelTimer(daemonThreadFactory(settings, "transport_client_timer"))));
        }
        clientBootstrap.setPipelineFactory(configureClientChannelPipelineFactory());
        clientBootstrap.setOption("connectTimeoutMillis", defaultConnectionProfile.getConnectTimeout().millis());

        boolean tcpNoDelay = TCP_NO_DELAY.get(settings);
        clientBootstrap.setOption("tcpNoDelay", tcpNoDelay);

        boolean tcpKeepAlive = TCP_KEEP_ALIVE.get(settings);
        clientBootstrap.setOption("keepAlive", tcpKeepAlive);

        ByteSizeValue tcpSendBufferSize = TCP_SEND_BUFFER_SIZE.get(settings);
        if (tcpSendBufferSize.getBytes() > 0) {
            clientBootstrap.setOption("sendBufferSize", tcpSendBufferSize.getBytes());
        }

        ByteSizeValue tcpReceiveBufferSize = TCP_RECEIVE_BUFFER_SIZE.get(settings);
        if (tcpReceiveBufferSize.getBytes() > 0) {
            clientBootstrap.setOption("receiveBufferSize", tcpReceiveBufferSize.getBytes());
        }

        clientBootstrap.setOption("receiveBufferSizePredictorFactory", receiveBufferSizePredictorFactory);

        boolean reuseAddress = TCP_REUSE_ADDRESS.get(settings);
        clientBootstrap.setOption("reuseAddress", reuseAddress);

        return clientBootstrap;
    }

    private Settings createFallbackSettings() {
        Settings.Builder fallbackSettingsBuilder = Settings.builder();

        List<String> fallbackBindHost = TransportSettings.BIND_HOST.get(settings);
        if (fallbackBindHost.isEmpty() == false) {
            fallbackSettingsBuilder.putArray("bind_host", fallbackBindHost);
        }

        List<String> fallbackPublishHost = TransportSettings.PUBLISH_HOST.get(settings);
        if (fallbackPublishHost.isEmpty() == false) {
            fallbackSettingsBuilder.putArray("publish_host", fallbackPublishHost);
        }

        boolean fallbackTcpNoDelay = settings.getAsBoolean("transport.netty.tcp_no_delay", TcpSettings.TCP_NO_DELAY.get(settings));
        fallbackSettingsBuilder.put("tcp_no_delay", fallbackTcpNoDelay);

        boolean fallbackTcpKeepAlive = settings.getAsBoolean("transport.netty.tcp_keep_alive", TcpSettings.TCP_KEEP_ALIVE.get(settings));
        fallbackSettingsBuilder.put("tcp_keep_alive", fallbackTcpKeepAlive);

        boolean fallbackReuseAddress = settings.getAsBoolean("transport.netty.reuse_address", TcpSettings.TCP_REUSE_ADDRESS.get(settings));
        fallbackSettingsBuilder.put("reuse_address", fallbackReuseAddress);

        ByteSizeValue fallbackTcpSendBufferSize = settings.getAsBytesSize("transport.netty.tcp_send_buffer_size",
            TCP_SEND_BUFFER_SIZE.get(settings));
        if (fallbackTcpSendBufferSize.getBytes() >= 0) {
            fallbackSettingsBuilder.put("tcp_send_buffer_size", fallbackTcpSendBufferSize);
        }

        ByteSizeValue fallbackTcpBufferSize = settings.getAsBytesSize("transport.netty.tcp_receive_buffer_size",
            TCP_RECEIVE_BUFFER_SIZE.get(settings));
        if (fallbackTcpBufferSize.getBytes() >= 0) {
            fallbackSettingsBuilder.put("tcp_receive_buffer_size", fallbackTcpBufferSize);
        }

        return fallbackSettingsBuilder.build();
    }

    private void createServerBootstrap(String name, Settings settings) {
        boolean blockingServer = TCP_BLOCKING_SERVER.get(settings);
        String port = settings.get("port");
        String bindHost = settings.get("bind_host");
        String publishHost = settings.get("publish_host");
        String tcpNoDelay = settings.get("tcp_no_delay");
        String tcpKeepAlive = settings.get("tcp_keep_alive");
        boolean reuseAddress = settings.getAsBoolean("reuse_address", NetworkUtils.defaultReuseAddress());
        ByteSizeValue tcpSendBufferSize = TCP_SEND_BUFFER_SIZE.getDefault(settings);
        ByteSizeValue tcpReceiveBufferSize = TCP_RECEIVE_BUFFER_SIZE.getDefault(settings);

        if (logger.isDebugEnabled()) {
            logger.debug("using profile[{}], worker_count[{}], port[{}], bind_host[{}], publish_host[{}], compress[{}], "
                    + "connect_timeout[{}], connections_per_node[{}/{}/{}/{}/{}], receive_predictor[{}->{}]",
                name, workerCount, port, bindHost, publishHost, compress,
                defaultConnectionProfile.getConnectTimeout(),
                defaultConnectionProfile.getNumConnectionsPerType(TransportRequestOptions.Type.RECOVERY),
                defaultConnectionProfile.getNumConnectionsPerType(TransportRequestOptions.Type.BULK),
                defaultConnectionProfile.getNumConnectionsPerType(TransportRequestOptions.Type.REG),
                defaultConnectionProfile.getNumConnectionsPerType(TransportRequestOptions.Type.STATE),
                defaultConnectionProfile.getNumConnectionsPerType(TransportRequestOptions.Type.PING), receivePredictorMin,
                receivePredictorMax);
        }

        final ThreadFactory bossFactory = daemonThreadFactory(this.settings, TRANSPORT_SERVER_BOSS_THREAD_NAME_PREFIX, name);
        final ThreadFactory workerFactory = daemonThreadFactory(this.settings, TRANSPORT_SERVER_WORKER_THREAD_NAME_PREFIX, name);
        final ServerBootstrap serverBootstrap;
        if (blockingServer) {
            serverBootstrap = new ServerBootstrap(new OioServerSocketChannelFactory(
                Executors.newCachedThreadPool(bossFactory),
                Executors.newCachedThreadPool(workerFactory)
            ));
        } else {
            serverBootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(bossFactory),
                Executors.newCachedThreadPool(workerFactory),
                workerCount));
        }
        serverBootstrap.setPipelineFactory(configureServerChannelPipelineFactory(name, settings));
        if (!"default".equals(tcpNoDelay)) {
            serverBootstrap.setOption("child.tcpNoDelay", Booleans.parseBoolean(tcpNoDelay, null));
        }
        if (!"default".equals(tcpKeepAlive)) {
            serverBootstrap.setOption("child.keepAlive", Booleans.parseBoolean(tcpKeepAlive, null));
        }
        if (tcpSendBufferSize != null && tcpSendBufferSize.getBytes() > 0) {
            serverBootstrap.setOption("child.sendBufferSize", tcpSendBufferSize.getBytes());
        }
        if (tcpReceiveBufferSize != null && tcpReceiveBufferSize.getBytes() > 0) {
            serverBootstrap.setOption("child.receiveBufferSize", tcpReceiveBufferSize.getBytes());
        }
        serverBootstrap.setOption("receiveBufferSizePredictorFactory", receiveBufferSizePredictorFactory);
        serverBootstrap.setOption("child.receiveBufferSizePredictorFactory", receiveBufferSizePredictorFactory);
        serverBootstrap.setOption("reuseAddress", reuseAddress);
        serverBootstrap.setOption("child.reuseAddress", reuseAddress);
        serverBootstraps.put(name, serverBootstrap);
    }

    protected final void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        onException(
                ctx.getChannel(),
                e.getCause() == null || e.getCause() instanceof Exception ?
                        (Exception)e.getCause() : new ElasticsearchException(e.getCause()));
    }

    @Override
    public long serverOpen() {
        Netty3OpenChannelsHandler channels = serverOpenChannels;
        return channels == null ? 0 : channels.numberOfOpenChannels();
    }

    @Override
    protected NodeChannels connectToChannels(DiscoveryNode node, ConnectionProfile profile, Consumer<Channel> onChannelClose) {
        final Channel[] channels = new Channel[profile.getNumConnections()];
        final NodeChannels nodeChannels = new NodeChannels(node, channels, profile);
        boolean success = false;
        try {
            final TimeValue connectTimeout;
            final ClientBootstrap clientBootstrap;
            final TimeValue defaultConnectTimeout = defaultConnectionProfile.getConnectTimeout();
            if (profile.getConnectTimeout() != null && profile.getConnectTimeout().equals(defaultConnectTimeout) == false) {
                clientBootstrap = new ClientBootstrap(this.clientBootstrap.getFactory());
                clientBootstrap.setPipelineFactory(this.clientBootstrap.getPipelineFactory());
                clientBootstrap.setOptions(this.clientBootstrap.getOptions());
                clientBootstrap.setOption("connectTimeoutMillis", Math.toIntExact(profile.getConnectTimeout().millis()));
                connectTimeout = profile.getConnectTimeout();
            } else {
                connectTimeout = defaultConnectTimeout;
                clientBootstrap = this.clientBootstrap;
            }
            final ArrayList<ChannelFuture> connections = new ArrayList<>(channels.length);
            final InetSocketAddress address = ((InetSocketTransportAddress) node.getAddress()).address();
            for (int i = 0; i < channels.length; i++) {
                connections.add(clientBootstrap.connect(address));
            }
            final ChannelFutureListener listener = future -> onChannelClose.accept(future.getChannel());
            final Iterator<ChannelFuture> iterator = connections.iterator();
            try {
                for (int i = 0; i < channels.length; i++) {
                    assert iterator.hasNext();
                    ChannelFuture future = iterator.next();
                    future.awaitUninterruptibly((long) (connectTimeout.millis() * 1.5));
                    if (!future.isSuccess()) {
                        throw new ConnectTransportException(node, "connect_timeout[" + connectTimeout + "]", future.getCause());
                    }
                    channels[i] = future.getChannel();
                    channels[i].getCloseFuture().addListener(listener);
                }
                assert iterator.hasNext() == false : "not all created connection have been consumed";
            } catch (RuntimeException e) {
                for (ChannelFuture future : Collections.unmodifiableList(connections)) {
                    future.cancel();
                    if (future.getChannel() != null && future.getChannel().isOpen()) {
                        try {
                            future.getChannel().close();
                        } catch (Exception e1) {
                            // ignore
                        }
                    }
                }
                throw e;
            }
            success = true;
        } finally {
            if (success == false) {
                try {
                    nodeChannels.close();
                } catch (IOException e) {
                    logger.trace("exception while closing channels", e);
                }
            }
        }
        return nodeChannels;
    }

    public ChannelPipelineFactory configureClientChannelPipelineFactory() {
        return new ClientChannelPipelineFactory(this);
    }

    protected static class ClientChannelPipelineFactory implements ChannelPipelineFactory {
        protected final Netty3Transport nettyTransport;

        public ClientChannelPipelineFactory(Netty3Transport nettyTransport) {
            this.nettyTransport = nettyTransport;
        }

        @Override
        public ChannelPipeline getPipeline() throws Exception {
            ChannelPipeline channelPipeline = Channels.pipeline();
            Netty3SizeHeaderFrameDecoder sizeHeader = new Netty3SizeHeaderFrameDecoder();
            if (nettyTransport.maxCumulationBufferCapacity.getBytes() >= 0) {
                if (nettyTransport.maxCumulationBufferCapacity.getBytes() > Integer.MAX_VALUE) {
                    sizeHeader.setMaxCumulationBufferCapacity(Integer.MAX_VALUE);
                } else {
                    sizeHeader.setMaxCumulationBufferCapacity((int) nettyTransport.maxCumulationBufferCapacity.getBytes());
                }
            }
            if (nettyTransport.maxCompositeBufferComponents != -1) {
                sizeHeader.setMaxCumulationBufferComponents(nettyTransport.maxCompositeBufferComponents);
            }
            channelPipeline.addLast("size", sizeHeader);
            // using a dot as a prefix means, this cannot come from any settings parsed
            channelPipeline.addLast("dispatcher", new Netty3MessageChannelHandler(nettyTransport, ".client"));
            return channelPipeline;
        }
    }

    public ChannelPipelineFactory configureServerChannelPipelineFactory(String name, Settings settings) {
        return new ServerChannelPipelineFactory(this, name, settings);
    }

    protected static class ServerChannelPipelineFactory implements ChannelPipelineFactory {

        protected final Netty3Transport nettyTransport;
        protected final String name;
        protected final Settings settings;

        public ServerChannelPipelineFactory(Netty3Transport nettyTransport, String name, Settings settings) {
            this.nettyTransport = nettyTransport;
            this.name = name;
            this.settings = settings;
        }

        @Override
        public ChannelPipeline getPipeline() throws Exception {
            ChannelPipeline channelPipeline = Channels.pipeline();
            channelPipeline.addLast("openChannels", nettyTransport.serverOpenChannels);
            Netty3SizeHeaderFrameDecoder sizeHeader = new Netty3SizeHeaderFrameDecoder();
            if (nettyTransport.maxCumulationBufferCapacity.getBytes() > 0) {
                if (nettyTransport.maxCumulationBufferCapacity.getBytes() > Integer.MAX_VALUE) {
                    sizeHeader.setMaxCumulationBufferCapacity(Integer.MAX_VALUE);
                } else {
                    sizeHeader.setMaxCumulationBufferCapacity((int) nettyTransport.maxCumulationBufferCapacity.getBytes());
                }
            }
            if (nettyTransport.maxCompositeBufferComponents != -1) {
                sizeHeader.setMaxCumulationBufferComponents(nettyTransport.maxCompositeBufferComponents);
            }
            channelPipeline.addLast("size", sizeHeader);
            channelPipeline.addLast("dispatcher", new Netty3MessageChannelHandler(nettyTransport, name));
            return channelPipeline;
        }
    }

    @Override
    protected void sendMessage(Channel channel, BytesReference reference, Runnable sendListener) {
        final ChannelFuture future = channel.write(Netty3Utils.toChannelBuffer(reference));
        future.addListener(future1 -> sendListener.run());
    }

    @Override
    protected void closeChannels(List<Channel> channels, boolean blocking) throws IOException {
        if (blocking) {
            Netty3Utils.closeChannels(channels);
        } else {
            for (final Channel channel : channels) {
                if (channel != null && channel.isOpen()) {
                    final ChannelFuture closeFuture = channel.close();
                    closeFuture.addListener(f -> {
                        if (f.isSuccess() == false) {
                            logger.warn("failed to close channel", f.getCause());
                        }
                    });
                }
            }
        }
    }

    @Override
    protected InetSocketAddress getLocalAddress(Channel channel) {
        return (InetSocketAddress) channel.getLocalAddress();
    }

    @Override
    protected Channel bind(String name, InetSocketAddress address) {
        return serverBootstraps.get(name).bind(address);
    }

    ScheduledPing getPing() {
        return scheduledPing;
    }

    @Override
    protected boolean isOpen(Channel channel) {
        return channel.isOpen();
    }

    @Override
    protected void stopInternal() {
        Releasables.close(serverOpenChannels, () ->{
            for (Map.Entry<String, ServerBootstrap> entry : serverBootstraps.entrySet()) {
                String name = entry.getKey();
                ServerBootstrap serverBootstrap = entry.getValue();
                try {
                    serverBootstrap.releaseExternalResources();
                } catch (Exception e) {
                    logger.debug((Supplier<?>) () -> new ParameterizedMessage("Error closing serverBootstrap for profile [{}]", name), e);
                }
            }
            serverBootstraps.clear();
            if (clientBootstrap != null) {
                clientBootstrap.releaseExternalResources();
                clientBootstrap = null;
            }
        });
    }

}
