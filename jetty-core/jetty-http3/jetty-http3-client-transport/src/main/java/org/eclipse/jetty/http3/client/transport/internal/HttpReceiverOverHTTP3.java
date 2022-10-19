//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.client.transport.internal;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.SerializedInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpReceiverOverHTTP3 extends HttpReceiver implements Stream.Client.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpReceiverOverHTTP3.class);

    private final AtomicReference<Runnable> contentActionRef = new AtomicReference<>();
    private ContentSource contentSource;
    private Content.Chunk contentGenerated;

    protected HttpReceiverOverHTTP3(HttpChannelOverHTTP3 channel)
    {
        super(channel);
    }

    @Override
    protected void reset()
    {
        contentActionRef.set(null);
        contentSource = null;
        super.reset();
    }

    @Override
    protected Content.Source newContentSource()
    {
        contentSource = new ContentSource();
        return contentSource;
    }

    private class ContentSource implements Content.Source
    {
        private final SerializedInvoker invoker = new SerializedInvoker();
        private volatile Content.Chunk currentChunk;
        private volatile Runnable demandCallback;

        @Override
        public Content.Chunk read()
        {
            Content.Chunk chunk = consumeCurrentChunk();
            if (chunk != null)
                return chunk;
            currentChunk = HttpReceiverOverHTTP3.this.read(false);
            return consumeCurrentChunk();
        }

        public void onDataAvailable()
        {
            if (demandCallback != null)
                invoker.run(this::invokeDemandCallback);
        }

        private Content.Chunk consumeCurrentChunk()
        {
            if (currentChunk != null)
            {
                Content.Chunk rc = currentChunk;
                if (!(rc instanceof Content.Chunk.Error))
                    currentChunk = currentChunk.isLast() ? Content.Chunk.EOF : null;
                return rc;
            }
            return null;
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            if (demandCallback == null)
                throw new IllegalArgumentException();
            if (this.demandCallback != null)
                throw new IllegalStateException();
            this.demandCallback = demandCallback;

            invoker.run(this::meetDemand);
        }

        private void meetDemand()
        {
            while (true)
            {
                if (currentChunk != null)
                {
                    invoker.run(this::invokeDemandCallback);
                    break;
                }
                else
                {
                    currentChunk = HttpReceiverOverHTTP3.this.read(true);
                    if (currentChunk == null)
                        return;
                }
            }
        }

        private void invokeDemandCallback()
        {
            Runnable demandCallback = this.demandCallback;
            this.demandCallback = null;
            if (demandCallback != null)
            {
                try
                {
                    demandCallback.run();
                }
                catch (Throwable x)
                {
                    fail(x);
                }
            }
        }

        @Override
        public void fail(Throwable failure)
        {
            if (currentChunk != null)
            {
                currentChunk.release();
                failAndClose(failure);
            }
            currentChunk = Content.Chunk.from(failure);
        }
    }

    private Content.Chunk read(boolean fillInterestIfNeeded)
    {
        Content.Chunk chunk = consumeContentGenerated();
        if (chunk != null)
            return chunk;
        HttpConnectionOverHTTP3 httpConnection = getHttpChannel().getHttpConnection();
//        boolean contentGenerated = httpConnection.parseAndFill();
//        if (!contentGenerated && fillInterestIfNeeded)
//        {
//            if (!httpConnection.isFillInterested())
//                httpConnection.fillInterested();
//        }
        return consumeContentGenerated();
    }

    private Content.Chunk consumeContentGenerated()
    {
        Content.Chunk chunk = this.contentGenerated;
        this.contentGenerated = null;
        return chunk;
    }

    void content(Content.Chunk chunk)
    {
        if (contentGenerated != null)
            throw new IllegalStateException();
        contentGenerated = chunk;

        if (contentSource == null)
        {
            Runnable r = contentActionRef.getAndSet(null);
            if (r != null)
                r.run();
        }
        else
        {
            contentSource.onDataAvailable();
        }
    }

    @Override
    protected HttpChannelOverHTTP3 getHttpChannel()
    {
        return (HttpChannelOverHTTP3)super.getHttpChannel();
    }

    protected void receive()
    {
        // Called when the application resumes demand of content.
        if (LOG.isDebugEnabled())
            LOG.debug("resuming response processing on {}", this);

        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        getHttpChannel().getStream().demand();
    }

    @Override
    public void onResponse(Stream.Client stream, HeadersFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        HttpResponse httpResponse = exchange.getResponse();
        MetaData.Response response = (MetaData.Response)frame.getMetaData();
        httpResponse.version(response.getHttpVersion()).status(response.getStatus()).reason(response.getReason());

        responseBegin(exchange);
        HttpFields headers = response.getFields();
        for (HttpField header : headers)
        {
            responseHeader(exchange, header);
        }

        // TODO: add support for HttpMethod.CONNECT.

        responseHeaders(exchange);
    }

    @Override
    public void onDataAvailable(Stream.Client stream)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        Stream.Data data = stream.readData();
        if (data != null)
        {
            ByteBuffer byteBuffer = data.getByteBuffer();
            if (byteBuffer.hasRemaining())
            {
                Callback callback = Callback.from(Invocable.InvocationType.NON_BLOCKING, data::release, x ->
                {
                    responseFailure(x, Promise.from(failed ->
                    {
                        if (failed)
                            stream.reset(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x);
                    }, t -> stream.reset(HTTP3ErrorCode.INTERNAL_ERROR.code(), x)));
                });

                // TODO Stream.Data data is lost here
                boolean proceed = false; //responseContent(exchange, callback);
                if (proceed)
                {
                    if (data.isLast())
                        responseSuccess(exchange);
                    else
                        stream.demand();
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("stalling response processing, no demand after {} on {}", data, this);
                }
            }
            else
            {
                data.release();
                if (data.isLast())
                    responseSuccess(exchange);
                else
                    stream.demand();
            }
        }
        else
        {
            stream.demand();
        }
    }

    @Override
    public void onTrailer(Stream.Client stream, HeadersFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        HttpFields trailers = frame.getMetaData().getFields();
        trailers.forEach(exchange.getResponse()::trailer);

        responseSuccess(exchange);
    }

    @Override
    public void onIdleTimeout(Stream.Client stream, Throwable failure, Promise<Boolean> promise)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            exchange.abort(failure, Promise.from(aborted -> promise.succeeded(!aborted), promise::failed));
        else
            promise.succeeded(false);
    }

    @Override
    public void onFailure(Stream.Client stream, long error, Throwable failure)
    {
        responseFailure(failure, Promise.noop());
    }

    private void failAndClose(Throwable failure)
    {
        responseFailure(failure, Promise.from(failed -> getHttpChannel().getHttpConnection().close(failure),
                x -> getHttpChannel().getHttpConnection().close(failure)));
    }
}
