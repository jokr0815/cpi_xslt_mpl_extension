/*
 * Copyright (c) HO2 Systemberatung GmbH
 * Licensed under the MIT License.
 */
package script;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * Utility class to interact with SAP Integration Suite Message Processing Log (MPL)
 * dynamically from XSLT Java Extensions via OSGi and Reflection.
 * 
 * @Author HO2 Systemberatung GmbH
 */
public class MPLWriter {

	private static int debugCounter = 0;

	/**
	 * Sets a string property on the Message Processing Log.
	 */
	public static Object setStringProperty(Object mpl, String name, Object value) throws Exception {

		Object key = createTypedKey(mpl, name, String.class);
		return put(mpl, key, value);
	}

	/**
	 * Adds a custom header property (User Defined Attribute) to the MPL.
	 */
	public static String addCustomHeaderProperty(Object mpl, String name, String value) throws Exception {

		return addCustomHeaderProperty(mpl, name, value, false);
	}

	public static String addCustomHeaderProperty(Object mpl, String name, String value, boolean debugEnabled)
			throws Exception {

		debug(mpl, debugEnabled, "START addCustomHeaderProperty " + name + "=" + value);

		Object rootMpl = mpl.getClass().getMethod("getRoot").invoke(mpl);
		ClassLoader loader = mpl.getClass().getClassLoader();
		Class<?> udaClass = loader.loadClass("com.sap.it.op.mpl.UserDefinedAttributeTypeV2");
		Class<?> keysClass = loader.loadClass("com.sap.it.op.mpl.TypedMessageProcessingLogKeys");
		Object udaKey = keysClass.getField("TK_USER_DEFINED_ATTRIBUTES").get(null);
		Method get = rootMpl.getClass().getMethod("get", udaKey.getClass());
		Object uda = get.invoke(rootMpl, udaKey);

		if (uda == null) {

			uda = udaClass.getDeclaredConstructor().newInstance();

			rootMpl.getClass().getMethod("put", udaKey.getClass(), Object.class).invoke(rootMpl, udaKey, uda);
		}

		Method grantAttribute = udaClass.getMethod("grantAttribute", String.class);

		@SuppressWarnings("unchecked")
		Set<String> values = (Set<String>) grantAttribute.invoke(uda, name);

		values.add(value);
		debug(mpl, debugEnabled, "UDA final=" + uda);

		return "OK";
	}

	/**
	 * Adds an attachment to the Message Processing Log (Normal Mode).
	 */
	public static String addAttachmentAsString(Object mpl, String name, String content, String mimeType)
			throws Exception {

		return addAttachmentAsString(mpl, name, content, mimeType, false);
	}

