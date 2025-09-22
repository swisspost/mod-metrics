package org.swisspush.metrics;

import com.codahale.metrics.jmx.ObjectNameFactory;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * keep the mbean name formated as io.dropwizard.metrics V 4.0.2
 */
public class SimpleObjectNameFactory implements ObjectNameFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleObjectNameFactory.class);

    @Override
    public ObjectName createName(String type, String domain, String name) {
        try {
            ObjectName objectName = new ObjectName(domain, "name", name);
            if (objectName.isPattern()) {
                objectName = new ObjectName(domain, "name", ObjectName.quote(name));
            }
            return objectName;
        } catch (MalformedObjectNameException e) {
            try {
                return new ObjectName(domain, "name", ObjectName.quote(name));
            } catch (MalformedObjectNameException e1) {
                LOGGER.warn("Unable to register {} {}", type, name, e1);
                throw new RuntimeException(e1);
            }
        }
    }
}
