/* 核对模型 HTTP 客户端的连接、响应与期限处理。 */

package com.pony.knowledge.modelmanagement;

import com.pony.knowledge.request.RequestIdContext;
import org.junit.jupiter.api.Test;
import java.io.Closeable;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelHttpDeadlineTest {
    /** 通过 {@code ModelHttpDeadline.outerTimeout} 执行本例并核对输出或记录。 */
    @Test void budgetHasOnlyOneTransportMargin() {
        assertEquals(35000,ModelHttpDeadline.outerTimeout(Map.of("budgetMs",30000),120000));
        assertEquals(40000,ModelHttpDeadline.outerTimeout(null,40000));
    }

    /** 断言抛出 {@code HttpTimeoutException}。调用路径经过 {@code closed.set}。 */
    @Test void timedOutOperationIsInterruptedAndItsBodyClosed() throws Exception {
        AtomicBoolean interrupted=new AtomicBoolean();AtomicBoolean closed=new AtomicBoolean();
        AtomicReference<Closeable> body=new AtomicReference<>(() -> closed.set(true));
        CountDownLatch entered=new CountDownLatch(1),exit=new CountDownLatch(1);
        assertThrows(HttpTimeoutException.class,() -> ModelHttpDeadline.run(150,() -> {
            entered.countDown();
            try {new CountDownLatch(1).await();}catch(InterruptedException e){interrupted.set(true);throw e;}finally{exit.countDown();}
            return null;
        },body));
        assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(exit.await(1,TimeUnit.SECONDS));
        assertTrue(interrupted.get());assertTrue(closed.get());
    }

    /** 核对 {@code ModelExecutionContext.currentOwnerId()} 为空。调用路径经过 {@code ModelExecutionContext.open}。 */
    @Test void deadlineWorkerCarriesExplicitModelOwnerAndRequestId() throws Exception {
        try(ModelExecutionContext ignored=ModelExecutionContext.open(Map.of("budgetMs",1000),9L);
            RequestIdContext.Scope request=RequestIdContext.open("deadline-request")) {
            assertEquals("9:deadline-request",ModelHttpDeadline.run(1000,
                    () -> ModelExecutionContext.currentOwnerId()+":"+RequestIdContext.current(),null));
        }
        assertNull(ModelExecutionContext.currentOwnerId());assertNull(RequestIdContext.current());
    }
}
