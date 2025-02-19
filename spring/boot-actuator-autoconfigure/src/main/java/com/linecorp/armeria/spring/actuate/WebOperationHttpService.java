/*
 * Copyright 2019 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.spring.actuate;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.InvocationContext;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.WebOperation;
import org.springframework.boot.actuate.endpoint.web.reactive.AbstractWebFluxEndpointHandlerMapping;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthStatusHttpMapper;
import org.springframework.core.io.Resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * {@link HttpService} to handle a {@link WebOperation}. Mostly inspired by reactive implementation in
 * {@link AbstractWebFluxEndpointHandlerMapping}.
 */
final class WebOperationHttpService implements HttpService {

    private static final Pattern FILENAME_BAD_CHARS = Pattern.compile("['/\\\\?%*:|\"<> ]");

    private static final Logger logger = LoggerFactory.getLogger(WebOperationHttpService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    private final WebOperation operation;
    private final HealthStatusHttpMapper healthMapper;

    WebOperationHttpService(WebOperation operation,
                            HealthStatusHttpMapper healthMapper) {
        this.operation = operation;
        this.healthMapper = healthMapper;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        final CompletableFuture<HttpResponse> resFuture = new CompletableFuture<>();
        req.aggregate().handle((aggregatedReq, t) -> {
            if (t != null) {
                resFuture.completeExceptionally(t);
                return null;
            }
            if (operation.isBlocking()) {
                ctx.blockingTaskExecutor().execute(() -> invoke(ctx, aggregatedReq, resFuture));
            } else {
                invoke(ctx, aggregatedReq, resFuture);
            }
            return null;
        });
        return HttpResponse.from(resFuture);
    }

    private void invoke(ServiceRequestContext ctx,
                        AggregatedHttpRequest req,
                        CompletableFuture<HttpResponse> resFuture) {
        final Map<String, Object> arguments = getArguments(ctx, req);
        final Object result = operation.invoke(new InvocationContext(SecurityContext.NONE, arguments));

        try {
            final HttpResponse res = handleResult(ctx, result, req.method());
            resFuture.complete(res);
        } catch (IOException e) {
            resFuture.completeExceptionally(e);
        }
    }

    private static Map<String, Object> getArguments(ServiceRequestContext ctx, AggregatedHttpRequest req) {
        final Map<String, Object> arguments = new LinkedHashMap<>(ctx.pathParams());
        if (!req.content().isEmpty()) {
            final Map<String, Object> bodyParams;
            try {
                bodyParams = OBJECT_MAPPER.readValue(req.content().array(), JSON_MAP);
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid JSON in request.");
            }
            arguments.putAll(bodyParams);
        }
        final String query = ctx.query();
        if (query != null) {
            final QueryStringDecoder queryStringDecoder = new QueryStringDecoder(query, false);
            queryStringDecoder.parameters().forEach(
                    (key, values) -> arguments.put(key, values.size() != 1 ? values : values.get(0)));
        }
        return ImmutableMap.copyOf(arguments);
    }

    private HttpResponse handleResult(ServiceRequestContext ctx,
                                      @Nullable Object result, HttpMethod method) throws IOException {
        if (result == null) {
            return HttpResponse.of(method != HttpMethod.GET ? HttpStatus.NO_CONTENT : HttpStatus.NOT_FOUND);
        }

        final HttpStatus status;
        final Object body;
        if (result instanceof WebEndpointResponse) {
            final WebEndpointResponse<?> webResult = (WebEndpointResponse<?>) result;
            status = HttpStatus.valueOf(webResult.getStatus());
            body = webResult.getBody();
        } else {
            if (result instanceof Health) {
                status = HttpStatus.valueOf(healthMapper.mapStatus(((Health) result).getStatus()));
            } else {
                status = HttpStatus.OK;
            }
            body = result;
        }

        final MediaType contentType = firstNonNull(ctx.negotiatedResponseMediaType(), MediaType.JSON_UTF_8);
        final String contentSubType = contentType.subtype();

        if ("json".equals(contentSubType) || contentSubType.endsWith("+json")) {
            return HttpResponse.of(status, contentType, OBJECT_MAPPER.writeValueAsBytes(body));
        }

        if (body instanceof CharSequence) {
            return HttpResponse.of(status, contentType, (CharSequence) body);
        }

        if (body instanceof Resource) {
            final Resource resource = (Resource) body;
            final String filename = resource.getFilename();
            final HttpResponseWriter res = HttpResponse.streaming();
            final long length = resource.contentLength();
            final ResponseHeadersBuilder headers = ResponseHeaders.builder(status);
            headers.contentType(contentType);
            headers.setLong(HttpHeaderNames.CONTENT_LENGTH, length);
            headers.setTimeMillis(HttpHeaderNames.LAST_MODIFIED, resource.lastModified());
            if (filename != null) {
                headers.set(HttpHeaderNames.CONTENT_DISPOSITION,
                            "attachment;filename=" + FILENAME_BAD_CHARS.matcher(filename).replaceAll("_"));
            }

            res.write(headers.build());

            boolean success = false;
            ReadableByteChannel in = null;
            try {
                in = resource.readableChannel();
                final ReadableByteChannel finalIn = in;
                ctx.blockingTaskExecutor().execute(() -> streamResource(ctx, res, finalIn, length));
                success = true;
                return res;
            } finally {
                if (!success && in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        logger.warn("{} Failed to close an actuator resource: {}", ctx, resource, e);
                    }
                }
            }
        }

        logger.warn("{} Cannot convert an actuator response: {}", ctx, body);
        return HttpResponse.of(status, contentType, body.toString());
    }

