/*
 * Copyright © 2018-2019 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.http.api;

import io.servicetalk.buffer.api.Buffer;
import io.servicetalk.buffer.api.BufferAllocator;
import io.servicetalk.concurrent.BlockingIterable;
import io.servicetalk.concurrent.BlockingIterator;
import io.servicetalk.concurrent.PublisherSource.Subscriber;
import io.servicetalk.concurrent.PublisherSource.Subscription;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.concurrent.api.TestPublisher;
import io.servicetalk.concurrent.api.TestSubscription;
import io.servicetalk.concurrent.internal.ServiceTalkTestTimeout;
import io.servicetalk.transport.api.ExecutionContext;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.servicetalk.buffer.netty.BufferAllocators.DEFAULT_ALLOCATOR;
import static io.servicetalk.concurrent.api.Completable.completed;
import static io.servicetalk.concurrent.api.Executors.immediate;
import static io.servicetalk.concurrent.api.Publisher.just;
import static io.servicetalk.concurrent.api.Single.success;
import static io.servicetalk.concurrent.api.SourceAdapters.toSource;
import static io.servicetalk.http.api.HttpProtocolVersion.HTTP_1_1;
import static io.servicetalk.http.api.HttpResponseStatus.OK;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Collections.singleton;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BlockingStreamingHttpServiceTest {
    @Rule
    public final ServiceTalkTestTimeout timeout = new ServiceTalkTestTimeout();

    @Mock
    private BlockingIterable<Buffer> mockIterable;
    @Mock
    private BlockingIterator<Buffer> mockIterator;
    @Mock
    private ExecutionContext mockExecutionCtx;

    private final TestPublisher<Buffer> publisher = new TestPublisher<>();
    private static final BufferAllocator allocator = DEFAULT_ALLOCATOR;
    private final StreamingHttpRequestResponseFactory reqRespFactory = new DefaultStreamingHttpRequestResponseFactory(
            allocator, DefaultHttpHeadersFactory.INSTANCE);
    private final BlockingStreamingHttpRequestResponseFactory blkReqRespFactory =
            new StreamingHttpRequestResponseFactoryToBlockingStreamingHttpRequestResponseFactory(reqRespFactory);
    private final HttpServiceContext mockCtx = new TestHttpServiceContext(reqRespFactory, mockExecutionCtx);

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mockExecutionCtx.executor()).thenReturn(immediate());
        when(mockIterable.iterator()).thenReturn(mockIterator);
    }

    @Test
    public void asyncToSyncNoPayload() throws Exception {
        StreamingHttpService asyncService = new StreamingHttpService() {
            @Override
            public Single<StreamingHttpResponse> handle(final HttpServiceContext ctx,
                                                        final StreamingHttpRequest request,
                                                        final StreamingHttpResponseFactory factory) {
                return success(factory.ok());
            }
        };
        BlockingStreamingHttpService syncService = asyncService.asBlockingStreamingService();
        BlockingStreamingHttpResponse syncResponse = syncService.handle(mockCtx,
                blkReqRespFactory.get("/"), blkReqRespFactory);
        assertEquals(HTTP_1_1, syncResponse.version());
        assertEquals(OK, syncResponse.status());
    }

    @Test
    public void asyncToSyncWithPayload() throws Exception {
        StreamingHttpService asyncService = new StreamingHttpService() {
            @Override
            public Single<StreamingHttpResponse> handle(final HttpServiceContext ctx,
                                                        final StreamingHttpRequest request,
                                                        final StreamingHttpResponseFactory factory) {
                return success(factory.ok().payloadBody(just(allocator.fromAscii("hello"))));
            }
        };
        BlockingStreamingHttpService syncService = asyncService.asBlockingStreamingService();
        BlockingStreamingHttpResponse syncResponse = syncService.handle(mockCtx,
                blkReqRespFactory.get("/"), blkReqRespFactory);
        assertEquals(HTTP_1_1, syncResponse.version());
        assertEquals(OK, syncResponse.status());
        BlockingIterator<Buffer> iterator = syncResponse.payloadBody().iterator();
        assertTrue(iterator.hasNext());
        assertEquals("hello", iterator.next().toString(US_ASCII));
        assertFalse(iterator.hasNext());
    }

    @Test
    public void asyncToSyncWithPayloadInputStream() throws Exception {
        String expectedPayload = "hello";
        byte[] expectedPayloadBytes = expectedPayload.getBytes(US_ASCII);
        StreamingHttpService asyncService = new StreamingHttpService() {
            @Override
            public Single<StreamingHttpResponse> handle(final HttpServiceContext ctx,
                                                        final StreamingHttpRequest request,
                                                        final StreamingHttpResponseFactory factory) {
                return success(factory.ok().payloadBody(just(allocator.fromAscii(expectedPayload))));
            }
        };
        BlockingStreamingHttpService syncService = asyncService.asBlockingStreamingService();
        BlockingStreamingHttpResponse syncResponse = syncService.handle(mockCtx,
                blkReqRespFactory.get("/"), blkReqRespFactory);
        assertEquals(HTTP_1_1, syncResponse.version());
        assertEquals(OK, syncResponse.status());
        InputStream is = syncResponse.payloadBodyInputStream();
        byte[] actualPayloadBytes = new byte[expectedPayloadBytes.length];
        assertEquals(expectedPayloadBytes.length, is.read(actualPayloadBytes, 0, actualPayloadBytes.length));
        assertArrayEquals(expectedPayloadBytes, actualPayloadBytes);
        is.close();
    }

    @Test
    public void asyncToSyncClose() throws Exception {
        final AtomicBoolean closedCalled = new AtomicBoolean();
        StreamingHttpService asyncService = new StreamingHttpService() {
            @Override
            public Single<StreamingHttpResponse> handle(final HttpServiceContext ctx,
                                                        final StreamingHttpRequest request,
                                                        final StreamingHttpResponseFactory factory) {
                return Single.error(new IllegalStateException("shouldn't be called!"));
            }

            @Override
            public Completable closeAsync() {
                closedCalled.set(true);
                return completed();
            }
        };
        BlockingStreamingHttpService syncService = asyncService.asBlockingStreamingService();
        syncService.close();
        assertTrue(closedCalled.get());
    }

    @Test
    public void asyncToSyncCancelPropagated() throws Exception {
        StreamingHttpService asyncService = new StreamingHttpService() {
            @Override
            public Single<StreamingHttpResponse> handle(final HttpServiceContext ctx,
                                                        final StreamingHttpRequest request,
                                                        final StreamingHttpResponseFactory factory) {
                return success(factory.ok().payloadBody(publisher));
            }
        };
        final TestSubscription subscription = new TestSubscription();
        BlockingStreamingHttpService syncService = asyncService.asBlockingStreamingService();
        BlockingStreamingHttpResponse syncResponse = syncService.handle(mockCtx,
                blkReqRespFactory.get("/"), blkReqRespFactory);
        assertEquals(HTTP_1_1, syncResponse.version());
        assertEquals(OK, syncResponse.status());
        BlockingIterator<Buffer> iterator = syncResponse.payloadBody().iterator();
        publisher.onSubscribe(subscription);
        publisher.onNext(allocator.fromAscii("hello"));
        assertTrue(iterator.hasNext());
        iterator.close();

        assertTrue(subscription.isCancelled());
    }

    @Test
    public void syncToAsyncNoPayload() throws Exception {
        BlockingStreamingHttpService syncService = new BlockingStreamingHttpService() {
            @Override
            public BlockingStreamingHttpResponse handle(final HttpServiceContext ctx,
                                                        final BlockingStreamingHttpRequest request,
                                                        final BlockingStreamingHttpResponseFactory factory) {
                return factory.ok();
            }
        };
        StreamingHttpService asyncService = syncService.asStreamingService();
        StreamingHttpResponse asyncResponse = asyncService.handle(mockCtx, reqRespFactory.get("/"), reqRespFactory)
                .toFuture().get();
        assertNotNull(asyncResponse);
        assertEquals(HTTP_1_1, asyncResponse.version());
        assertEquals(OK, asyncResponse.status());
    }

    @Test
    public void syncToAsyncWithPayload() throws Exception {
        BlockingStreamingHttpService syncService = new BlockingStreamingHttpService() {
            @Override
            public BlockingStreamingHttpResponse handle(final HttpServiceContext ctx,
                                                        final BlockingStreamingHttpRequest request,
                                                        final BlockingStreamingHttpResponseFactory factory) {
                return factory.ok().payloadBody(singleton(allocator.fromAscii("hello")));
            }
        };
        StreamingHttpService asyncService = syncService.asStreamingService();
        StreamingHttpResponse asyncResponse = asyncService.handle(mockCtx, reqRespFactory.get("/"), reqRespFactory).toFuture().get();
        assertNotNull(asyncResponse);
        assertEquals(HTTP_1_1, asyncResponse.version());
        assertEquals(OK, asyncResponse.status());
        assertEquals("hello", asyncResponse.payloadBody()
                .reduce(() -> "", (acc, next) -> acc + next.toString(US_ASCII)).toFuture().get());
    }

    @Test
    public void syncToAsyncWithPayloadInputStream() throws Exception {
        String expectedPayload = "hello";
        byte[] expectedPayloadBytes = expectedPayload.getBytes(US_ASCII);
        BlockingStreamingHttpService syncService = new BlockingStreamingHttpService() {
            @Override
            public BlockingStreamingHttpResponse handle(final HttpServiceContext ctx,
                                                        final BlockingStreamingHttpRequest request,
                                                        final BlockingStreamingHttpResponseFactory factory)
                    throws Exception {
                byte[] actualPayloadBytes = new byte[expectedPayloadBytes.length];
                request.payloadBodyInputStream().read(actualPayloadBytes);
                return factory.ok().payloadBody(singleton(allocator.wrap(actualPayloadBytes)));
            }
        };
        StreamingHttpService asyncService = syncService.asStreamingService();
        StreamingHttpResponse asyncResponse = asyncService.handle(mockCtx,
                reqRespFactory.post("/")
                        .payloadBody(just(allocator.fromAscii(expectedPayload))), reqRespFactory).toFuture().get();
        assertNotNull(asyncResponse);
        assertEquals(HTTP_1_1, asyncResponse.version());
        assertEquals(OK, asyncResponse.status());
        assertEquals("hello", asyncResponse.payloadBody().reduce(() -> "",
                (acc, next) -> acc + next.toString(US_ASCII)).toFuture().get());
    }

    @Test
    public void syncToAsyncClose() throws Exception {
        final AtomicBoolean closedCalled = new AtomicBoolean();
        BlockingStreamingHttpService syncService = new BlockingStreamingHttpService() {
            @Override
            public BlockingStreamingHttpResponse handle(final HttpServiceContext ctx,
                                                        final BlockingStreamingHttpRequest request,
                                                        final BlockingStreamingHttpResponseFactory factory) {
                throw new IllegalStateException("shouldn't be called!");
            }

            @Override
            public void close() {
                closedCalled.set(true);
            }
        };
        StreamingHttpService asyncService = syncService.asStreamingService();
        asyncService.closeAsync().toFuture().get();
        assertTrue(closedCalled.get());
    }

    @Test
    public void syncToAsyncCancelPropagated() throws Exception {
        BlockingStreamingHttpService syncService = new BlockingStreamingHttpService() {
            @Override
            public BlockingStreamingHttpResponse handle(final HttpServiceContext ctx,
                                                        final BlockingStreamingHttpRequest request,
                                                        final BlockingStreamingHttpResponseFactory factory) {
                return factory.ok().payloadBody(mockIterable);
            }
        };
        StreamingHttpService asyncService = syncService.asStreamingService();
        StreamingHttpResponse asyncResponse = asyncService.handle(mockCtx, reqRespFactory.get("/"), reqRespFactory)
                .toFuture().get();
        assertNotNull(asyncResponse);
        CountDownLatch latch = new CountDownLatch(1);
        toSource(asyncResponse.payloadBody()).subscribe(new Subscriber<Buffer>() {
            @Override
            public void onSubscribe(final Subscription s) {
                s.cancel();
                latch.countDown();
            }

            @Override
            public void onNext(final Buffer s) {
            }

            @Override
            public void onError(final Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });
        latch.await();
        verify(mockIterator).close();
    }
}