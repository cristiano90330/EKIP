/* 核对受管流式请求的期限计算、中止和终态。 */

package com.pony.knowledge.modelmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.llm.AgentGatewayClient;
import com.pony.knowledge.agent.llm.AgentGatewayProperties;
import com.pony.knowledge.resilience.AiAvailabilityGuard;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ManagedStreamingDeadlineTest {
    /** 断言抛出 {@code AgentGatewayClient.AgentGatewayException}。调用路径经过 {@code HttpServer.create}。 */
    @Test void continuedHeartbeatBytesCannotKeepTheBodyAliveBeyondItsTotalBudget() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        CountDownLatch bodyStarted=new CountDownLatch(1),bodyClosed=new CountDownLatch(1);
        server.createContext("/agent/decide/stream",exchange -> {
            exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try {
                bodyStarted.countDown();
                for(int i=0;i<100;i++) {
                    exchange.getResponseBody().write(": alive\n\n".getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();
                    try {Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
                }
            } catch(Exception closed) {bodyClosed.countDown();} finally {exchange.close();}
        });
        server.start();
        try {
            AgentGatewayClient client=new AgentGatewayClient(AgentGatewayProperties.builder()
                    .baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).connectTimeoutMs(1000).readTimeoutMs(350).build(),
                    new ObjectMapper(),"test-internal-token",null,AiAvailabilityGuard.disabled(new SimpleMeterRegistry()));
            AtomicInteger terminal=new AtomicInteger();long start=System.nanoTime();
            assertThrows(AgentGatewayClient.AgentGatewayException.class,() -> client.postSse("/agent/decide/stream",Map.of(),
                    (event,data) -> {if(event.equals("final"))terminal.incrementAndGet();}));
            assertTrue(bodyStarted.await(1,TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1800);
            assertEquals(0,terminal.get());assertTrue(bodyClosed.await(2,TimeUnit.SECONDS));
        } finally {server.stop(0);}
    }
}
