package org.chobit.epubra.app.support;

import org.chobit.epubra.lib.validation.ValidationReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AppEventBus} 的契约测试：发布 / 订阅 / 取消订阅 / 异常隔离 / record 事件传递。
 */
class AppEventBusTest {

    @Test
    void publishesAndReceivesRecordEvent() {
        AppEventBus bus = new AppEventBus();
        List<String> received = new ArrayList<>();
        try (var sub = bus.subscribe(AppEventBus.BookLoadedEvent.class, e -> received.add("loaded"))) {
            bus.publish(new AppEventBus.BookLoadedEvent());
        }
        assertEquals(List.of("loaded"), received);
    }

    @Test
    void topicOverloadCarriesPayload() {
        AppEventBus bus = new AppEventBus();
        AtomicInteger received = new AtomicInteger();
        try (var sub = bus.subscribe("custom.topic", payload -> received.incrementAndGet())) {
            bus.publish("custom.topic", "payload");
            bus.publish("custom.topic", 42);
            bus.publish("other.topic", "ignored");
        }
        assertEquals(2, received.get());
    }

    @Test
    void unsubscribeStopsDelivery() {
        AppEventBus bus = new AppEventBus();
        AtomicInteger count = new AtomicInteger();
        var sub = bus.subscribe(AppEventBus.BookSavedEvent.class, e -> count.incrementAndGet());
        bus.publish(new AppEventBus.BookSavedEvent());
        bus.unsubscribe(AppEventBus.BookSavedEvent.class.getName(), e -> {});
        sub.close();
        bus.publish(new AppEventBus.BookSavedEvent());
        assertEquals(1, count.get(), "订阅关闭后不应再收到事件");
    }

    @Test
    void failingSubscriberDoesNotBreakOthers() {
        AppEventBus bus = new AppEventBus();
        AtomicInteger after = new AtomicInteger();
        try (var sub1 = bus.subscribe(AppEventBus.ThemeChangedEvent.class, e -> {
            throw new RuntimeException("boom");
        })) {
            try (var sub2 = bus.subscribe(AppEventBus.ThemeChangedEvent.class, e -> after.incrementAndGet())) {
                bus.publish(new AppEventBus.ThemeChangedEvent(Theme.LIGHT));
            }
        }
        assertEquals(1, after.get(), "前一个订阅者抛异常不应阻断后续订阅者");
    }

    @Test
    void validationCompletedCarriesReport() {
        AppEventBus bus = new AppEventBus();
        ValidationReport[] received = new ValidationReport[1];
        try (var sub = bus.subscribe(AppEventBus.ValidationCompletedEvent.class, e -> received[0] = e.report())) {
            ValidationReport report = ValidationReport.EMPTY;
            bus.publish(new AppEventBus.ValidationCompletedEvent(report));
        }
        assertEquals(ValidationReport.EMPTY, received[0]);
    }

    @Test
    void unknownTopicIsNoop() {
        AppEventBus bus = new AppEventBus();
        // 没订阅者也不应抛异常
        bus.publish("no.subscribers", "x");
        bus.publish(new AppEventBus.BookRestoredEvent());
        assertTrue(true, "no subscribers 时发布必须静默通过");
    }
}