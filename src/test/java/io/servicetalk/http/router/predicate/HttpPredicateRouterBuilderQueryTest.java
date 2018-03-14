/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.http.router.predicate;

import io.servicetalk.http.api.HttpPayloadChunk;
import io.servicetalk.http.api.HttpService;
import org.junit.Test;

import java.util.regex.Pattern;

import static java.util.Collections.emptyIterator;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.when;

public class HttpPredicateRouterBuilderQueryTest extends BaseHttpPredicateRouterBuilderTest {

    @Test
    public void testWhenQueryParamIsPresent() {
        final HttpService<HttpPayloadChunk, HttpPayloadChunk> service = new HttpPredicateRouterBuilder<HttpPayloadChunk, HttpPayloadChunk>()
                .whenQueryParam("page").isPresent().thenRouteTo(serviceA)
                .when((ctx, req) -> true).thenRouteTo(fallbackService)
                .build();

        when(request.getQueryValues("page")).then(answerIteratorOf("home"));
        assertSame(responseA, service.handle(ctx, request));

        when(request.getQueryValues("page")).thenReturn(emptyIterator());
        assertSame(fallbackResponse, service.handle(ctx, request));
    }

    @Test
    public void testWhenQueryParamFirstValue() {
        final HttpService<HttpPayloadChunk, HttpPayloadChunk> service = new HttpPredicateRouterBuilder<HttpPayloadChunk, HttpPayloadChunk>()
                .whenQueryParam("page").firstValue("home").thenRouteTo(serviceA)
                .when((ctx, req) -> true).thenRouteTo(fallbackService)
                .build();

        when(request.getQueryValues("page")).then(answerIteratorOf("home"));
        assertSame(responseA, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("home", "signUp"));
        assertSame(responseA, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("signUp", "home"));
        assertSame(fallbackResponse, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("signUp"));
        assertSame(fallbackResponse, service.handle(ctx, request));

        when(request.getQueryValues("page")).thenReturn(emptyIterator());
        assertSame(fallbackResponse, service.handle(ctx, request));
    }

    @Test
    public void testWhenQueryParamFirstValueMatches() {
        final HttpService<HttpPayloadChunk, HttpPayloadChunk> service = new HttpPredicateRouterBuilder<HttpPayloadChunk, HttpPayloadChunk>()
                .whenQueryParam("page").firstValueMatches("sign.*").thenRouteTo(serviceA)
                .when((ctx, req) -> true).thenRouteTo(fallbackService)
                .build();

        when(request.getQueryValues("page")).then(answerIteratorOf("signUp"));
        assertSame(responseA, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("signUp", "home"));
        assertSame(responseA, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("SignUp", "home"));
        assertSame(fallbackResponse, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("home", "signUp"));
        assertSame(fallbackResponse, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("home"));
        assertSame(fallbackResponse, service.handle(ctx, request));
    }

    @Test
    public void testWhenQueryParamFirstValueMatchesPattern() {
        final HttpService<HttpPayloadChunk, HttpPayloadChunk> service = new HttpPredicateRouterBuilder<HttpPayloadChunk, HttpPayloadChunk>()
                .whenQueryParam("page").firstValueMatches(Pattern.compile("sign.*", CASE_INSENSITIVE)).thenRouteTo(serviceA)
                .when((ctx, req) -> true).thenRouteTo(fallbackService)
                .build();

        when(request.getQueryValues("page")).then(answerIteratorOf("signUp"));
        assertSame(responseA, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("signUp", "home"));
        assertSame(responseA, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("SignUp", "home"));
        assertSame(responseA, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("home", "signUp"));
        assertSame(fallbackResponse, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("home"));
        assertSame(fallbackResponse, service.handle(ctx, request));
    }

    @Test
    public void testWhenQueryParamValues() {
        final HttpService<HttpPayloadChunk, HttpPayloadChunk> service = new HttpPredicateRouterBuilder<HttpPayloadChunk, HttpPayloadChunk>()
                .whenQueryParam("page").values(new AnyMatchPredicate<>("home")).thenRouteTo(serviceA)
                .when((ctx, req) -> true).thenRouteTo(fallbackService)
                .build();

        when(request.getQueryValues("page")).then(answerIteratorOf("home"));
        assertSame(responseA, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("signUp", "home"));
        assertSame(responseA, service.handle(ctx, request));

        when(request.getQueryValues("page")).then(answerIteratorOf("signUp"));
        assertSame(fallbackResponse, service.handle(ctx, request));

        when(request.getQueryValues("page")).thenReturn(emptyIterator());
        assertSame(fallbackResponse, service.handle(ctx, request));
    }
}
