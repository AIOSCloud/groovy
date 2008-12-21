package groovy.jmx.builder;

import groovy.lang.Closure;

import javax.management.*;
import javax.management.modelmbean.InvalidTargetObjectTypeException;
import javax.management.modelmbean.ModelMBeanInfo;
import javax.management.modelmbean.RequiredModelMBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class JmxBuilderModelMBean extends RequiredModelMBean implements NotificationListener {
    private List<String> methodListeners = new ArrayList<String>(0);
    private Object managedObject;

    public JmxBuilderModelMBean(Object objectRef) throws MBeanException, RuntimeOperationsException, InstanceNotFoundException, InvalidTargetObjectTypeException {
        super.setManagedResource(objectRef, "ObjectReference");
    }

    public JmxBuilderModelMBean() throws MBeanException, RuntimeOperationsException {
        super();
    }

    public JmxBuilderModelMBean(ModelMBeanInfo mbi) throws MBeanException, RuntimeOperationsException {
        super(mbi);
    }

    public synchronized void setManagedResource(Object obj) {
        managedObject = obj;
        try {
            super.setManagedResource(obj, "ObjectReference");
        } catch (Exception ex) {
            throw new JmxBuilderException(ex);
        }
    }

    /**
     * Registers listeners for operation calls (i.e. method, getter, and setter calls) when
     * invoked on this bean from the MBeanServer.  Descriptor should contain a map with layout
     * item -> [Map[methodListner:[target:"", tpe:"", callback:&amp;Closure], ... ,]]
     *
     * @param descriptor MetaMap descriptor containing description of operation call listeners
     */
    public void addOperationCallListeners(Map<String, Map> descriptor) {
        if (descriptor == null) return;
        for (Map.Entry<String, Map> item : descriptor.entrySet()) {
            // setup method listeners (such as attributeListener and Operation Listners)
            // item -> [Map[methodListner:[target:"", tpe:"", callback:&Closure], ... ,]]
            if (item.getValue().containsKey("methodListener")) {
                Map listener = (Map) item.getValue().get("methodListener");
                String target = (String) listener.get("target");
                methodListeners.add(target);
                String listenerType = (String) listener.get("type");
                listener.put("managedObject", this.managedObject);
                // register an attribute change notification listener with model mbean
                if (listenerType.equals("attributeChangeListener")) {
                    try {
                        this.addAttributeChangeNotificationListener(
                                AttributeChangedListener.getListner(), (String) listener.get("attribute"), listener
                        );
                    } catch (MBeanException e) {
                        throw new JmxBuilderException(e);
                    }
                }
                if (listenerType.equals("operationCallListener")) {
                    String eventType = "jmx.operation.call." + target;
                    NotificationFilterSupport filter = new NotificationFilterSupport();
                    filter.enableType(eventType);
                    this.addNotificationListener(JmxEventListener.getListner(), filter, listener);
                }
            }
        }
    }

    /**
     * Sets up event listeners for this MBean as described in the descriptor.
     * The descriptor contains a map with layout {item -> Map[event:"...", from:ObjectName, callback:&amp;Closure],...,}
     *
     * @param server     the MBeanServer is to be registered.
     * @param descriptor a map containing info about the event
     */
    public void addEventListeners(MBeanServer server, Map<String, Map> descriptor) {
        for (Map.Entry<String, Map> item : descriptor.entrySet()) {
            Map<String, Object> listener = item.getValue();

            // register with server
            ObjectName broadcaster = (ObjectName) listener.get("from");
            try {
                String eventType = (String) listener.get("event");

                if (eventType != null) {
                    NotificationFilterSupport filter = new NotificationFilterSupport();
                    filter.enableType(eventType);
                    server.addNotificationListener(broadcaster, JmxEventListener.getListner(), filter, listener);
                } else {
                    server.addNotificationListener(broadcaster, JmxEventListener.getListner(), null, listener);
                }
            } catch (InstanceNotFoundException e) {
                throw new JmxBuilderException(e);
            }
        }
    }

    @Override
    public Object invoke(String opName, Object[] opArgs, String[] signature) throws MBeanException, ReflectionException {
        Object result = super.invoke(opName, opArgs, signature);
        if (methodListeners.contains(opName)) {
            this.sendNotification(buildCallListenerNotification(opName));
        }
        return result;
    }

    public void handleNotification(Notification note, Object handback) {
        //System.out.println("Received note!");
    }

    private Notification buildCallListenerNotification(String target) {
        Notification note = new Notification(
                "jmx.operation.call." + target,
                this,
                NumberSequencer.getNextSequence(),
                System.currentTimeMillis()
        );

        return note;
    }


    private static class NumberSequencer {
        private static AtomicLong num;

        static {
            num = new AtomicLong(0);
        }

        public static long getNextSequence() {
            return num.incrementAndGet();
        }
    }

    private static class AttributeChangedListener implements NotificationListener {
        private static AttributeChangedListener listener;

        public static synchronized AttributeChangedListener getListner() {
            if (listener == null) {
                listener = new AttributeChangedListener();
            }
            return listener;
        }

        private AttributeChangedListener() {
        }

        public void handleNotification(Notification notification, Object handback) {
            AttributeChangeNotification note = (AttributeChangeNotification) notification;
            Map event = (Map) handback;
            if (event != null) {
                Object del = event.get("managedObject");
                Object callback = event.get("callback");
                if (callback != null && callback instanceof Closure) {
                    Closure closure = (Closure) callback;
                    closure.setDelegate(del);
                    if (closure.getMaximumNumberOfParameters() == 1)
                        closure.call(buildAttributeNotificationPacket(note));
                    else closure.call();
                }
            }
        }

        private static Map buildAttributeNotificationPacket(AttributeChangeNotification note) {
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("oldValue", note.getOldValue());
            result.put("newValue", note.getNewValue());
            result.put("attribute", note.getAttributeName());
            result.put("attributeType", note.getAttributeType());
            result.put("sequenceNumber", note.getSequenceNumber());
            result.put("timeStamp", note.getTimeStamp());
            return result;
        }
    }
}
