/* 验证受管模型执行上下文的绑定、嵌套和恢复。 */

package com.pony.knowledge.modelmanagement;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelExecutionContextTest {
    /** 断言抛出 {@code UnsupportedOperationException}。调用路径经过 {@code bindings.put}。 */
    @Test void snapshotIsDeeplyImmutableAndNestedScopeRestoresOwner() {
        Map<String,Object> mutable = new LinkedHashMap<>();
        Map<String,Object> bindings = new LinkedHashMap<>();
        bindings.put("agent",new LinkedHashMap<>(Map.of("id","chosen")));
        mutable.put("bindings",bindings);
        try (ModelExecutionContext ignored=ModelExecutionContext.open(mutable,11L)) {
            bindings.clear();
            assertFalse(((Map<?,?>)ModelExecutionContext.current().get("bindings")).isEmpty());
            assertThrows(UnsupportedOperationException.class,() -> ModelExecutionContext.current().put("x","y"));
            try (ModelExecutionContext nested=ModelExecutionContext.open(Map.of("id","nested"),22L)) {
                assertEquals(22L,ModelExecutionContext.currentOwnerId());
            }
            assertEquals(11L,ModelExecutionContext.currentOwnerId());
        }
        assertNull(ModelExecutionContext.current());
        assertNull(ModelExecutionContext.currentOwnerId());
    }

    /** 核对 {@code seen.get()} 与预期 {@code 19L} 一致。调用路径经过 {@code Executors.newSingleThreadExecutor}。 */
    @Test void callableSupplierAndRunnableCarryExplicitOwnerAndCleanWorker() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Callable<Long> wrapped;
            java.util.function.Supplier<Long> supplier;
            Runnable runnable;
            java.util.concurrent.atomic.AtomicReference<Long> seen=new java.util.concurrent.atomic.AtomicReference<>();
            try (ModelExecutionContext ignored=ModelExecutionContext.open(Map.of("budgetMs",30000),19L)) {
                wrapped=ModelExecutionContext.wrap(ModelExecutionContext::currentOwnerId);
                supplier=ModelExecutionContext.wrapSupplier(ModelExecutionContext::currentOwnerId);
                runnable=ModelExecutionContext.wrapRunnable(() -> seen.set(ModelExecutionContext.currentOwnerId()));
            }
            assertEquals(19L,executor.submit(wrapped).get(2,TimeUnit.SECONDS));
            assertEquals(19L,CompletableFuture.supplyAsync(supplier,executor).get(2,TimeUnit.SECONDS));
            executor.submit(runnable).get(2,TimeUnit.SECONDS);
            assertEquals(19L,seen.get());
            assertNull(executor.submit(ModelExecutionContext::currentOwnerId).get(2,TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
}
