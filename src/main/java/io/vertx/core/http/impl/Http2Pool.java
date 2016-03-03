/*
 * Copyright (c) 2011-2013 The original author or authors
 *  ------------------------------------------------------
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.core.http.impl;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2EventAdapter;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.impl.ContextImpl;
import io.vertx.core.net.NetSocket;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
class Http2Pool extends ConnectionManager.Pool {

  private VertxClientHandler clientHandler;

  public Http2Pool(ConnectionManager.ConnQueue queue) {
    super(queue, 1);
  }

  public boolean getConnection(HttpClientRequestImpl req, Handler<HttpClientStream> handler, ContextImpl context) {
    if (clientHandler != null) {
      if (context == null) {
        context = clientHandler.context;
      } else if (context != clientHandler.context) {
        ConnectionManager.log.warn("Reusing a connection with a different context: an HttpClient is probably shared between different Verticles");
      }
      context.runOnContext(v -> {
        clientHandler.handle(handler, req);
      });
      return true;
    } else {
      return false;
    }
  }

  void createConn(ChannelHandlerContext handlerCtx, ContextImpl context, int port, String host, Channel ch, HttpClientRequestImpl req, Handler<HttpClientStream> connectHandler,
                          Handler<Throwable> exceptionHandler) {
    ChannelPipeline p = ch.pipeline();
    Http2Connection connection = new DefaultHttp2Connection(false);
    VertxClientHandlerBuilder clientHandlerBuilder = new VertxClientHandlerBuilder(handlerCtx, context);
    synchronized (queue) {
      VertxClientHandler handler = clientHandlerBuilder.build(connection);
      handler.decoder().frameListener(handler);
      clientHandler = handler;
      p.addLast(handler);
      handler.handle(connectHandler, req);
      // Todo :  limit according to the max concurrency of the stream
      ConnectionManager.Waiter waiter;
      while ((waiter = queue.getNextWaiter()) != null) {
        handler.handle(waiter.handler, waiter.req);
      }
    }
  }

  @Override
  void closeAllConnections() {
    // todo
  }

  class Http2ClientStream implements HttpClientStream {

    private final HttpClientRequestImpl req;
    private final ChannelHandlerContext context;
    private final Http2Connection conn;
    private final int id;
    private final Http2ConnectionEncoder encoder;
    private HttpClientResponseImpl resp;

    public Http2ClientStream(HttpClientRequestImpl req,
                             ChannelHandlerContext context,
                             Http2Connection conn,
                             Http2ConnectionEncoder encoder) {
      this.req = req;
      this.context = context;
      this.conn = conn;
      this.id = conn.local().incrementAndGetNextStreamId();
      this.encoder = encoder;
    }

    void handleHeaders(Http2Headers headers, boolean end) {
      resp = new HttpClientResponseImpl(
          req,
          HttpVersion.HTTP_2,
          this,
          Integer.parseInt(headers.status().toString()),
          "todo",
          new Http2HeadersAdaptor(headers)
      );
      req.handleResponse(resp);
      if (end) {
        handleEnd();
      }
    }

    void handleData(ByteBuf chunk, boolean end) {
      if (chunk.isReadable()) {
        Buffer buff = Buffer.buffer(chunk.slice());
        resp.handleChunk(buff);
      }
      if (end) {
        handleEnd();
      }
    }

    private void handleEnd() {
      // Should use an shared immutable object ?
      resp.handleEnd(new CaseInsensitiveHeaders());
    }

    @Override
    public void writeHead(HttpMethod method, String uri, MultiMap headers, boolean chunked) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void writeHeadWithContent(HttpMethod method, String uri, MultiMap headers, boolean chunked, ByteBuf buf, boolean end) {
      Http2Headers h = new DefaultHttp2Headers();
      h.method(method.name());
      h.path(uri);
      h.scheme("https");
      encoder.writeHeaders(context, id, h, 0, end, context.newPromise());
      context.flush();
    }
    @Override
    public void writeBuffer(ByteBuf buf, boolean end) {
      throw new UnsupportedOperationException();
    }
    @Override
    public String hostHeader() {
      throw new UnsupportedOperationException();
    }
    @Override
    public Context getContext() {
      throw new UnsupportedOperationException();
    }
    @Override
    public void doSetWriteQueueMaxSize(int size) {
      throw new UnsupportedOperationException();
    }
    @Override
    public boolean isNotWritable() {
      throw new UnsupportedOperationException();
    }
    @Override
    public void handleInterestedOpsChanged() {
      throw new UnsupportedOperationException();
    }
    @Override
    public void endRequest() {
    }
    @Override
    public void doPause() {
      throw new UnsupportedOperationException();
    }
    @Override
    public void doResume() {
      throw new UnsupportedOperationException();
    }
    @Override
    public void reportBytesWritten(long numberOfBytes) {
    }
    @Override
    public void reportBytesRead(long s) {
    }
    @Override
    public NetSocket createNetSocket() {
      throw new UnsupportedOperationException();
    }
  }

  class VertxClientHandler extends Http2ConnectionHandler implements Http2FrameListener {

    private final ChannelHandlerContext handlerCtx;
    private final ContextImpl context;
    private final IntObjectMap<Http2ClientStream> streams = new IntObjectHashMap<>();

    public VertxClientHandler(
        ChannelHandlerContext handlerCtx,
        ContextImpl context,
        Http2ConnectionDecoder decoder,
        Http2ConnectionEncoder encoder,
        Http2Settings initialSettings) {
      super(decoder, encoder, initialSettings);
      this.handlerCtx = handlerCtx;
      this.context = context;
    }

    void handle(Handler<HttpClientStream> handler, HttpClientRequestImpl req) {
      Http2ClientStream stream = createStream(req);
      handler.handle(stream);
    }

    Http2ClientStream createStream(HttpClientRequestImpl req) {
      Http2ClientStream stream = new Http2ClientStream(req, handlerCtx, connection(), encoder());
      streams.put(stream.id, stream);
      return stream;
    }

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
      Http2ClientStream stream = streams.get(streamId);
      stream.handleData(data, endOfStream);
      return data.readableBytes() + padding;
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endOfStream) throws Http2Exception {
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endOfStream) throws Http2Exception {
      Http2ClientStream stream = streams.get(streamId);
      stream.handleHeaders(headers, endOfStream);
    }

    @Override
    public void onPriorityRead(ChannelHandlerContext ctx, int streamId, int streamDependency, short weight, boolean exclusive) throws Http2Exception {
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) throws Http2Exception {
    }

    @Override
    public void onSettingsAckRead(ChannelHandlerContext ctx) throws Http2Exception {
    }

    @Override
    public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) throws Http2Exception {
    }

    @Override
    public void onPingRead(ChannelHandlerContext ctx, ByteBuf data) throws Http2Exception {
    }

    @Override
    public void onPingAckRead(ChannelHandlerContext ctx, ByteBuf data) throws Http2Exception {
    }

    @Override
    public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId, Http2Headers headers, int padding) throws Http2Exception {
    }

    @Override
    public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) throws Http2Exception {
    }

    @Override
    public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) throws Http2Exception {
    }

    @Override
    public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId, Http2Flags flags, ByteBuf payload) throws Http2Exception {
    }
  }

  class VertxClientHandlerBuilder extends AbstractHttp2ConnectionHandlerBuilder<VertxClientHandler, VertxClientHandlerBuilder> {

    private final ChannelHandlerContext handlerCtx;
    private final ContextImpl context;

    public VertxClientHandlerBuilder(ChannelHandlerContext handlerCtx, ContextImpl context) {
      this.handlerCtx = handlerCtx;
      this.context = context;
    }

    @Override
    protected VertxClientHandler build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder, Http2Settings initialSettings) throws Exception {
      return new VertxClientHandler(handlerCtx, context, decoder, encoder, initialSettings);
    }

    public VertxClientHandler build(Http2Connection conn) {
      connection(conn);
      initialSettings(new Http2Settings());
      frameListener(new Http2EventAdapter() {
        @Override
        public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
          return super.onDataRead(ctx, streamId, data, padding, endOfStream);
        }
      });
      return super.build();
    }
  }
}