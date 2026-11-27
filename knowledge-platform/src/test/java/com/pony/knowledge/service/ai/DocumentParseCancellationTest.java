/* 验证取消会中断实际解析连接，且响应体只绑定自己的尝试。 */

package com.pony.knowledge.service.ai;

import com.pony.knowledge.resilience.AiAvailabilityGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentParseCancellationTest {
    /** 无论正在等响应头还是正文，取消后回环服务器都会看到 EOF 或复位。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(10)
    void cancelsRealHttpConnection(boolean sendHeaders) throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        var requestRead = new CountDownLatch(1);
        var responseTracked = new CountDownLatch(1);
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var disconnected = workers.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    int length = 0;
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring("content-length:".length()).trim());
                        }
                    }
                    assertTrue(length > 0);
                    for (int i = 0; i < length; i++) assertNotEquals(-1, input.read());
                    requestRead.countDown();
                    if (sendHeaders) {
                        byte[] partial = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                + "Content-Length: 1024\r\n\r\n{\"documentId\":")
                                .getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(partial);
                        socket.getOutputStream().flush();
                    }
                    try {
                        return input.read() == -1;
                    } catch (SocketException disconnectedSocket) {
                        return true;
                    }
                }
            });
            var guard = mock(AiAvailabilityGuard.class);
            var call = mock(AiAvailabilityGuard.Call.class);
            when(guard.acquire()).thenReturn(call);
            doAnswer(invocation -> {
                responseTracked.countDown();
                return null;
            }).when(call).success();
            var client = new AiServiceClient(RestClient.builder(), "http://127.0.0.1:" + server.getLocalPort(),
                    2_000, 8_000, 40_000, "test-token", null, guard);
            var cancellation = new DocumentParseCancellation();
            var parsing = workers.submit(() -> client.parseDocument(1L, "input.md", "fixtures", "md", cancellation));

            assertTrue(requestRead.await(4, TimeUnit.SECONDS));
            if (sendHeaders) assertTrue(responseTracked.await(3, TimeUnit.SECONDS));
            cancellation.cancel();
            assertThrows(ExecutionException.class, () -> parsing.get(3, TimeUnit.SECONDS));
            assertTrue(disconnected.get(3, TimeUnit.SECONDS));
            verify(call, never()).failure();
        } finally {
            workers.shutdownNow();
        }
    }

    /** 收到响应头后关闭登记的响应体，同一线程上的新尝试仍可运行。 */
    @Test
    void closesOnlyTheRegisteredResponseAndCancelsOnlyThisAttempt() throws Exception {
        var cancellation = new DocumentParseCancellation();
        var response = mock(ClientHttpResponse.class);
        var body = mock(InputStream.class);
        when(response.getBody()).thenReturn(body);
        cancellation.start();
        assertTrue(cancellation.trackResponse(response));
        cancellation.cancel();
        cancellation.finish();

        verify(body).close();
        verify(response, never()).close();
        assertThrows(CancellationException.class, cancellation::start);
        assertFalse(Thread.currentThread().isInterrupted());
        var next = new DocumentParseCancellation();
        next.start();
        assertFalse(next.isCancelled());
        next.finish();
    }
}
