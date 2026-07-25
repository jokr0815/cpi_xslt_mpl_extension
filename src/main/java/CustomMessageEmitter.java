// CustomMessageEmitter.java
/*
 * Copyright (c) HO2 Systemberatung GmbH
 * Licensed under the MIT License.
 */
package script;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import net.sf.saxon.Controller;
import net.sf.saxon.event.PipelineConfiguration;
import net.sf.saxon.om.Item;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.serialize.MessageEmitter;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.ObjectValue;

/**
 * @name CustomMessageEmitter
 * @description Custom Saxon MessageEmitter implementation that captures XSLT
 *              xsl:message output into an in-memory buffer and logs it as an
 *              attachment to the SAP CPI Message Processing Log (MPL).
 * @usage Register this emitter in the Saxon transformer pipeline configuration
 *        to automatically extract xsl:messages and attach them to the current
 *        MPL execution context.
 */
public class CustomMessageEmitter extends MessageEmitter {
	
	// Holds the message strings
	private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

	/**
	 * Constructs a new CustomMessageEmitter instance.
	 *
	 * @throws Exception
	 *             if superclass initialization fails.
	 */
	public CustomMessageEmitter() throws Exception {
		super();
	}

	/**
	 * Sets the pipeline configuration for this emitter and configures the internal
	 * output stream to capture messages in memory.
	 *
	 * @param pipe
	 *            the Saxon PipelineConfiguration to apply.
	 */
	@Override
	public void setPipelineConfiguration(PipelineConfiguration pipe) {
		super.setPipelineConfiguration(pipe);
		try {
			setOutputStream(captured);
		} catch (XPathException e) {
			System.err.println(e.getMessage());
		}
	}

	/**
	 * Closes the emitter stream, extracts the captured message buffer as a UTF-8
	 * string, retrieves the SAP Message Processing Log object from the Saxon
	 * Controller context parameter, and writes the captured messages as an MPL
	 * attachment.
	 *
	 * @throws XPathException
	 *             if processing or writing to the MPL fails.
	 */
	@Override
	public void close() throws XPathException {
		try {
			String s = captured.toString(StandardCharsets.UTF_8.name());
			PipelineConfiguration pipe = getPipelineConfiguration();
			Controller controller = pipe.getController();
			StructuredQName qname = new StructuredQName("", "", "SAP_MessageProcessingLog");
			Sequence<?> sequence = controller.getParameter(qname);
			System.out
					.println("MPL parameter sequence = " + (sequence == null ? "NULL" : sequence.getClass().getName()));
			if (sequence != null) {
				Item<?> item = sequence.head();
				System.out.println("MPL parameter item = " + (item == null ? "NULL" : item.getClass().getName()));
				if (item instanceof ObjectValue) {
					Object mpl = ((ObjectValue<?>) item).getObject();
					System.out.println("MPL parameter object = " + (mpl == null ? "NULL" : mpl.getClass().getName()));
					MPLWriter.addAttachmentAsString(mpl, "xslt-messages", s, "text/plain", true);
				}
			}
			super.close();
		} catch (Exception e) {
			throw new XPathException(e);
		}
	}
}