    // TODO(trustin): A lot of duplication with StreamingHttpFile. Need to add some utility classes for
    //                streaming a ReadableByteChannel and an InputStream.
    private static void streamResource(ServiceRequestContext ctx, HttpResponseWriter res,
                                       ReadableByteChannel in, long remainingBytes) {

        final int chunkSize = (int) Math.min(8192, remainingBytes);
        final ByteBuf buf = ctx.alloc().buffer(chunkSize);
        final int readBytes;
        boolean success = false;
        try {
            readBytes = read(in, buf);
            if (readBytes < 0) {
                // Should not reach here because we only read up to the end of the stream.
                // If reached, it may mean the stream has been truncated.
                throw new EOFException();
            }
            success = true;
        } catch (Exception e) {
            close(res, in, e);
            return;
        } finally {
            if (!success) {
                buf.release();
            }
        }

        final long nextRemainingBytes = remainingBytes - readBytes;
        final boolean endOfStream = nextRemainingBytes == 0;
        if (readBytes > 0) {
            if (!res.tryWrite(new ByteBufHttpData(buf, endOfStream))) {
                close(in);
                return;
            }
        } else {
            buf.release();
        }

        if (endOfStream) {
            close(res, in);
            return;
        }

        res.onDemand(() -> {
            try {
                ctx.blockingTaskExecutor().execute(() -> streamResource(ctx, res, in, nextRemainingBytes));
            } catch (Exception e) {
                close(res, in, e);
            }
        });
    }

    private static int read(ReadableByteChannel src, ByteBuf dst) throws IOException {
        if (src instanceof ScatteringByteChannel) {
            return dst.writeBytes((ScatteringByteChannel) src, dst.writableBytes());
        }

        final int readBytes = src.read(dst.nioBuffer(dst.writerIndex(), dst.writableBytes()));
        if (readBytes > 0) {
            dst.writerIndex(dst.writerIndex() + readBytes);
        }
        return readBytes;
    }

    private static void close(HttpResponseWriter res, Closeable in) {
        close(in);
        res.close();
    }

    private static void close(HttpResponseWriter res, Closeable in, Exception cause) {
        close(in);
        res.close(cause);
    }

    private static void close(Closeable in) {
        try {
            in.close();
        } catch (Exception e) {
            logger.warn("Failed to close a stream for: {}", in, e);
        }
    }
}
