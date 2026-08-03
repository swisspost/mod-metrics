package org.swisspush.metrics;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Class MetricsTests
 */
@RunWith(VertxUnitRunner.class)
public class MetricsTests {

    Vertx vertx;
    String address = "org.swisspush.metrics";

    private final String COUNTER = "test.counter";
    private final String GAUGE = "test.gauge";
    private final String OK = "ok";

    @Before
    public void setUp(TestContext context) {
        vertx = Vertx.vertx();
        vertx.deployVerticle("org.swisspush.metrics.MetricsModule", context.asyncAssertSuccess());
    }

    @Test
    public void testCounterInc(TestContext context){
        Async async = context.async();
        eventBusSend(incOperation(COUNTER), event -> {
            eventBusSend(countersOperation(), event1 -> {
                context.assertEquals(OK, extractStatus(event1));
                context.assertEquals(1, event1.result().body().getJsonObject(COUNTER).getInteger("count"));

                // Reset to 0
                eventBusSend(decOperation(COUNTER), event2 -> {
                    async.complete();
                });
            });
        });
    }

    @Test
    public void testCounterInc4(TestContext context){
        Async async = context.async();
        eventBusSend(incOperation(COUNTER, 4), event -> {
           eventBusSend(countersOperation(), reply -> {
               context.assertEquals(OK, extractStatus(reply));
               context.assertEquals(4, reply.result().body().getJsonObject(COUNTER).getInteger("count"));

               // Reset to 0
               eventBusSend(decOperation(COUNTER, 4), event2 -> {
                   async.complete();
               });
           });
        });
    }

    @Test
    public void testCounterDec(TestContext context) {
        Async async = context.async();
        eventBusSend(decOperation(COUNTER), event -> {
            eventBusSend(countersOperation(), reply -> {
                context.assertEquals(OK, extractStatus(reply));
                context.assertEquals(-1, reply.result().body().getJsonObject(COUNTER).getInteger("count"));

                // Reset to 0
                eventBusSend(incOperation(COUNTER), event1 -> {
                    async.complete();
                });
            });
        });
    }

    @Test
    public void testCounterDec4(TestContext context) {
        Async async = context.async();
        eventBusSend(decOperation(COUNTER, 4), event -> {
            eventBusSend(countersOperation(), reply -> {
                context.assertEquals(OK, extractStatus(reply));
                context.assertEquals(-4, reply.result().body().getJsonObject(COUNTER).getInteger("count"));

                // Reset to 0
                eventBusSend(incOperation(COUNTER, 4), event1 -> {
                    async.complete();
                });
            });
        });
    }

    @Test
    public void testGauges(TestContext context) {
        Async async = context.async();
        eventBusSend(setOperation(GAUGE, 1234), event -> {
            eventBusSend(gaugesOperation(), reply -> {
                context.assertEquals(OK, extractStatus(reply));
                context.assertEquals(1234, reply.result().body().getJsonObject(GAUGE).getInteger("value"));

                eventBusSend(setOperation(GAUGE, 5678), event1 -> {
                    eventBusSend(gaugesOperation(), reply2 -> {
                        context.assertEquals(OK, extractStatus(reply2));
                        context.assertEquals(5678, reply2.result().body().getJsonObject(GAUGE).getInteger("value"));

                        eventBusSend(removeOperation(GAUGE), event2 -> {
                            async.complete();
                        });
                    });
                });
            });
        });
    }

    @Test
    public void testBatchAllSuccess(TestContext context) {
        Async async = context.async();
        JsonObject batch = batchOperation(
                incOperation(COUNTER),
                incOperation(COUNTER, 4),
                setOperation(GAUGE, 42)
        );
        eventBusSend(batch, event -> {
            context.assertEquals(OK, extractStatus(event));
            JsonArray results = event.result().body().getJsonArray("results");
            context.assertEquals(3, results.size());
            for (int i = 0; i < results.size(); i++) {
                context.assertEquals(OK, results.getJsonObject(i).getString("status"));
            }

            eventBusSend(countersOperation(), reply -> {
                context.assertEquals(5, reply.result().body().getJsonObject(COUNTER).getInteger("count"));

                eventBusSend(gaugesOperation(), reply2 -> {
                    context.assertEquals(42, reply2.result().body().getJsonObject(GAUGE).getInteger("value"));

                    // Reset state
                    eventBusSend(decOperation(COUNTER, 5), r1 ->
                        eventBusSend(removeOperation(GAUGE), r2 -> async.complete()));
                });
            });
        });
    }

