package org.jboss.resteasy.client.jaxrs.engines;

import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.HttpMethod;
import org.jboss.resteasy.client.jaxrs.internal.ClientConfiguration;
import org.jboss.resteasy.client.jaxrs.internal.ClientInvocation;
import org.jboss.resteasy.client.jaxrs.internal.ClientRequestHeaders;
import org.jboss.resteasy.client.jaxrs.internal.ClientResponse;
import org.jboss.resteasy.tracing.RESTEasyTracingLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.resources.ConnectionProvider;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.client.ResponseProcessingException;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static java.util.Objects.requireNonNull;
import static org.jboss.resteasy.util.HttpHeaderNames.CONTENT_LENGTH;

public class ReactorNettyClientHttpEngine implements AsyncClientHttpEngine {

    private static final Logger log = LoggerFactory.getLogger(ReactorNettyClientHttpEngine.class);

    private final HttpClient httpClient;
    private final ChannelGroup channelGroup;
    private final ConnectionProvider connectionProvider;
    private final Optional<Duration> requestTimeout;

    /**
     * Constructor for ReactorNettyClientHttpEngine
     *
     * @param httpClient The {@link HttpClient} instance to be used by this {@link AsyncClientHttpEngine}
     * @param channelGroup The {@link ChannelGroup} instance used by the provided {@link HttpClient}
     * @param connectionProvider The {@link ConnectionProvider} instance used to create the provided {@link HttpClient}
     * @param requestTimeout The {@link Optional<Duration>} instance used to configure requestTimeout on response
     */
    private ReactorNettyClientHttpEngine(final HttpClient httpClient,
                                        final ChannelGroup channelGroup,
                                        final ConnectionProvider connectionProvider,
                                        final Optional<Duration> requestTimeout) {
        this.httpClient = requireNonNull(httpClient);
        this.channelGroup = requireNonNull(channelGroup);
        this.connectionProvider = requireNonNull(connectionProvider);
        this.requestTimeout = requireNonNull(requestTimeout);

        requestTimeout
                .ifPresent( duration -> {
                    if(duration.isNegative())
                        throw new IllegalArgumentException("Required positive value for requestTimeout");
                    if(duration.isZero())
                        throw new IllegalArgumentException("Required non zero value for requestTimeout");
                });
    }

    public ReactorNettyClientHttpEngine(final HttpClient httpClient,
                                        final ChannelGroup channelGroup,
                                        final ConnectionProvider connectionProvider) {
        this(httpClient, channelGroup, connectionProvider, Optional.empty());
    }

    public ReactorNettyClientHttpEngine(final HttpClient httpClient,
                                        final ChannelGroup channelGroup,
                                        final ConnectionProvider connectionProvider,
                                        final Duration requestTimeout) {
        this(httpClient, channelGroup, connectionProvider, Optional.of(requestTimeout));
    }

    @Override
    public <T> Future<T> submit(final ClientInvocation request,
                                final boolean buffered,
                                final InvocationCallback<T> callback,
                                final ResultExtractor<T> extractor) {

        return submit(request, buffered, extractor, null)
                .whenComplete((response, throwable) -> {
                    if(callback != null) {
                        if (throwable != null) callback.failed(throwable);
                        else callback.completed(response);
                    }
                });
    }

    @Override
    public <T> CompletableFuture<T> submit(final ClientInvocation request,
                                           final boolean buffered,
                                           final ResultExtractor<T> extractor,
                                           final ExecutorService executorService) {

        final Optional<byte[]> payload =
            Optional.ofNullable(request.getEntity()).map(entity -> requestContent(request));

        final HttpClient.RequestSender requestSender =
                httpClient
                        .headers(headerBuilder -> {
                            final ClientRequestHeaders resteasyHeaders = request.getHeaders();
                            resteasyHeaders.getHeaders().entrySet().forEach(entry -> {
                                final String key = entry.getKey();
                                final List<Object> valueList = entry.getValue();
                                valueList.forEach(value -> headerBuilder.add(key, value != null ? value : ""));
                            });

                            payload.ifPresent(bytes -> {

                                headerBuilder.set(CONTENT_LENGTH, bytes.length);

                                if (log.isDebugEnabled() &&
                                    isContentLengthInvalid(resteasyHeaders.getHeader(CONTENT_LENGTH), bytes)) {
                                    log.debug("The request's Content-Length header is replaced " +
                                            " by the size of the byte array computed from the request entity.");
                                }
                            });
                        })
                        .request(HttpMethod.valueOf(request.getMethod()))
                        .uri(request.getUri().toString());

        // Please see https://github.com/reactor/reactor-netty/issues/585 to see why
        // we do not use outbound.sendObject(object) API.
        final HttpClient.ResponseReceiver<?> responseReceiver =
            payload.<HttpClient.ResponseReceiver<?>>map(bytes -> requestSender.send(
                (httpClientRequest, outbound) ->
                    outbound.sendObject(Mono.just(outbound.alloc().buffer().writeBytes(bytes))))
            ).orElse(requestSender);

        final Mono<T> responseMono =  responseReceiver
                .responseSingle((response, bytes) -> bytes
                        .asInputStream()
                        .map(is -> extractResult(request.getClientConfiguration(), response, is, extractor))
                        .switchIfEmpty(
                                Mono.defer(
                                        () -> Mono.just(
                                                extractResult(
                                                        request.getClientConfiguration(),
                                                        response,
                                                        null,
                                                        extractor)))));

        return requestTimeout
                .map(duration -> responseMono.timeout(duration))
                .orElse(responseMono)
                .toFuture();
    }

