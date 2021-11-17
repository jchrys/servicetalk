/*
 * Copyright © 2021 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.http.netty;

import io.servicetalk.concurrent.api.AsyncContext;
import io.servicetalk.concurrent.api.AsyncContextMap;

import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.AsyncContextMap.Key.newKey;

public class BlockingStreamingHttpServiceAsyncContextDeprecatedTest
        extends BlockingStreamingHttpServiceAsyncContextTest {

    private static final AsyncContextMap.Key<CharSequence> K1 = newKey("k1");

    @Override
    void putIntoAsyncContext(final CharSequence value) {
        AsyncContext.put(K1, value);
    }

    @Nullable
    @Override
    CharSequence getFromAsyncContext() {
        return AsyncContext.get(K1);
    }

    @Override
    String keyToString() {
        return K1.toString();
    }
}