	/**
	 * Adds an attachment to the Message Processing Log (Debug Mode).
	 */
	public static String addAttachmentAsString(Object mpl, String name, String content, String mimeType,
			boolean debugEnabled) throws Exception {
		
		debug(mpl, debugEnabled, "START addAttachment name=" + name);

		// Obtain OSGi bundle context dynamically via Message class loader
		Bundle bundle = FrameworkUtil.getBundle(Class.forName("com.sap.gateway.ip.core.customdev.util.Message"));
		debug(mpl, debugEnabled, "Bundle=" + bundle);

		BundleContext ctx = bundle.getBundleContext();
		ServiceReference<?> ref = ctx.getServiceReference("com.sap.esb.camel.message.storage.api.MessageStorageWrite");

		debug(mpl, debugEnabled, "MessageStorage reference=" + ref);

		if (ref == null) {

			return "MessageStorageWrite service not found";
		}

		Object messageStore = null;

		try {
			messageStore = ctx.getService(ref);
			Class<?> messageClass = messageStore.getClass().getClassLoader()
					.loadClass("com.sap.esb.camel.message.storage.api.Message");
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

			debug(mpl, debugEnabled, "Attachment bytes=" + bytes.length);

			Method setBody = null;

			for (Method m : messageClass.getMethods()) {
				if ("setBody".equals(m.getName()) && m.getParameterCount() == 1) {
					setBody = m;
					break;
				}
			}

			if (setBody == null) {
				throw new Exception("Message.setBody not found");
			}

			debug(mpl, debugEnabled, "setBody type=" + setBody.getParameterTypes()[0].getName());
			setBody.invoke(attachmentMessage, new ByteArrayInputStream(bytes));

			/*
			 * Resolve MPL Identifiers
			 */
			Object messageGuid = getMplValue(mpl, "MessageGuid");
			Object stepId = getMplValue(mpl, "StepId");

			debug(mpl, debugEnabled, "MessageGuid=" + messageGuid);
			debug(mpl, debugEnabled, "StepId=" + stepId);

			if (messageGuid == null) {
				throw new Exception("MessageGuid not found");
			}

			messageClass.getMethod("setMplID", String.class).invoke(attachmentMessage, messageGuid.toString());

			if (stepId != null) {
				messageClass.getMethod("setStepID", String.class).invoke(attachmentMessage, stepId.toString());
			}

			/*
			 * Tag Message as ATTACHMENT
			 */
			Class<?> tagClass = messageClass.getClassLoader()
					.loadClass("com.sap.esb.camel.message.storage.api.Message$TagKey");
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object attachmentTag = Enum.valueOf((Class) tagClass, "ATTACHMENT");

			messageClass.getMethod("setTagKey", tagClass).invoke(attachmentMessage, attachmentTag);

			/*
			 * Persist Attachment to Storage
			 */
			URI uri = (URI) messageStore.getClass().getMethod("add", messageClass, boolean.class).invoke(messageStore,
					attachmentMessage, true);
			debug(mpl, debugEnabled, "Attachment URI=" + uri);

			/*
			 * Register Attachment URI in MPL Attachment List
			 */
			Object attachments = getMplValue(mpl, "attachments");
			debug(mpl, debugEnabled, "Existing attachments=" + attachments);

			if (attachments == null) {
				attachments = new ArrayList<>();
				Object key = createTypedKey(mpl, "attachments", List.class);
				put(mpl, key, attachments);
			}

			Class<?> attachmentType = mpl.getClass().getClassLoader().loadClass("com.sap.it.op.mpl.AttachmentType");
			Object attachment = attachmentType.getDeclaredConstructor().newInstance();

			attachmentType.getMethod("setName", String.class).invoke(attachment, name);
			attachmentType.getMethod("setURI", URI.class).invoke(attachment, uri);

			@SuppressWarnings("unchecked")
			List<Object> list = (List<Object>) attachments;

			list.add(attachment);
			debug(mpl, debugEnabled, "Attachment list size=" + list.size());

			return "CREATED URI=" + uri;

		} finally {
			if (ref != null) {
				ctx.ungetService(ref);
			}
		}
	}

	private static Object createTypedKey(Object mpl, String name, Class<?> type) throws Exception {
		Class<?> keyClass = findKeyClass(mpl);
		Method grant = keyClass.getMethod("grantKey", Class.class, String.class);

		return grant.invoke(null, type, name);
	}

	private static Class<?> findKeyClass(Object mpl) throws Exception {

		for (Method m : mpl.getClass().getMethods()) {

			if ("put".equals(m.getName()) && m.getParameterCount() == 2) {
				Class<?>[] params = m.getParameterTypes();
				if (params[1].equals(Object.class)) {
					return params[0];
				}
			}
		}

		throw new Exception("TypedMessageProcessingLogKey not found");
	}

	private static Object put(Object mpl, Object key, Object value) throws Exception {

		Method put = mpl.getClass().getMethod("put", key.getClass(), Object.class);

		return put.invoke(mpl, key, value);
	}

	private static Object getMplValue(Object mpl, String name) throws Exception {

		Class<?> keyClass = findKeyClass(mpl);
		Object key = createTypedKey(mpl, name, guessKeyType(name));
		Method get = mpl.getClass().getMethod("get", keyClass);

		return get.invoke(mpl, key);
	}

	private static Class<?> guessKeyType(String name) {

		switch (name) {
		case "MessageGuid":
		case "StepId":
			return String.class;
		case "attachments":
			return List.class;
		default:
			return Object.class;
		}
	}

	private static synchronized void debug(Object mpl, boolean enabled, Object value) {

		if (!enabled) {
			return;
		}

		try {
			setStringProperty(mpl, "MPL_DEBUG_" + String.format("%02d", ++debugCounter), String.valueOf(value));
		} catch (Exception ignored) {

		}
	}
}
