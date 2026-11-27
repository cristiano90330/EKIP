/* 将一次文档尝试的租约失效信号送给它自己的解析 HTTP 请求。 */

package com.pony.knowledge.service.ai;

import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.concurrent.CancellationException;

/** 只在本次解析调用期间绑定执行线程和响应体。 */
public final class DocumentParseCancellation {
    private boolean cancelled;
    private Thread worker;
    private ClientHttpResponse response;
    private boolean interruptedForCancellation;

    /** 租约续租确认失效后取消解析。已完成的请求不会影响下一次尝试。 */
    public void cancel() {
        ClientHttpResponse body;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            body = response;
            if (body == null && worker != null && !worker.isInterrupted()) {
                // JDK 请求工厂在等候响应头时收到中断，会取消尚未返回的 HTTP 调用。
                interruptedForCancellation = true;
                worker.interrupt();
            }
        }
        if (body != null) abortResponse(body);
    }

    /** 关闭底层读取流，避免响应封装先尝试把未完成的正文读完。 */
    static void abortResponse(ClientHttpResponse response) {
        try {
            response.getBody().close();
        } catch (IOException ignored) {
            // 正常的请求清理流程仍会关闭响应。
        }
    }

    /** 返回租约续租线程是否已确认本次尝试无法继续。 */
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** 在解析调用的工作线程上登记传输。已取消时不启动新请求。 */
    synchronized void start() {
        if (cancelled) throw new CancellationException("文档解析尝试已取消");
        worker = Thread.currentThread();
    }

    /** 响应头到达后登记响应体，让取消可解除阻塞的正文读取。 */
    synchronized boolean trackResponse(ClientHttpResponse body) {
        if (cancelled) return false;
        response = body;
        return true;
    }

    /** 注销当前传输，并在工作线程返回数据库流程前消费本次取消产生的中断。 */
    synchronized void finish() {
        worker = null;
        response = null;
        if (interruptedForCancellation) Thread.interrupted();
    }
}
