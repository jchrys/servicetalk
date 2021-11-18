/*
 * Copyright © 2019-2021 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.grpc.netty;

import io.servicetalk.buffer.api.BufferAllocator;
import io.servicetalk.concurrent.api.AsyncContext;
import io.servicetalk.concurrent.api.Executor;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.grpc.api.GrpcExecutionStrategy;
import io.servicetalk.grpc.api.GrpcLifecycleObserver;
import io.servicetalk.grpc.api.GrpcServerBuilder;
import io.servicetalk.grpc.api.GrpcServerSecurityConfigurator;
import io.servicetalk.grpc.api.GrpcServiceFactory;
import io.servicetalk.grpc.api.GrpcServiceFactory.ServerBinder;
import io.servicetalk.http.api.BlockingHttpService;
import io.servicetalk.http.api.BlockingStreamingHttpService;
import io.servicetalk.http.api.HttpExecutionStrategy;
import io.servicetalk.http.api.HttpLifecycleObserver;
import io.servicetalk.http.api.HttpProtocolConfig;
import io.servicetalk.http.api.HttpRequestMetaData;
import io.servicetalk.http.api.HttpServerBuilder;
import io.servicetalk.http.api.HttpServerSecurityConfigurator;
import io.servicetalk.http.api.HttpService;
import io.servicetalk.http.api.StreamingHttpRequest;
import io.servicetalk.http.api.StreamingHttpService;
import io.servicetalk.http.api.StreamingHttpServiceFilterFactory;
import io.servicetalk.http.utils.TimeoutFromRequest;
import io.servicetalk.http.utils.TimeoutHttpServiceFilter;
import io.servicetalk.logging.api.LogLevel;
import io.servicetalk.transport.api.ConnectionAcceptor;
import io.servicetalk.transport.api.ConnectionAcceptorFactory;
import io.servicetalk.transport.api.IoExecutor;
import io.servicetalk.transport.api.ServerContext;
import io.servicetalk.transport.api.ServerSslConfig;
import io.servicetalk.transport.api.TransportObserver;
import io.servicetalk.transport.netty.internal.ExecutionContextBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.SocketOption;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import static io.servicetalk.grpc.api.GrpcExecutionStrategies.defaultStrategy;
import static io.servicetalk.grpc.internal.DeadlineUtils.GRPC_DEADLINE_CONTEXT_KEY;
import static io.servicetalk.grpc.internal.DeadlineUtils.readTimeoutHeader;
import static io.servicetalk.http.netty.HttpProtocolConfigs.h2Default;
import static io.servicetalk.utils.internal.DurationUtils.ensurePositive;
import static java.util.Objects.requireNonNull;

final class DefaultGrpcServerBuilder extends GrpcServerBuilder implements ServerBinder {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultGrpcServerBuilder.class);

    private final Supplier<HttpServerBuilder> httpServerBuilderSupplier;
    private GrpcServerBuilder.HttpInitializer initializer = builder -> {
        // no-op
    };
    private GrpcServerBuilder.HttpInitializer directCallInitializer = builder -> {
        // no-op
    };

    @Nullable
    private ExecutionContextInterceptorHttpServerBuilder interceptorBuilder;

    /**
     * A duration greater than zero or null for no timeout.
     */
    @Nullable
    private Duration defaultTimeout;

    DefaultGrpcServerBuilder(final Supplier<HttpServerBuilder> httpServerBuilderSupplier) {
        this.httpServerBuilderSupplier = () -> httpServerBuilderSupplier.get()
                .protocols(h2Default()).allowDropRequestTrailers(true);
    }

    @Override
    public GrpcServerBuilder initializeHttp(final GrpcServerBuilder.HttpInitializer initializer) {
        this.initializer = requireNonNull(initializer);
        return this;
    }

    @Override
    public GrpcServerBuilder defaultTimeout(Duration defaultTimeout) {
        this.defaultTimeout = ensurePositive(defaultTimeout, "defaultTimeout");
        return this;
    }

    @Override
    public GrpcServerBuilder protocols(final HttpProtocolConfig... protocols) {
        directCallInitializer = directCallInitializer.append(builder -> builder.protocols(protocols));
        return this;
    }

    @Deprecated
    @Override
    public GrpcServerSecurityConfigurator secure() {
        return new LazyGrpcServerSecurityConfigurator();
    }

    @Override
    public GrpcServerBuilder sslConfig(final ServerSslConfig config) {
        directCallInitializer = directCallInitializer.append(builder -> builder.sslConfig(config));
        return this;
    }

    @Override
    public GrpcServerBuilder sslConfig(final ServerSslConfig defaultConfig, final Map<String, ServerSslConfig> sniMap) {
        directCallInitializer = directCallInitializer.append(builder -> builder.sslConfig(defaultConfig, sniMap));
        return this;
    }

    @Override
    public <T> GrpcServerBuilder socketOption(final SocketOption<T> option, final T value) {
        directCallInitializer = directCallInitializer.append(builder -> builder.socketOption(option, value));
        return this;
    }

    @Override
    public <T> GrpcServerBuilder listenSocketOption(final SocketOption<T> option, final T value) {
        directCallInitializer = directCallInitializer.append(builder -> builder.listenSocketOption(option, value));
        return this;
    }

    @Deprecated
    @Override
    public GrpcServerBuilder enableWireLogging(final String loggerName) {
        directCallInitializer = directCallInitializer.append(builder -> builder.enableWireLogging(loggerName));
        return this;
    }

    @Override
    public GrpcServerBuilder enableWireLogging(final String loggerName, final LogLevel logLevel,
                                               final BooleanSupplier logUserData) {
        directCallInitializer = directCallInitializer.append(builder ->
                builder.enableWireLogging(loggerName, logLevel, logUserData));
        return this;
    }

    @Override
    public GrpcServerBuilder transportObserver(final TransportObserver transportObserver) {
        directCallInitializer = directCallInitializer.append(builder -> builder.transportObserver(transportObserver));
        return this;
    }

    @Override
    public GrpcServerBuilder lifecycleObserver(final GrpcLifecycleObserver lifecycleObserver) {
        directCallInitializer = directCallInitializer.append(builder -> builder
                .lifecycleObserver(new GrpcToHttpLifecycleObserverBridge(lifecycleObserver)));
        return this;
    }

    @Override
    public GrpcServerBuilder drainRequestPayloadBody(boolean enable) {
        directCallInitializer = directCallInitializer.append(builder -> builder.drainRequestPayloadBody(enable));
        return this;
    }

    @Override
    public GrpcServerBuilder appendConnectionAcceptorFilter(final ConnectionAcceptorFactory factory) {
        directCallInitializer = directCallInitializer.append(builder ->
                builder.appendConnectionAcceptorFilter(factory));
        return this;
    }

    @Override
    public GrpcServerBuilder executor(final Executor executor) {
        directCallInitializer = directCallInitializer.append(builder -> builder.executor(executor));
        return this;
    }

    @Override
    public GrpcServerBuilder ioExecutor(final IoExecutor ioExecutor) {
        directCallInitializer = directCallInitializer.append(builder -> builder.ioExecutor(ioExecutor));
        return this;
    }

    @Override
    public GrpcServerBuilder bufferAllocator(final BufferAllocator allocator) {
        directCallInitializer = directCallInitializer.append(builder -> builder.bufferAllocator(allocator));
        return this;
    }

    @Override
    public GrpcServerBuilder executionStrategy(final GrpcExecutionStrategy strategy) {
        directCallInitializer = directCallInitializer.append(builder -> builder.executionStrategy(strategy));
        return this;
    }

    @Override
    protected Single<ServerContext> doListen(final GrpcServiceFactory<?, ?, ?> serviceFactory) {
        interceptorBuilder = preBuild();
        return serviceFactory.bind(this, interceptorBuilder.contextBuilder.build());
    }

    @Override
    protected void doAppendHttpServiceFilter(final StreamingHttpServiceFilterFactory factory) {
        directCallInitializer = directCallInitializer.append(builder -> builder.appendServiceFilter(factory));
    }

    @Override
    protected void doAppendHttpServiceFilter(final Predicate<StreamingHttpRequest> predicate,
                                             final StreamingHttpServiceFilterFactory factory) {
        directCallInitializer = directCallInitializer.append(builder ->
                builder.appendServiceFilter(predicate, factory));
    }

    private ExecutionContextInterceptorHttpServerBuilder preBuild() {
        final ExecutionContextInterceptorHttpServerBuilder interceptor =
                new ExecutionContextInterceptorHttpServerBuilder(httpServerBuilderSupplier.get());

        appendCatchAllFilter(interceptor);

        directCallInitializer.initialize(interceptor);
        initializer.initialize(interceptor);

        interceptor.appendServiceFilter(
                new TimeoutHttpServiceFilter(grpcDetermineTimeout(defaultTimeout), true));
        return interceptor;
    }

    private static TimeoutFromRequest grpcDetermineTimeout(@Nullable Duration defaultTimeout) {
        return new TimeoutFromRequest() {
            /**
             * Return the timeout duration extracted from the GRPC timeout HTTP header if present or default timeout.
             *
             * @param request The HTTP request to be used as source of the GRPC timeout header
             * @return The non-negative timeout duration which may be null
             */
            @Override
            public @Nullable Duration apply(HttpRequestMetaData request) {
                @Nullable
                Duration requestTimeout = readTimeoutHeader(request);
                @Nullable
                Duration timeout = null != requestTimeout ? requestTimeout : defaultTimeout;

                if (null != timeout) {
                    // Store the timeout in the context as a deadline to be used for any client requests created
                    // during the context of handling this request.
                    try {
                        Long deadline = System.nanoTime() + timeout.toNanos();
                        AsyncContext.put(GRPC_DEADLINE_CONTEXT_KEY, deadline);
                    } catch (UnsupportedOperationException ignored) {
                        LOGGER.debug("Async context disabled, timeouts will not be propagated to client requests");
                        // ignored -- async context has probably been disabled.
                        // Timeout propagation will be partially disabled.
                        // cancel()s will still happen which will accomplish the same effect though less efficiently
                    }
                }

                return timeout;
            }

            @Override
            public HttpExecutionStrategy influenceStrategy(final HttpExecutionStrategy strategy) {
                // We don't block so have no influence on strategy.
                return strategy;
            }
        };
    }

    @Override
    public Single<ServerContext> bind(final HttpService service) {
        return interceptorBuilder.listen(service);
    }

    @Override
    public Single<ServerContext> bindStreaming(final StreamingHttpService service) {
        return interceptorBuilder.listenStreaming(service);
    }

    @Override
    public Single<ServerContext> bindBlocking(final BlockingHttpService service) {
        return interceptorBuilder.listenBlocking(service);
    }

    @Override
    public Single<ServerContext> bindBlockingStreaming(final BlockingStreamingHttpService service) {
        return interceptorBuilder.listenBlockingStreaming(service);
    }

    private class LazyGrpcServerSecurityConfigurator implements GrpcServerSecurityConfigurator {

        @Nullable
        private HttpServerSecurityConfigurator delegate;

        private HttpInitializer initializer = builder -> delegate = requireNonNull(builder.secure());

        @Override
        public GrpcServerSecurityConfigurator trustManager(final Supplier<InputStream> trustCertChainSupplier) {
            initializer = initializer.append(builder -> delegate.trustManager(trustCertChainSupplier));
            return this;
        }

        @Override
        public GrpcServerSecurityConfigurator trustManager(final TrustManagerFactory trustManagerFactory) {
            initializer = initializer.append(builder -> delegate.trustManager(trustManagerFactory));
            return this;
        }

        @Override
        public GrpcServerSecurityConfigurator protocols(final String... protocols) {
            initializer = initializer.append(builder -> delegate.protocols(protocols));
            return this;
        }

        @Override
        public GrpcServerSecurityConfigurator ciphers(final Iterable<String> ciphers) {
            initializer = initializer.append(builder -> delegate.ciphers(ciphers));
            return this;
        }

        @Override
        public GrpcServerSecurityConfigurator sessionCacheSize(final long sessionCacheSize) {
            initializer = initializer.append(builder -> delegate.sessionCacheSize(sessionCacheSize));
            return this;
        }

        @Override
        public GrpcServerSecurityConfigurator sessionTimeout(final long sessionTimeout) {
            initializer = initializer.append(builder -> delegate.sessionTimeout(sessionTimeout));
            return this;
        }

        @Override
        public GrpcServerSecurityConfigurator provider(final SslProvider provider) {
            initializer = initializer.append(builder -> delegate.provider(provider));
            return this;
        }

        @Override
        public GrpcServerSecurityConfigurator clientAuth(final ClientAuth clientAuth) {
            initializer = initializer.append(builder -> delegate.clientAuth(clientAuth));
            return this;
        }

        @Override
        public GrpcServerBuilder commit(final KeyManagerFactory keyManagerFactory) {
            initializer = initializer.append(builder -> delegate.commit(keyManagerFactory));
            DefaultGrpcServerBuilder.this.directCallInitializer =
                    DefaultGrpcServerBuilder.this.directCallInitializer.append(initializer);
            return DefaultGrpcServerBuilder.this;
        }

        @Override
        public GrpcServerBuilder commit(final Supplier<InputStream> keyCertChainSupplier,
                                        final Supplier<InputStream> keySupplier) {
            initializer = initializer.append(builder -> delegate.commit(keyCertChainSupplier, keySupplier));
            DefaultGrpcServerBuilder.this.directCallInitializer =
                    DefaultGrpcServerBuilder.this.directCallInitializer.append(initializer);
            return DefaultGrpcServerBuilder.this;
        }

        @Override
        public GrpcServerBuilder commit(final Supplier<InputStream> keyCertChainSupplier,
                                        final Supplier<InputStream> keySupplier,
                                        final String keyPassword) {
            initializer = initializer.append(builder ->
                    delegate.commit(keyCertChainSupplier, keySupplier, keyPassword));
            DefaultGrpcServerBuilder.this.directCallInitializer =
                    DefaultGrpcServerBuilder.this.directCallInitializer.append(initializer);
            return DefaultGrpcServerBuilder.this;
        }
    }

    private static class ExecutionContextInterceptorHttpServerBuilder extends HttpServerBuilder {
        private final HttpServerBuilder delegate;
        private final ExecutionContextBuilder contextBuilder = new ExecutionContextBuilder()
                // Make sure we always set a strategy so that ExecutionContextBuilder does not create a strategy
                // which is not compatible with gRPC.
                .executionStrategy(defaultStrategy());

        ExecutionContextInterceptorHttpServerBuilder(final HttpServerBuilder delegate) {
            this.delegate = delegate;
        }

        @Override
        protected Single<ServerContext> doListen(@Nullable final ConnectionAcceptor connectionAcceptor,
                                                 final StreamingHttpService service,
                                                 final HttpExecutionStrategy strategy,
                                                 final boolean drainRequestPayloadBody) {
            throw new UnsupportedOperationException("This delegate builder does not create the ServerContext");
        }

        @Override
        public HttpServerBuilder executor(final Executor executor) {
            contextBuilder.executor(executor);
            delegate.executor(executor);
            return this;
        }

        @Override
        public HttpServerBuilder ioExecutor(final IoExecutor ioExecutor) {
            contextBuilder.ioExecutor(ioExecutor);
            delegate.ioExecutor(ioExecutor);
            return this;
        }

        @Override
        public HttpServerBuilder executionStrategy(final HttpExecutionStrategy strategy) {
            contextBuilder.executionStrategy(strategy);
            delegate.executionStrategy(strategy);
            return this;
        }

        @Override
        public HttpServerBuilder bufferAllocator(final BufferAllocator allocator) {
            contextBuilder.bufferAllocator(allocator);
            delegate.bufferAllocator(allocator);
            return this;
        }

        @Override
        public HttpServerBuilder protocols(final HttpProtocolConfig... protocols) {
            delegate.protocols(protocols);
            return this;
        }

        @Override
        public HttpServerSecurityConfigurator secure() {
            return delegate.secure();
        }

        @Override
        public HttpServerBuilder sslConfig(final ServerSslConfig config) {
            delegate.sslConfig(config);
            return this;
        }

        @Override
        public HttpServerBuilder sslConfig(final ServerSslConfig defaultConfig,
                                           final Map<String, ServerSslConfig> sniMap) {
            delegate.sslConfig(defaultConfig, sniMap);
            return this;
        }

        @Override
        public <T> HttpServerBuilder socketOption(final SocketOption<T> option, final T value) {
            delegate.socketOption(option, value);
            return this;
        }

        @Override
        public <T> HttpServerBuilder listenSocketOption(final SocketOption<T> option, final T value) {
            delegate.listenSocketOption(option, value);
            return this;
        }

        @Override
        public HttpServerBuilder enableWireLogging(final String loggerName) {
            delegate.enableWireLogging(loggerName);
            return this;
        }

        @Override
        public HttpServerBuilder enableWireLogging(final String loggerName, final LogLevel logLevel,
                                                   final BooleanSupplier logUserData) {
            delegate.enableWireLogging(loggerName, logLevel, logUserData);
            return this;
        }

        @Override
        public HttpServerBuilder transportObserver(final TransportObserver transportObserver) {
            delegate.transportObserver(transportObserver);
            return this;
        }

        @Override
        public HttpServerBuilder allowDropRequestTrailers(final boolean allowDrop) {
            delegate.allowDropRequestTrailers(allowDrop);
            return this;
        }

        @Override
        public HttpServerBuilder backlog(final int backlog) {
            delegate.backlog(backlog);
            return this;
        }

        @Override
        public HttpServerBuilder lifecycleObserver(final HttpLifecycleObserver lifecycleObserver) {
            delegate.lifecycleObserver(lifecycleObserver);
            return this;
        }

        @Override
        public HttpServerBuilder disableDrainingRequestPayloadBody() {
            delegate.disableDrainingRequestPayloadBody();
            return this;
        }

        @Override
        public HttpServerBuilder drainRequestPayloadBody(final boolean enable) {
            delegate.drainRequestPayloadBody(enable);
            return this;
        }

        @Override
        public HttpServerBuilder appendConnectionAcceptorFilter(final ConnectionAcceptorFactory factory) {
            delegate.appendConnectionAcceptorFilter(factory);
            return this;
        }

        @Override
        public HttpServerBuilder appendServiceFilter(final StreamingHttpServiceFilterFactory factory) {
            delegate.appendServiceFilter(factory);
            return this;
        }

        @Override
        public HttpServerBuilder appendServiceFilter(final Predicate<StreamingHttpRequest> predicate,
                                                     final StreamingHttpServiceFilterFactory factory) {
            delegate.appendServiceFilter(predicate, factory);
            return this;
        }

        @Override
        public ServerContext listenAndAwait(final HttpService service) throws Exception {
            return delegate.listenAndAwait(service);
        }

        @Override
        public ServerContext listenStreamingAndAwait(final StreamingHttpService handler) throws Exception {
            return delegate.listenStreamingAndAwait(handler);
        }

        @Override
        public ServerContext listenBlockingAndAwait(final BlockingHttpService service) throws Exception {
            return delegate.listenBlockingAndAwait(service);
        }

        @Override
        public ServerContext listenBlockingStreamingAndAwait(final BlockingStreamingHttpService handler)
                throws Exception {
            return delegate.listenBlockingStreamingAndAwait(handler);
        }

        @Override
        public Single<ServerContext> listen(final HttpService service) {
            return delegate.listen(service);
        }

        @Override
        public Single<ServerContext> listenStreaming(final StreamingHttpService service) {
            return delegate.listenStreaming(service);
        }

        @Override
        public Single<ServerContext> listenBlocking(final BlockingHttpService service) {
            return delegate.listenBlocking(service);
        }

        @Override
        public Single<ServerContext> listenBlockingStreaming(final BlockingStreamingHttpService service) {
            return delegate.listenBlockingStreaming(service);
        }
    }
}