    private static boolean isContentLengthInvalid(final String headerValue, final byte[] payload) {

        try {
            return headerValue != null && Long.parseLong(headerValue) != payload.length;
        } catch (Exception e) {
            log.warn("Problem parsing the Content-Length header value.", e);
        }
        return true;
    }

    @Override
    public SSLContext getSslContext() {
        // reactor-netty does not allow to access the ssl-context from HttpClient API.
        throw new UnsupportedOperationException();
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
        // reactor-netty does not support HostnameVerifier API.
        throw new UnsupportedOperationException();
    }

    @Override
    public Response invoke(Invocation request) {
        final Future<ClientResponse> future = submit((ClientInvocation) request, false, null, response -> response);

        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            throw clientException(e, null);
        } catch (ExecutionException e) {
            throw clientException(e.getCause(), null);
        }
    }

    @Override
    public void close() {
        try {
            channelGroup.close().await();
        } catch (InterruptedException e) {
            log.warn("Exception while closing Netty ChannelGroup", e);
            // What can we do other than swallowing?
        } finally {
            connectionProvider.disposeLater().block();
        }
    }

    static RuntimeException clientException(Throwable ex, Response clientResponse) {
        RuntimeException ret;
        if (ex == null) {
            ret = new ProcessingException(new NullPointerException());
        } else if (ex instanceof WebApplicationException) {
            ret = (WebApplicationException) ex;
        } else if (ex instanceof ProcessingException) {
            ret = (ProcessingException) ex;
        } else if (clientResponse != null) {
            ret = new ResponseProcessingException(clientResponse, ex);
        } else {
            ret = new ProcessingException(ex);
        }
        return ret;
    }

    private static byte[] requestContent(ClientInvocation request)
    {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        request.getDelegatingOutputStream().setDelegate(baos);
        try {
            request.writeRequestBody(request.getEntityStream());
            baos.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write the request body!", e);
        }
    }

    private <T> T extractResult(final ClientConfiguration clientConfiguration,
                                final HttpClientResponse reactorNettyResponse,
                                final InputStream inputStream,
                                final ResultExtractor<T> extractor) {

        return extractor.extractResult(toRestEasyResponse(clientConfiguration, reactorNettyResponse, inputStream));
    }

    private ClientResponse toRestEasyResponse(final ClientConfiguration clientConfiguration,
                                              final HttpClientResponse reactorNettyResponse,
                                              final InputStream inputStream) {

        class RestEasyClientResponse extends ClientResponse {

            private InputStream is;

            RestEasyClientResponse(final ClientConfiguration configuration, final InputStream is) {
                super(configuration, RESTEasyTracingLogger.empty());
                this.is = is;
            }

            @Override
            protected InputStream getInputStream() {
                return this.is;
            }

            @Override
            protected void setInputStream(InputStream inputStream) {
                this.is = inputStream;
            }

            @Override
            public void releaseConnection() throws IOException {
                this.releaseConnection(false);
            }

            @Override
            public void releaseConnection(boolean consumeInputStream) throws IOException {
                try {
                    if (is != null) {
                        if (consumeInputStream) {
                            while (is.available() > 0) {
                                is.read();
                            }
                        }
                        is.close();
                    }
                }
                catch (IOException e) {
                    // Swallowing because other ClientHttpEngine implementations are swallowing as well.
                    // What is better?  causing a potential leak with inputstream slowly or cause an unexpected
                    // and unhandled io error and potentially cause the service go down?
                    log.warn("Exception while releasing the connection!", e);
                }
            }
        }

        final ClientResponse restEasyClientResponse = new RestEasyClientResponse(clientConfiguration, inputStream);
        restEasyClientResponse.setStatus(reactorNettyResponse.status().code());

        final MultivaluedMap<String, Object> resteasyHeaders =  restEasyClientResponse.getHeaders();
        reactorNettyResponse.responseHeaders()
                .forEach(header -> resteasyHeaders.add(header.getKey(), header.getValue()));

        return restEasyClientResponse;
    }
}