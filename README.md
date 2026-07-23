# cpi_xslt_mpl_extension
# Useful Functions for XSLT in SAP Integration Suite (CPI)

## Overview

When migrating from SAP Process Orchestration (PO) or developing natively in SAP Integration Suite (Cloud Integration), developers often encounter a capability gap: **XSLT 2.0 / 3.0 transformations in CPI do not natively provide a mechanism to interact directly with the Message Processing Log (MPL)**.

In SAP PO, extension calls allowed logging directly to `MappingTrace` or `AuditLog`. In SAP Integration Suite, the **`MPLWriter`** Java utility bridges this gap. By utilizing OSGi reflection, it enables XSLT steps to:

- Add custom header properties (`addCustomHeaderProperty`)
- Store message body/payload attachments (`addAttachmentAsString`)
- Set text string properties for debugging/tracing (`setStringProperty`)

---

## Technical Architecture & Dependencies

The `MPLWriter` class interacts dynamically with the execution context passed into Java extension calls inside Saxon/XSLT steps.

### Build Requirements
- **Compile-Time Dependencies:** Only `org.osgi.core-6.0.0.jar` is required.
- **No SAP CPI runtime JARs** are needed in your local build environment.

---

## Java Implementation (`MPLWriter.java`)

Deploy this compiled Java class (`.jar` file or embedded class) into your Integration Flow archive or Script Collection.

```java
package com.sap.custom.xslt;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Utility class to interact with SAP CPI Message Processing Log (MPL) from XSLT mappings.
 */
public class MPLWriter {

    public static String addCustomHeaderProperty(Object mpl, String name, String value) throws Exception {
        return addCustomHeaderProperty(mpl, name, value, false);
    }

    public static String addCustomHeaderProperty(Object mpl, String name, String value, boolean debugEnabled) throws Exception {
        debug(mpl, debugEnabled, "START addCustomHeaderProperty name=" + name + ", value=" + value);
        
        if (mpl == null) {
            return "MPL context is null";
        }

        // Access UserDefinedAttributes (UDAs) dynamically via reflection
        Object rootMpl = mpl.getClass().getMethod("getRoot").invoke(mpl);
        ClassLoader loader = mpl.getClass().getClassLoader();
        
        Class<?> udaClass = loader.loadClass("com.sap.it.op.mpl.UserDefinedAttributeTypeV2");
        Class<?> keysClass = loader.loadClass("com.sap.it.op.mpl.MsgProcLogPropertyKeys");
        
        Object udaKey = keysClass.getField("TK_USER_DEFINED_ATTRIBUTES").get(null);
        Method getMethod = rootMpl.getClass().getMethod("get", udaKey.getClass());
        Object uda = getMethod.invoke(rootMpl, udaKey);

        Method grantAttribute = udaClass.getMethod("grantAttribute", String.class);
        @SuppressWarnings("unchecked")
        Set<String> values = (Set<String>) grantAttribute.invoke(uda, name);
        values.add(value);

        debug(mpl, debugEnabled, "UDA successfully updated: " + uda);
        return "OK";
    }

    public static String addAttachmentAsString(Object mpl, String name, String content, String mimeType, boolean debugEnabled) throws Exception {
        debug(mpl, debugEnabled, "START addAttachment name=" + name);
        
        if (mpl == null) {
            return "MPL context is null";
        }

        // Obtain OSGi bundle context dynamically via Message class loader
        Bundle bundle = FrameworkUtil.getBundle(mpl.getClass());
        Class<?> messageClass = mpl.getClass().getClassLoader().loadClass("com.sap.esb.camel.message.storage.api.Message");
        Object attachmentMessage = messageClass.getDeclaredConstructor().newInstance();

        /*
         * Set Attachment Headers
         */
        Map<String, String> headers = new HashMap<>();
        headers.put("SapAttachmentName", name);
        headers.put("SapAttachmentContentType", mimeType);
        messageClass.getMethod("setHeader", Map.class).invoke(attachmentMessage, headers);

        /*
         * Set Body Content
         */
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        debug(mpl, debugEnabled, "Attachment payload bytes=" + bytes.length);

        // Fetch Message ID & Step ID from root MPL
        Object messageGuid = getMplValue(mpl, "messageGuid");
        Object stepId = getMplValue(mpl, "stepId");

        messageClass.getMethod("setMplID", String.class).invoke(attachmentMessage, messageGuid.toString());
        if (stepId != null) {
            messageClass.getMethod("setStepID", String.class).invoke(attachmentMessage, stepId.toString());
        }

        /*
         * Tag Message as ATTACHMENT
         */
        Class<?> tagClass = messageClass.getClassLoader().loadClass("com.sap.esb.camel.message.storage.api.Message$Tag");
        Object attachmentTag = Enum.valueOf((Class<Enum>) tagClass, "ATTACHMENT");
        messageClass.getMethod("setTag", tagClass).invoke(attachmentMessage, attachmentTag);

        /*
         * Persist Attachment to Storage
         */
        Object messageStore = getService(bundle, "com.sap.esb.camel.message.storage.api.MessageStore");
        URI uri = (URI) messageStore.getClass().getMethod("add", messageClass, boolean.class).invoke(messageStore, attachmentMessage, true);
        debug(mpl, debugEnabled, "Attachment URI=" + uri);

        /*
         * Register Attachment URI in MPL Attachment List
         */
        Object attachments = getMplValue(mpl, "attachments");
        if (attachments != null) {
            Method addMethod = attachments.getClass().getMethod("add", Object.class);
            addMethod.invoke(attachments, uri);
        }

        return "OK";
    }

    private static Object getMplValue(Object mpl, String fieldName) throws Exception {
        Method getMethod = mpl.getClass().getMethod("get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1));
        return getMethod.invoke(mpl);
    }

    private static Object getService(Bundle bundle, String className) throws Exception {
        org.osgi.framework.BundleContext bc = bundle.getBundleContext();
        org.osgi.framework.ServiceReference<?> ref = bc.getServiceReference(className);
        return bc.getService(ref);
    }

    private static void debug(Object mpl, boolean enabled, String message) {
        if (enabled) {
            // Write debug statement to standard out / logs
            System.out.println("[MPLWriter DEBUG] " + message);
        }
    }
}
