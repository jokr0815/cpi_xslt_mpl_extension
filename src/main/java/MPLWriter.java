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
 * Utility class to interact with SAP Integration Suite Message Processing Log
 * (MPL) dynamically from XSLT Java Extensions via OSGi and Reflection.
 *
 * The class deliberately does not reference
 * com.sap.gateway.ip.core.customdev.util.Message directly.
 *
 * This is important because the XSLT extension bundle may not have visibility
 * to the CPI Message API class.
 *
 * @Author HO2 Systemberatung GmbH
 */
public class MPLWriter {
	/**
	 * Counter used only for debug property names.
	 *
	 * Note: This counter is global to the OSGi bundle. It is synchronized to avoid
	 * duplicate values when multiple messages are processed concurrently.
	 */
	private static int debugCounter = 0;

	/**
	 * Sets a String property on the Message Processing Log.
	 *
	 * XSLT:
	 *
	 * mpl:setStringProperty( $SAP_MessageProcessingLog, 'PROPERTY_NAME', 'VALUE' )
	 */
	public static Object setStringProperty(Object mpl, String name, Object value) throws Exception {
		validateMpl(mpl);
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("MPL property name must not be null or empty");
		}
		Object key = createTypedKey(mpl, name, String.class);
		return put(mpl, key, value);
	}

	/**
	 * Adds a custom header property (User Defined Attribute) to the MPL.
	 *
	 * Default mode without debug logging.
	 */
	public static String addCustomHeaderProperty(Object mpl, String name, String value) throws Exception {
		return addCustomHeaderProperty(mpl, name, value, false);
	}

	/**
	 * Adds a custom header property (User Defined Attribute) to the MPL.
	 */
	public static String addCustomHeaderProperty(Object mpl, String name, String value, boolean debugEnabled)
			throws Exception {
		validateMpl(mpl);
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("Custom header property name must not be null or empty");
		}
		debug(mpl, debugEnabled, "START addCustomHeaderProperty " + name + "=" + value);
		/*
		 * Get root MPL.
		 */
		Object rootMpl = mpl.getClass().getMethod("getRoot").invoke(mpl);
		if (rootMpl == null) {
			throw new Exception("MPL root object is null");
		}
		/*
		 * Use the MPL object's own classloader.
		 *
		 * We deliberately do not reference the CPI Message class here.
		 */
		ClassLoader loader = getClassLoader(mpl);
		/*
		 * Load SAP MPL classes dynamically.
		 */
		Class<?> udaClass = loader.loadClass("com.sap.it.op.mpl.UserDefinedAttributeTypeV2");
		Class<?> keysClass = loader.loadClass("com.sap.it.op.mpl.TypedMessageProcessingLogKeys");
		/*
		 * Obtain predefined key:
		 *
		 * TK_USER_DEFINED_ATTRIBUTES
		 */
		Object udaKey = keysClass.getField("TK_USER_DEFINED_ATTRIBUTES").get(null);
		/*
		 * Read existing UDA object.
		 */
		Method get = findMethod(rootMpl.getClass(), "get", udaKey.getClass());
		Object uda = get.invoke(rootMpl, udaKey);
		/*
		 * Create UDA object if none exists.
		 */
		if (uda == null) {
			uda = udaClass.getDeclaredConstructor().newInstance();
			Method put = findMethod(rootMpl.getClass(), "put", udaKey.getClass(), Object.class);
			put.invoke(rootMpl, udaKey, uda);
		}
		/*
		 * Obtain the Set associated with the UDA name.
		 */
		Method grantAttribute = udaClass.getMethod("grantAttribute", String.class);
		@SuppressWarnings("unchecked")
		Set<String> values = (Set<String>) grantAttribute.invoke(uda, name);
		if (values == null) {
			throw new Exception("grantAttribute returned null for UDA: " + name);
		}
		/*
		 * Add value.
		 */
		values.add(value != null ? value : "");
		debug(mpl, debugEnabled, "UDA final=" + uda);
		return "OK";
	}

	/**
	 * Adds an attachment to the Message Processing Log.
	 *
	 * Default mode without debug logging.
	 */
	public static String addAttachmentAsString(Object mpl, String name, String content, String mimeType)
			throws Exception {
		return addAttachmentAsString(mpl, name, content, mimeType, false);
	}

	/**
	 * Adds an attachment to the Message Processing Log.
	 * The implementation uses the SAP MessageStorageWrite OSGi service.
	 *
	 * IMPORTANT: This method deliberately does NOT reference:
	 * com.sap.gateway.ip.core.customdev.util.Message
	 * The original implementation used:
	 * Class.forName( "com.sap.gateway.ip.core.customdev.util.Message" )
	 * which caused:
	 * ClassNotFoundException: com.sap.gateway.ip.core.customdev.util.Message
	 * because the XSLT extension bundle does not have visibility to that class.
	 */
	public static String addAttachmentAsString(Object mpl, String name, String content, String mimeType,
			boolean debugEnabled) throws Exception {
		validateMpl(mpl);
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("Attachment name must not be null or empty");
		}
		if (content == null) {
			content = "";
		}
		if (mimeType == null || mimeType.isEmpty()) {
			mimeType = "text/plain";
		}
		debug(mpl, debugEnabled, "START addAttachment name=" + name);
		/*
		 * ----1. Obtain OSGi bundle context ----
		 *
		 * IMPORTANT:
		 *
		 * Do NOT do this:
		 *
		 * FrameworkUtil.getBundle( Class.forName(
		 * "com.sap.gateway.ip.core.customdev.util.Message" ) );
		 *
		 * Instead, resolve the bundle that contains this class.
		 */
		Bundle bundle = FrameworkUtil.getBundle(MPLWriter.class);
		debug(mpl, debugEnabled, "Bundle=" + bundle);
		if (bundle == null) {
			throw new Exception("Unable to resolve OSGi bundle for MPLWriter");
		}
		BundleContext ctx = bundle.getBundleContext();
		if (ctx == null) {
			throw new Exception("Unable to resolve OSGi BundleContext for MPLWriter");
		}
		/*
		 * ----2. Resolve MessageStorageWrite service ----
		 */
		ServiceReference<?> ref = ctx.getServiceReference("com.sap.esb.camel.message.storage.api.MessageStorageWrite");
		debug(mpl, debugEnabled, "MessageStorage reference=" + ref);
		if (ref == null) {
			return "MessageStorageWrite service not found";
		}
		Object messageStore = null;
		try {
			/*
			 * 3. Obtain service
			 * 
			 */
			messageStore = ctx.getService(ref);
			if (messageStore == null) {
				throw new Exception("MessageStorageWrite service returned null");
			}
			debug(mpl, debugEnabled, "MessageStorage service=" + messageStore.getClass().getName());
			/*
			 * 4. Load SAP Message class using the service's classloader
			 * 
			 *
			 * This is intentional.
			 *
			 * The class is loaded through the classloader of the actual MessageStorage
			 * service rather than through the XSLT extension bundle.
			 */
			ClassLoader messageLoader = messageStore.getClass().getClassLoader();
			if (messageLoader == null) {
				throw new Exception("Unable to resolve MessageStorage service classloader");
			}
			Class<?> messageClass = messageLoader.loadClass("com.sap.esb.camel.message.storage.api.Message");
			debug(mpl, debugEnabled, "Message class=" + messageClass.getName());
			/*
			 * 5. Create attachment Message
			 */
			Object attachmentMessage = messageClass.getDeclaredConstructor().newInstance();
			/*
			 * 6. Set attachment headers
			 */
			Map<String, String> headers = new HashMap<>();
			headers.put("SapAttachmentName", name);
			headers.put("SapAttachmentContentType", mimeType);
			Method setHeader = messageClass.getMethod("setHeader", Map.class);
			setHeader.invoke(attachmentMessage, headers);
			/*
			 * 7. Set attachment body
			 */
			byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
			debug(mpl, debugEnabled, "Attachment bytes=" + bytes.length);
			Method setBody = findSingleParameterMethod(messageClass, "setBody");
			if (setBody == null) {
				throw new Exception("Message.setBody not found");
			}
			debug(mpl, debugEnabled, "setBody type=" + setBody.getParameterTypes()[0].getName());
			setBody.invoke(attachmentMessage, new ByteArrayInputStream(bytes));
			/*
			 * 8. Resolve MPL identifiers
			 */
			Object messageGuid = getMplValue(mpl, "MessageGuid");
			Object stepId = getMplValue(mpl, "StepId");
			debug(mpl, debugEnabled, "MessageGuid=" + messageGuid);
			debug(mpl, debugEnabled, "StepId=" + stepId);
			if (messageGuid == null) {
				throw new Exception("MessageGuid not found in MPL");
			}
			/*
			 * 9. Set MPL ID
			 * 
			 */
			messageClass.getMethod("setMplID", String.class).invoke(attachmentMessage, messageGuid.toString());
			/*
			 * 10. Set Step ID
			 * 
			 */
			if (stepId != null) {
				messageClass.getMethod("setStepID", String.class).invoke(attachmentMessage, stepId.toString());
			}
			/*
			 * 11. Tag Message as ATTACHMENT
			 */
			Class<?> tagClass = messageLoader.loadClass("com.sap.esb.camel.message.storage.api.Message$TagKey");
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object attachmentTag = Enum.valueOf((Class) tagClass, "ATTACHMENT");
			messageClass.getMethod("setTagKey", tagClass).invoke(attachmentMessage, attachmentTag);
			/*
			 * 12. Persist attachment
			 */
			Method addMethod = messageStore.getClass().getMethod("add", messageClass, boolean.class);
			URI uri = (URI) addMethod.invoke(messageStore, attachmentMessage, true);
			debug(mpl, debugEnabled, "Attachment URI=" + uri);
			/*
			 * 13. Register attachment in MPL attachment list
			 * 
			 */
			Object attachments = getMplValue(mpl, "attachments");
			debug(mpl, debugEnabled, "Existing attachments=" + attachments);
			if (attachments == null) {
				attachments = new ArrayList<>();
				Object key = createTypedKey(mpl, "attachments", List.class);
				put(mpl, key, attachments);
			}
			/*
			 * 14. Create SAP AttachmentType
			 */
			ClassLoader mplLoader = getClassLoader(mpl);
			Class<?> attachmentType = mplLoader.loadClass("com.sap.it.op.mpl.AttachmentType");
			Object attachment = attachmentType.getDeclaredConstructor().newInstance();
			/*
			 * Set attachment name.
			 */
			attachmentType.getMethod("setName", String.class).invoke(attachment, name);
			/*
			 * Set attachment URI.
			 */
			attachmentType.getMethod("setURI", URI.class).invoke(attachment, uri);
			/*
			 * 15. Add AttachmentType to MPL list
			 * 
			 */
			@SuppressWarnings("unchecked")
			List<Object> list = (List<Object>) attachments;
			list.add(attachment);
			debug(mpl, debugEnabled, "Attachment list size=" + list.size());
			return "CREATED URI=" + uri;
		} finally {
			/*
			 * 16. Release OSGi service
			 */
			if (ref != null) {
				try {
					ctx.ungetService(ref);
				} catch (Exception ignored) {
					// Do not mask the original exception.
				}
			}
		}
	}

	/**
	 * Creates a TypedMessageProcessingLogKey dynamically.
	 */
	private static Object createTypedKey(Object mpl, String name, Class<?> type) throws Exception {
		Class<?> keyClass = findKeyClass(mpl);
		Method grant = keyClass.getMethod("grantKey", Class.class, String.class);
		return grant.invoke(null, type, name);
	}

	/**
	 * Finds the TypedMessageProcessingLogKey class by inspecting the MPL put(...)
	 * method.
	 */
	private static Class<?> findKeyClass(Object mpl) throws Exception {
		validateMpl(mpl);
		for (Method method : mpl.getClass().getMethods()) {
			if (!"put".equals(method.getName())) {
				continue;
			}
			if (method.getParameterCount() != 2) {
				continue;
			}
			Class<?>[] params = method.getParameterTypes();
			if (params[1].equals(Object.class)) {
				return params[0];
			}
		}
		throw new Exception("TypedMessageProcessingLogKey not found");
	}

	/**
	 * Writes a value into the MPL.
	 */
	private static Object put(Object mpl, Object key, Object value) throws Exception {
		if (key == null) {
			throw new Exception("MPL key is null");
		}
		Method put = findMethod(mpl.getClass(), "put", key.getClass(), Object.class);
		return put.invoke(mpl, key, value);
	}

	/**
	 * Reads a value from the MPL.
	 */
	private static Object getMplValue(Object mpl, String name) throws Exception {
		validateMpl(mpl);
		Class<?> keyClass = findKeyClass(mpl);
		Object key = createTypedKey(mpl, name, guessKeyType(name));
		Method get = findMethod(mpl.getClass(), "get", keyClass);
		return get.invoke(mpl, key);
	}

	/**
	 * Guesses the type used for well-known MPL keys.
	 */
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

	/**
	 * Returns the classloader of the MPL implementation.
	 */
	private static ClassLoader getClassLoader(Object mpl) throws Exception {
		validateMpl(mpl);
		ClassLoader loader = mpl.getClass().getClassLoader();
		if (loader == null) {
			loader = Thread.currentThread().getContextClassLoader();
		}
		if (loader == null) {
			throw new Exception("Unable to resolve MPL classloader");
		}
		return loader;
	}

	/**
	 * Finds a method with the exact parameter types.
	 */
	private static Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes)
			throws NoSuchMethodException {
		try {
			return clazz.getMethod(methodName, parameterTypes);
		} catch (NoSuchMethodException e) {
			/*
			 * Try declared methods as a fallback.
			 */
			for (Method method : clazz.getMethods()) {
				if (!methodName.equals(method.getName())) {
					continue;
				}
				if (method.getParameterCount() != parameterTypes.length) {
					continue;
				}
				Class<?>[] actual = method.getParameterTypes();
				boolean compatible = true;
				for (int i = 0; i < actual.length; i++) {
					if (!actual[i].isAssignableFrom(parameterTypes[i])) {
						compatible = false;
						break;
					}
				}
				if (compatible) {
					return method;
				}
			}
			throw e;
		}
	}

	/**
	 * Finds a method with exactly one parameter.
	 */
	private static Method findSingleParameterMethod(Class<?> clazz, String methodName) {
		for (Method method : clazz.getMethods()) {
			if (methodName.equals(method.getName()) && method.getParameterCount() == 1) {
				return method;
			}
		}
		return null;
	}

	/**
	 * Validates the MPL object passed from XSLT.
	 */
	private static void validateMpl(Object mpl) {
		if (mpl == null) {
			throw new IllegalArgumentException("Message Processing Log object must not be null");
		}
	}

	/**
	 * Writes debug information into MPL properties.
	 *
	 * Debugging failures are deliberately ignored so that debug logging never masks
	 * the original operation.
	 */
	private static synchronized void debug(Object mpl, boolean enabled, Object value) {
		if (!enabled) {
			return;
		}
		try {
			setStringProperty(mpl, "MPL_DEBUG_" + String.format("%02d", ++debugCounter), String.valueOf(value));
		} catch (Exception ignored) {
			/*
			 * Never let debug logging break the actual MPL operation.
			 */
		}
	}
}