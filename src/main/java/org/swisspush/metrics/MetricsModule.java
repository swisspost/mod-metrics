/*
 * Copyright 2012-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.swisspush.metrics;

import com.codahale.metrics.*;
import com.codahale.metrics.Timer.Context;
import com.codahale.metrics.jmx.JmxReporter;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MetricsModule extends AbstractVerticle implements Handler<Message<JsonObject>> {

    private MetricRegistry metrics ;
    private String address ;
    private Map<String,Context> timers ;
    private ConcurrentMap<String,Integer> gauges ;
    private JsonObject config;

    private Logger logger = LoggerFactory.getLogger(MetricsModule.class);

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("Starting MetricsModule");
        config = config();
        address = getOptionalStringConfig( "address", "org.swisspush.metrics" ) ;
        String registryName = getOptionalStringConfig( "registryName", null ) ;

        metrics = new MetricRegistry() ;
        if (registryName != null) {
            MetricRegistry other = SharedMetricRegistries.add(registryName, metrics);
            if (other != null) {
                metrics = other;
            }
        }
        timers = new HashMap<>() ;
        gauges = new ConcurrentHashMap<>() ;
        JmxReporter.forRegistry( metrics )
                .createsObjectNamesWith(new SimpleObjectNameFactory())
                .build().start() ;

        logger.info("Register consumer for event bus address '" + address + "'");
        vertx.eventBus().consumer( address, this ) ;

        startPromise.complete();
    }

    private static Integer getOptionalInteger( JsonObject obj, String name, Integer def ) {
        Integer result = obj.getInteger( name ) ;
        return result == null ? def : result ;
    }

    public void handle( final Message<JsonObject> message ) {
        if(message.body() == null){
            sendError( message, "message body must be specified" ) ;
            return;
        }

        final JsonObject body = message.body() ;

        if( isBatch( body ) ) {
            handleBatch( message, body ) ;
        } else {
            handleSingle( message, body ) ;
        }
    }

    /**
     * Checks whether the given message body describes a batch message
     * (i.e. the "action" field is "batch" and it contains multiple sub-messages
     * to be processed in one go) as opposed to a normal, single-action message.
     * Note: "batch" is therefore a reserved action value and cannot be used as a
     * regular single-message action.
     */
    private boolean isBatch( final JsonObject body ) {
        return "batch".equals( body.getString( "action" ) ) ;
    }

    /**
     * Processes every sub-message contained in the "metrics" array of a batch message
     * and replies with the aggregated per-item results.
     * <p>
     * <b>Note:</b> batches are <i>not</i> atomic. Sub-messages are applied one after
     * another as they are encountered, so if a later sub-message fails or is invalid,
     * any earlier sub-messages in the same batch will already have been applied. A
     * reply with {@code status=error} therefore does not mean the batch had no effect;
     * check the individual "results" entries to see which sub-messages succeeded.
     */
    private void handleBatch( final Message<JsonObject> message, final JsonObject body ) {
        final JsonArray metrics = body.getJsonArray( "metrics" ) ;
        if( metrics == null ) {
            sendError( message, "metrics array must be specified for a batch message" ) ;
            return;
        }

        logger.debug("Handling batch message with " + metrics.size() + " sub-message(s)");

        JsonArray results = new JsonArray() ;
        boolean hasError = false ;
        for( Object item : metrics ) {
            JsonObject result ;
            if( item instanceof JsonObject ) {
                result = processAction( (JsonObject) item ) ;
            } else {
                result = new JsonObject().put( "status", "error" )
                        .put( "message", "batch item must be a JSON object" ) ;
            }
            if( "error".equals( result.getString( "status" ) ) ) {
                hasError = true ;
            }
            results.add( result ) ;
        }

        JsonObject reply = new JsonObject().put( "results", results ) ;
        sendStatus( hasError ? "error" : "ok", message, reply ) ;
    }

    private void handleSingle( final Message<JsonObject> message, final JsonObject body ) {
        JsonObject result = processAction( body ) ;

        if( "error".equals( result.getString( "status" ) ) ) {
            sendError( message, result.getString( "message" ) ) ;
            return;
        }

        result.remove( "status" ) ;
        sendOK( message, result.isEmpty() ? null : result ) ;
    }

    /**
     * Processes a single action described by the given body and returns a JsonObject
     * containing a "status" field ("ok" or "error"), an optional "message" field
     * describing the error, and any additional data produced by the action (e.g. for
     * the "gauges", "counters", "histograms", "meters" and "timers" actions).
     */
    private JsonObject processAction( final JsonObject body ) {
        if( body == null ) {
            return new JsonObject().put( "status", "error" ).put( "message", "message body must be specified" ) ;
        }

        final String action = body.getString( "action" ) ;
        final String name   = body.getString( "name" ) ;

        logger.debug("Handling message with action '"+action+"' and name '"+name+"'");

        if( action == null ) {
            return new JsonObject().put( "status", "error" ).put( "message", "action must be specified" ) ;
        }

        try {
            switch( action ) {
                // set a gauge
                case "set" :
                    return setGauge(name, body);

                // increment a counter
                case "inc" :
                    return incrementCounter(name, body);

                // decrement a counter
                case "dec" :
                    return decrementCounter(name, body);

                // Mark a meter
                case "mark" :
                    return markMeter(name);

                // Update a histogram
                case "update" :
                    return updateHistogram(name, body);

                // Start a timer
                case "start" :
                    return startTimer(name);

                // Stop a timer
                case "stop" :
                    return stopTimer(name);

                // Remove a metric if it exists
                case "remove" :
                    return removeMetric(name);

                case "gauges" :
                    return collectGauges();

                case "counters" :
                    return collectCounters();

                case "histograms" :
                    return collectHistograms();

                case "meters" :
                    return collectMeters();

                case "timers" :
                    return collectTimers();

                default:
                    return new JsonObject().put( "status", "error" ).put( "message", "Invalid action : " + action ) ;
            }
        } catch (Exception e) {
            logger.error("Error while processing action '"+action+"' and name '"+name+"'", e);
            String message = e.getMessage() != null ? e.getMessage() : e.toString() ;
            return new JsonObject().put( "status", "error" ).put( "message", message ) ;
        }
    }

    private JsonObject setGauge(String name, JsonObject body){
        Integer n = body.getInteger( "n" ) ;
        logger.debug("setting gauge with name '"+name+"' and value " + n);
        gauges.put( name, n ) ;
        if( metrics.getMetrics().get( name ) == null ) {
            metrics.register( name, (Gauge<Integer>) () -> gauges.get( name )) ;
        }
        return new JsonObject().put( "status", "ok" ) ;
    }

    private JsonObject incrementCounter(String name, JsonObject body){
        Integer value = getOptionalInteger( body, "n", 1 );
        logger.debug("incrementing counter with name '"+name+"' by " + value);
        metrics.counter( name ).inc( value ) ;
        return new JsonObject().put( "status", "ok" ) ;
    }

    private JsonObject decrementCounter(String name, JsonObject body){
        Integer value = getOptionalInteger( body, "n", 1 );
        logger.debug("decrementing counter with name '"+name+"' by " + value);
        metrics.counter( name ).dec( value ) ;
        return new JsonObject().put( "status", "ok" ) ;
    }

    private JsonObject markMeter(String name){
        metrics.meter( name ).mark() ;
        logger.debug("marking meter with name '"+name+"'");
        return new JsonObject().put( "status", "ok" ) ;
    }

    private JsonObject updateHistogram(String name, JsonObject body){
        Integer value = body.getInteger("n");
        logger.debug("updating histogram with name '"+name+"' and value " + value);
        metrics.histogram( name ).update( value ) ;
        return new JsonObject().put( "status", "ok" ) ;
    }

    private JsonObject startTimer(String name){
        logger.debug("starting timer with name '"+name+"'");
        timers.put( name, metrics.timer( name ).time() ) ;
        return new JsonObject().put( "status", "ok" ) ;
    }

    private JsonObject stopTimer(String name){
        logger.debug("stopping timer with name '"+name+"'");
        Context c = timers.remove( name ) ;
        if( c != null ) {
            c.stop() ;
        }
        return new JsonObject().put( "status", "ok" ) ;
    }

    private JsonObject removeMetric(String name){
        logger.debug("removing metric with name '"+name+"'");
        metrics.remove( name ) ;
        gauges.remove( name ) ;
        return new JsonObject().put( "status", "ok" ) ;
    }

    private JsonObject collectGauges(){
        JsonObject reply = new JsonObject() ;
        for( Entry<String,Gauge> entry : metrics.getGauges().entrySet() ) {
            reply.put( entry.getKey(),
                    serialiseGauge( entry.getValue(), new JsonObject() ) ) ;
        }
        logger.debug("getting values for gauges. reply with " + reply.encode());
        return reply.put( "status", "ok" ) ;
    }

    private JsonObject collectCounters(){
        JsonObject reply = new JsonObject() ;
        for( Entry<String,Counter> entry : metrics.getCounters().entrySet() ) {
            reply.put( entry.getKey(),
                    serialiseCounting( entry.getValue(),
                            new JsonObject() ) ) ;
        }
        logger.debug("getting values for counters. reply with " + reply.encode());
        return reply.put( "status", "ok" ) ;
    }

    private JsonObject collectHistograms(){
        JsonObject reply = new JsonObject() ;
        for( Entry<String,Histogram> entry : metrics.getHistograms().entrySet() ) {
            reply.put( entry.getKey(),
                    serialiseSampling( entry.getValue(),
                            serialiseCounting( entry.getValue(),
                                    new JsonObject() ) ) ) ;
        }
        logger.debug("getting values for histograms. reply with " + reply.encode());
        return reply.put( "status", "ok" ) ;
    }

    private JsonObject collectMeters(){
        JsonObject reply = new JsonObject() ;
        for( Entry<String,Meter> entry : metrics.getMeters().entrySet() ) {
            reply.put( entry.getKey(),
                    serialiseMetered( entry.getValue(),
                            new JsonObject() ) ) ;
        }
        logger.debug("getting values for meters. reply with " + reply.encode());
        return reply.put( "status", "ok" ) ;
    }

    private JsonObject collectTimers(){
        JsonObject reply = new JsonObject() ;
        for( Entry<String,Timer> entry : metrics.getTimers().entrySet() ) {
            reply.put( entry.getKey(),
                    serialiseSampling( entry.getValue(),
                            serialiseMetered( entry.getValue(),
                                    new JsonObject() ) ) ) ;
        }
        logger.debug("getting values for timers. reply with " + reply.encode());
        return reply.put( "status", "ok" ) ;
    }

    private JsonObject serialiseGauge( Gauge gauge, JsonObject ret ) {
        ret.put( "value", (Integer)gauge.getValue() ) ;
        return ret ;
    }

    private JsonObject serialiseCounting( Counting count, JsonObject ret ) {
        ret.put( "count", count.getCount() ) ;
        return ret ;
    }

    private JsonObject serialiseSampling( Sampling sample, JsonObject ret ) {
        Snapshot snap = sample.getSnapshot() ;
        ret.put( "min",    snap.getMin() ) ;
        ret.put( "max",    snap.getMax() ) ;
        ret.put( "median", snap.getMedian() ) ;
        ret.put( "mean",   snap.getMean() ) ;
        ret.put( "stddev", snap.getStdDev() ) ;
        ret.put( "size",   snap.size() ) ;
        ret.put( "75th",   snap.get75thPercentile() ) ;
        ret.put( "95th",   snap.get95thPercentile() ) ;
        ret.put( "98th",   snap.get98thPercentile() ) ;
        ret.put( "99th",   snap.get99thPercentile() ) ;
        ret.put( "999th",  snap.get999thPercentile() ) ;
        return ret ;
    }

    private JsonObject serialiseMetered( Metered meter, JsonObject ret ) {
        ret.put( "1m",    meter.getOneMinuteRate() ) ;
        ret.put( "5m",    meter.getFiveMinuteRate() ) ;
        ret.put( "15m",   meter.getFifteenMinuteRate() ) ;
        ret.put( "count", meter.getCount() ) ;
        ret.put( "mean",  meter.getMeanRate() ) ;
        return ret ;
    }

    private void sendError(Message<JsonObject> message, String error) {
        sendError(message, error, null);
    }

    private void sendError(Message<JsonObject> message, String error, Exception e) {
        logger.error(error, e);
        JsonObject json = new JsonObject().put("status", "error").put("message", error);
        message.reply(json);
    }

    private void sendOK(Message<JsonObject> message) {
        sendOK(message, null);
    }

    private void sendOK(Message<JsonObject> message, JsonObject json) {
        sendStatus("ok", message, json);
    }

    private void sendStatus(String status, Message<JsonObject> message, JsonObject json) {
        if (json == null) {
            json = new JsonObject();
        }
        json.put("status", status);
        if(message.replyAddress() != null) {
            logger.debug("replying message with status " + status);
        }
        message.reply(json);
    }

    private String getOptionalStringConfig(String fieldName, String defaultValue) {
        String s = config.getString(fieldName);
        return s == null ? defaultValue : s;
    }
}