package com.indeed.imhotep.shardmaster.rpc;

import com.indeed.imhotep.shardmaster.ShardMasterExecutors;
import com.indeed.imhotep.shardmaster.protobuf.ShardMasterRequest;
import com.indeed.imhotep.shardmaster.protobuf.ShardMasterResponse;
import com.indeed.util.core.io.Closeables2;
import org.apache.log4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;

/**
 * @author kenh
 */

public class RequestResponseServer implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(RequestResponseServer.class);

    private final RequestHandler requestHandler;
    private final ExecutorService requestHandlerExecutor;
    private final ServerSocket serverSocket;

    public RequestResponseServer(final int port, final RequestHandler requestHandler, final int numThreads) throws IOException {
        this.requestHandler = requestHandler;
        requestHandlerExecutor = ShardMasterExecutors.newBlockingFixedThreadPool(numThreads);
        serverSocket = new ServerSocket(port);
    }

    public int getActualPort() {
        if (serverSocket.isClosed()) {
            return 0;
        } else {
            return serverSocket.getLocalPort();
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        requestHandlerExecutor.shutdown();
    }

    public void run() throws IOException {
        while (!serverSocket.isClosed()) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (final SocketException e) {
                if ("Socket closed".equals(e.getMessage())) {
                    LOGGER.warn("Shutting down due to server socket closed");
                    break;
                }
                throw e;
            }

            final ShardMasterRequest request;
            try {
                socket.setTcpNoDelay(true); // disable nagle
                request = ShardMasterMessageUtil.receiveRequest(socket.getInputStream());
            } catch (final IOException e) {
                LOGGER.error("Error while reading request", e);
                Closeables2.closeQuietly(socket, LOGGER);
                continue;
            }

            requestHandlerExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        Iterable<ShardMasterResponse> responses;
                        try {
                            responses = requestHandler.handleRequest(request);
                        } catch (final Throwable e) {
                            LOGGER.error("Failed to handle request " + request, e);
                            responses = Collections.singletonList(ShardMasterResponse.newBuilder()
                                    .setResponseCode(ShardMasterResponse.ResponseCode.ERROR)
                                    .setErrorMessage(e.getMessage())
                                    .build());
                        }

                        try {
                            for (final ShardMasterResponse response : responses) {
                                ShardMasterMessageUtil.sendMessage(response, socket.getOutputStream());
                            }
                        } catch (final IOException e) {
                            LOGGER.error("Error while responding to request "+ request, e);
                        }
                    } finally {
                        Closeables2.closeQuietly(socket, LOGGER);
                    }
                }
            });
        }
    }
}