/*
 * Copyright © 2019, 2021, 2023 Apple Inc. and the ServiceTalk project authors
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

import io.servicetalk.http.api.BlockingHttpClient;
import io.servicetalk.http.api.HttpProtocolConfig;
import io.servicetalk.http.api.HttpProtocolVersion;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.http.api.HttpServiceContext;
import io.servicetalk.http.api.ReservedBlockingHttpConnection;
import io.servicetalk.test.resources.DefaultTestCerts;
import io.servicetalk.transport.api.ClientSslConfigBuilder;
import io.servicetalk.transport.api.ServerContext;
import io.servicetalk.transport.api.ServerSslConfigBuilder;
import io.servicetalk.transport.netty.internal.ExecutionContextExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Stream;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.VerificationTestUtils.assertThrows;
import static io.servicetalk.http.api.HttpResponseStatus.OK;
import static io.servicetalk.http.api.HttpSerializers.textSerializerUtf8;
import static io.servicetalk.http.netty.AlpnIds.HTTP_1_1;
import static io.servicetalk.http.netty.AlpnIds.HTTP_2;
import static io.servicetalk.http.netty.BuilderUtils.newClientBuilder;
import static io.servicetalk.http.netty.BuilderUtils.newServerBuilder;
import static io.servicetalk.test.resources.DefaultTestCerts.serverPemHostname;
import static io.servicetalk.transport.api.SslProvider.OPENSSL;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

class AlpnClientAndServerTest {

    @RegisterExtension
    static final ExecutionContextExtension SERVER_CTX =
            ExecutionContextExtension.cached("server-io", "server-executor")
                    .setClassLevel(true);
    @RegisterExtension
    static final ExecutionContextExtension CLIENT_CTX =
            ExecutionContextExtension.cached("client-io", "client-executor")
                    .setClassLevel(true);

    private static final String PAYLOAD_BODY = "Hello World!";

    private ServerContext serverContext;
    private BlockingHttpClient client;
    @Nullable
    private HttpProtocolVersion expectedProtocol;
    private BlockingQueue<HttpServiceContext> serviceContext;
    private BlockingQueue<HttpProtocolVersion> requestVersion;
    private void setUp(List<String> serverSideProtocols,
                       List<String> clientSideProtocols,
                       @Nullable HttpProtocolVersion expectedProtocol) throws Exception {
        serverContext = startServer(serverSideProtocols);
        client = startClient(serverContext, clientSideProtocols);
        this.expectedProtocol = expectedProtocol;
    }

    @BeforeEach
    void beforeEach() {
        serviceContext = new LinkedBlockingDeque<>();
        requestVersion = new LinkedBlockingDeque<>();
    }

    @SuppressWarnings("unused")
    private static Stream<Arguments> clientExecutors() {
        return Stream.of(
                Arguments.of(asList(HTTP_2, HTTP_1_1), asList(HTTP_2, HTTP_1_1),
                        HttpProtocolVersion.HTTP_2_0, null, null),
                Arguments.of(asList(HTTP_2, HTTP_1_1), asList(HTTP_1_1, HTTP_2),
                        HttpProtocolVersion.HTTP_2_0, null, null),
                Arguments.of(asList(HTTP_2, HTTP_1_1), singletonList(HTTP_2),
                        HttpProtocolVersion.HTTP_2_0, null, null),
                Arguments.of(asList(HTTP_2, HTTP_1_1), singletonList(HTTP_1_1),
                        HttpProtocolVersion.HTTP_1_1, null, null),

                Arguments.of(asList(HTTP_1_1, HTTP_2), asList(HTTP_2, HTTP_1_1),
                        HttpProtocolVersion.HTTP_1_1, null, null),
                Arguments.of(asList(HTTP_1_1, HTTP_2), asList(HTTP_1_1, HTTP_2),
                        HttpProtocolVersion.HTTP_1_1, null, null),
                Arguments.of(asList(HTTP_1_1, HTTP_2), singletonList(HTTP_2),
                        HttpProtocolVersion.HTTP_2_0, null, null),
                Arguments.of(asList(HTTP_1_1, HTTP_2), singletonList(HTTP_1_1),
                        HttpProtocolVersion.HTTP_1_1, null, null),

                Arguments.of(singletonList(HTTP_2), asList(HTTP_2, HTTP_1_1),
                        HttpProtocolVersion.HTTP_2_0, null, null),
                Arguments.of(singletonList(HTTP_2), asList(HTTP_1_1, HTTP_2),
                        HttpProtocolVersion.HTTP_2_0, null, null),
                Arguments.of(singletonList(HTTP_2), singletonList(HTTP_2),
                        HttpProtocolVersion.HTTP_2_0, null, null),
                Arguments.of(singletonList(HTTP_2), singletonList(HTTP_1_1),
                        null, ClosedChannelException.class, null),

                Arguments.of(singletonList(HTTP_1_1), asList(HTTP_2, HTTP_1_1),
                        HttpProtocolVersion.HTTP_1_1, null, null),
                Arguments.of(singletonList(HTTP_1_1), asList(HTTP_1_1, HTTP_2),
                        HttpProtocolVersion.HTTP_1_1, null, null),
                Arguments.of(singletonList(HTTP_1_1), singletonList(HTTP_2),
                        null, ClosedChannelException.class, null),
                Arguments.of(singletonList(HTTP_1_1), singletonList(HTTP_1_1),
                        HttpProtocolVersion.HTTP_1_1, null, null));
    }

    private ServerContext startServer(List<String> supportedProtocols) throws Exception {
        return newServerBuilder(SERVER_CTX)
                .protocols(toProtocolConfigs(supportedProtocols))
                .sslConfig(new ServerSslConfigBuilder(DefaultTestCerts::loadServerPem,
                        DefaultTestCerts::loadServerKey).provider(OPENSSL).build())
                .listenBlocking((ctx, request, responseFactory) -> {
                    serviceContext.put(ctx);
                    requestVersion.put(request.version());
                    return responseFactory.ok().payloadBody(PAYLOAD_BODY, textSerializerUtf8());
                })
                .toFuture().get();
    }

    private static BlockingHttpClient startClient(ServerContext serverContext, List<String> supportedProtocols) {
        return newClientBuilder(serverContext, CLIENT_CTX)
                .protocols(toProtocolConfigs(supportedProtocols))
                .sslConfig(new ClientSslConfigBuilder(DefaultTestCerts::loadServerCAPem)
                        .peerHost(serverPemHostname()).provider(OPENSSL).build())
                .buildBlocking();
    }

    private static HttpProtocolConfig[] toProtocolConfigs(List<String> supportedProtocols) {
        return supportedProtocols.stream()
                .map(id -> {
                    switch (id) {
                        case HTTP_1_1:
                            return HttpProtocol.HTTP_1.config;
                        case HTTP_2:
                            return HttpProtocol.HTTP_2.config;
                        default:
                            throw new IllegalArgumentException("Unsupported protocol: " + id);
                    }
                }).toArray(HttpProtocolConfig[]::new);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            client.close();
        } finally {
            serverContext.close();
        }
    }

    @ParameterizedTest(name = "serverAlpnProtocols={0}, clientAlpnProtocols={1}," +
            " expectedProtocol={2}, expectedExceptionType={3}")
    @MethodSource("clientExecutors")
    void testAlpnConnection(List<String> serverSideProtocols,
                            List<String> clientSideProtocols,
                            @Nullable HttpProtocolVersion expectedProtocol,
                            @Nullable Class<? extends Throwable> expectedExceptionType,
                            @Nullable Class<? extends Throwable> optionalExceptionWrapperType) throws Exception {
        setUp(serverSideProtocols, clientSideProtocols, expectedProtocol);
        if (expectedExceptionType != null) {
            assertThrows(expectedExceptionType, optionalExceptionWrapperType, () -> client.request(client.get("/")));
            return;
        }

        try (ReservedBlockingHttpConnection connection = client.reserveConnection(client.get("/"))) {
            assertThat(connection.connectionContext().protocol(), is(expectedProtocol));
            assertThat(connection.connectionContext().sslConfig(), is(notNullValue()));
            assertThat(connection.connectionContext().sslSession(), is(notNullValue()));

            assertResponseAndServiceContext(connection.request(client.get("/")));
            // When using ALPN the request factory selection is deferred until after the connection is established, so
            // the protocol on the requests may be "http/1.x" but we may actually be speaking http/2. In either case
            // keep-alive should be enabled, the connection shouldn't be closed, and subsequent requests should succeed.
            assertResponseAndServiceContext(connection.request(client.get("/")));
        }
    }

    @ParameterizedTest(name = "serverAlpnProtocols={0}, clientAlpnProtocols={1}," +
            " expectedProtocol={2}, expectedExceptionType={3}")
    @MethodSource("clientExecutors")
    void testAlpnClient(List<String> serverSideProtocols,
                        List<String> clientSideProtocols,
                        @Nullable HttpProtocolVersion expectedProtocol,
                        @Nullable Class<? extends Throwable> expectedExceptionType,
                        @Nullable Class<? extends Throwable> optionalExceptionWrapperType) throws Exception {
        setUp(serverSideProtocols, clientSideProtocols, expectedProtocol);
        if (expectedExceptionType != null) {
            assertThrows(expectedExceptionType, optionalExceptionWrapperType, () -> client.request(client.get("/")));
        } else {
            assertResponseAndServiceContext(client.request(client.get("/")));
        }
    }

    private void assertResponseAndServiceContext(HttpResponse response) throws Exception {
        assertThat(response.version(), is(expectedProtocol));
        assertThat(response.status(), is(OK));
        assertThat(response.payloadBody(textSerializerUtf8()), is(PAYLOAD_BODY));

        HttpServiceContext serviceCtx = serviceContext.take();
        assertThat(serviceCtx.protocol(), is(expectedProtocol));
        assertThat(serviceCtx.sslSession(), is(notNullValue()));
        assertThat(serviceCtx.sslConfig(), is(notNullValue()));
        assertThat(requestVersion.take(), is(expectedProtocol));

        assertThat(serviceContext, is(empty()));
        assertThat(requestVersion, is(empty()));
    }
}