    @Test
    public void testBatchWithError(TestContext context) {
        Async async = context.async();
        JsonObject batch = batchOperation(
                incOperation(COUNTER),
                buildOperation("invalidAction")
        );
        eventBusSend(batch, event -> {
            context.assertEquals("error", extractStatus(event));
            JsonArray results = event.result().body().getJsonArray("results");
            context.assertEquals(2, results.size());
            context.assertEquals(OK, results.getJsonObject(0).getString("status"));
            context.assertEquals("error", results.getJsonObject(1).getString("status"));

            // Reset state - the valid sub-message still gets applied
            eventBusSend(decOperation(COUNTER), r -> async.complete());
        });
    }

    @Test
    public void testBatchMissingMessagesArray(TestContext context) {
        Async async = context.async();
        JsonObject batch = new JsonObject().put("action", "batch");
        eventBusSend(batch, event -> {
            context.assertEquals("error", extractStatus(event));
            async.complete();
        });
    }

    @Test
    public void testBatchEmpty(TestContext context) {
        Async async = context.async();
        JsonObject batch = batchOperation();
        eventBusSend(batch, event -> {
            context.assertEquals(OK, extractStatus(event));
            JsonArray results = event.result().body().getJsonArray("results");
            context.assertEquals(0, results.size());
            async.complete();
        });
    }

    @Test
    public void testBatchWithNonObjectItem(TestContext context) {
        Async async = context.async();
        JsonArray metrics = new JsonArray().add(incOperation(COUNTER)).add("not-an-object");
        JsonObject batch = new JsonObject().put("action", "batch").put("metrics", metrics);
        eventBusSend(batch, event -> {
            context.assertEquals("error", extractStatus(event));
            JsonArray results = event.result().body().getJsonArray("results");
            context.assertEquals(2, results.size());
            context.assertEquals(OK, results.getJsonObject(0).getString("status"));
            context.assertEquals("error", results.getJsonObject(1).getString("status"));
            context.assertNotNull(results.getJsonObject(1).getString("message"));

            // Reset state - the valid sub-message still gets applied
            eventBusSend(decOperation(COUNTER), r -> async.complete());
        });
    }

    @Test
    public void testBatchWithInternalExceptionUsesFallbackMessage(TestContext context) {
        Async async = context.async();
        // "update" on a histogram without the required "n" causes an NPE (null Integer
        // unboxing) inside the action handler, exercising the null-message fallback path.
        JsonObject batch = batchOperation(buildOperation("histogram.name", "update"));
        eventBusSend(batch, event -> {
            context.assertEquals("error", extractStatus(event));
            JsonArray results = event.result().body().getJsonArray("results");
            context.assertEquals(1, results.size());
            context.assertEquals("error", results.getJsonObject(0).getString("status"));
            context.assertNotNull(results.getJsonObject(0).getString("message"));
            async.complete();
        });
    }

    private String extractStatus(AsyncResult<Message<JsonObject>> reply){
        return reply.result().body().getString("status");
    }

    private JsonObject countersOperation(){
        return buildOperation("counters");
    }

    private JsonObject gaugesOperation(){
        return buildOperation("gauges");
    }

    private JsonObject incOperation(String name){
        return buildOperation(name, "inc");
    }

    private JsonObject incOperation(String name, int n){
        JsonObject op = incOperation(name);
        op.put("n", n);
        return op;
    }

    private JsonObject removeOperation(String name){
        return buildOperation(name, "remove");
    }

    private JsonObject setOperation(String name){
        return buildOperation(name, "set");
    }

    private JsonObject setOperation(String name, int n){
        JsonObject op = setOperation(name);
        op.put("n", n);
        return op;
    }

    private JsonObject decOperation(String name){
        return buildOperation(name, "dec");
    }

    private JsonObject decOperation(String name, int n){
        JsonObject op = decOperation(name);
        op.put("n", n);
        return op;
    }

    private JsonObject buildOperation(String name, String action){
        JsonObject op = buildOperation(action);
        op.put("name", name);
        return op;
    }

    private JsonObject buildOperation(String action){
        JsonObject op = new JsonObject();
        op.put("action", action);
        return op;
    }

    private JsonObject batchOperation(JsonObject... operations){
        JsonArray metrics = new JsonArray();
        for (JsonObject operation : operations) {
            metrics.add(operation);
        }
        return new JsonObject().put("action", "batch").put("metrics", metrics);
    }

    private void eventBusSend(JsonObject operation, Handler<AsyncResult<Message<JsonObject>>> handler){
        vertx.eventBus().request(address, operation, handler);
    }
}
