package org.chobit.epubra.app.support.context;

/** 不抛 checked 异常的取消订阅句柄，让 try-with-resources 直接可用。 */
@FunctionalInterface
public interface Unsubscriber extends AutoCloseable {
    @Override
    void close(); // 不抛 Exception
}
