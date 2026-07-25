/*
 * Copyright (c) HO2 Systemberatung GmbH
 * Licensed under the MIT License.
 */
import com.sap.gateway.ip.core.customdev.util.Message
import org.apache.camel.Exchange
import org.apache.camel.component.xslt.XsltEndpoint
import org.apache.camel.component.xslt.saxon.XsltSaxonEndpoint
import net.sf.saxon.lib.Logger
import net.sf.saxon.lib.TraceListener
import javax.xml.transform.ErrorListener
import net.sf.saxon.trace.InstructionInfo
import net.sf.saxon.trace.TraceEventMulticaster
import net.sf.saxon.trace.XSLTTraceListener
import net.sf.saxon.Controller
import net.sf.saxon.expr.XPathContext
import net.sf.saxon.om.Item
import net.sf.saxon.om.StructuredQName
import net.sf.saxon.value.ObjectValue
import script.MPLWriter
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.TransformerException
import java.lang.reflect.Field
import java.security.MessageDigest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
/*
* ============================================================
* CONFIGURATION
* ============================================================
*
* Change ONLY this URI if you want to test another stylesheet.
*
* The URI identifies the exact Camel endpoint and therefore
* the exact XSLT resource.
*/
class SaxonDiagnosticConfig {
  static final String ENDPOINT_URI = 'xslt-saxon://mapping/XSLTMapping1.xsl' + '?output=bytes' + '&saxonExtensionFunctions=%23xsltExtensionsV11' + '&secureProcessing=false' + '&transformerFactory=%23saxoneeTransformer'
  static final String XSLT_RESOURCE = 'mapping/XSLTMapping1.xsl'
  static final String CUSTOM_EMITTER = 'script.CustomMessageEmitter'
  static final String LOGGER_PROPERTY = 'CUSTOM_TRACE_LOGGER'
  static final String NATIVE_LOGGER_PROPERTY = 'CUSTOM_XSLT_TRACE_LOGGER'
  static final String LISTENER_PROPERTY = 'CUSTOM_TRACE_LISTENER'
  static final String NATIVE_LISTENER_PROPERTY = 'CUSTOM_XSLT_TRACE_LISTENER'
  static final String ERROR_LISTENER_PROPERTY = 'CUSTOM_ERROR_LISTENER'
  static final String CONFIGURATION_PROPERTY = 'CUSTOM_SAXON_CONFIGURATION'
}
/*
* ============================================================
* CUSTOM TRACE LOGGER
* ============================================================
*/
class CustomTraceLogger extends Logger {
  final ByteArrayOutputStream output = new ByteArrayOutputStream()
  @Override
  void println(String message, int level) {
    if (message == null) {
      return }
    synchronized (output) {
      output.write((message + '\n').getBytes('UTF-8'))
    }
  }
  /*
  * Saxon Logger API.
  *
  * This stream belongs ONLY to the diagnostic logger.
  * It is NOT the XSLT xsl:message output stream.
*/
  @Override
  StreamResult asStreamResult() {
    return new StreamResult(output)
  }
  String getOutput() {
    synchronized (output) {
      return output.toString('UTF-8')
    }
  }
}
/*
* ============================================================
* CUSTOM ERROR LISTENER
* ============================================================
*
* Captures errors and warnings reported through Saxon's
* javax.xml.transform.ErrorListener mechanism.
*
* Important:
*
* This is separate from:
*
* 1. xsl:message               -> CustomMessageEmitter
* 2. Saxon Logger              -> CustomTraceLogger
* 3. Saxon TraceListener       -> CustomTraceListener
* 4. ErrorListener             -> CustomErrorListener
*
* Depending on where the error occurs, Saxon may call warning(),
* error(), or fatalError().
*/
class CustomErrorListener implements ErrorListener {
  final ByteArrayOutputStream output = new ByteArrayOutputStream()
  private void append(String level, TransformerException exception) {
    if (exception == null) {
      return }
    synchronized (output) {
      output.write(('[' + level + ']\n').getBytes('UTF-8'))
      output.write(('Message: ' + exception.getMessage() + '\n').getBytes('UTF-8'))
      if (exception.getLocator() != null) {
        output.write(('SystemId: ' + exception.getLocator().getSystemId() + '\n').getBytes('UTF-8'))
        output.write(('Line: ' + exception.getLocator().getLineNumber() + '\n').getBytes('UTF-8'))
        output.write(('Column: ' + exception.getLocator().getColumnNumber() + '\n').getBytes('UTF-8'))
      }
      Throwable cause = exception.getException()
      if (cause != null) {
        output.write(('Cause: ' + cause.getClass().name + '\n').getBytes('UTF-8'))
        output.write(('Cause message: ' + cause.getMessage() + '\n').getBytes('UTF-8'))
      }
      output.write('\n'.getBytes('UTF-8'))
    }
  }
  @Override
  void warning(TransformerException exception) {
    append('WARNING', exception)
  }
  @Override
  void error(TransformerException exception) {
    append('ERROR', exception)
  }
  @Override
  void fatalError(TransformerException exception) {
    append('FATAL ERROR', exception)
  }
  String getOutput() {
    synchronized (output) {
      return output.toString('UTF-8')
    }
  }
}
/*
* ============================================================
* CUSTOM TRACE LISTENER
* ============================================================
*/
class CustomTraceListener implements TraceListener {
  private final CustomTraceLogger logger
  private final CustomTraceLogger nativeLogger
  private final ThreadLocal<Controller> controllers = new ThreadLocal<Controller>()
  CustomTraceListener(CustomTraceLogger logger, CustomTraceLogger nativeLogger) {
    this.logger = logger
    this.nativeLogger = nativeLogger
  }
  /**
   * Triggered when the trace listener opens execution.
   *
   * @Param controller Saxon Controller instance
   * @return void
   */
  @Override
  void open(Controller controller) {
    controllers.set(controller)
    logger.info('TRACE LISTENER OPEN')
  }
  /**
   * Triggered when the trace listener closes execution.
   *
   * @return void
   */
  @Override
  void close() {
    logger.info('TRACE LISTENER CLOSE')
    try {
      Controller controller = controllers.get()
      Object mpl = resolveMpl(controller)
      if (mpl == null) {
        logger.info('TRACE ATTACHMENT SKIPPED: MPL parameter is unavailable')
        return
      }

      String customTrace = logger.getOutput()
      String nativeTrace = nativeLogger.getOutput()
      String combinedTrace =
        '===== CUSTOM TRACE LISTENER =====\n' + customTrace +
        '\n===== NATIVE XSLT TRACE LISTENER =====\n' + nativeTrace

      MPLWriter.addAttachmentAsString(
        mpl, 'saxon-combined-trace.txt', combinedTrace, 'text/plain', true
      )
      MPLWriter.addAttachmentAsString(
        mpl,
        'saxon-xslt-trace.xml',
        SaxonDiagnosticReflection.createTraceXml(nativeTrace),
        'application/xml',
        true
      )
    } catch (Exception e) {
      System.err.println('Could not write trace attachments on close: ' + e.message)
    } finally {
      controllers.remove()
    }
  }

  private static Object resolveMpl(Controller controller) {
    if (controller == null) {
      return null
    }
    StructuredQName qname = new StructuredQName('', '', 'SAP_MessageProcessingLog')
    def sequence = controller.getParameter(qname)
    if (sequence == null || sequence.head() == null) {
      return null
    }
    def item = sequence.head()
    return item instanceof ObjectValue ? item.getObject() : null
  }
  /**
   * Called when entering an XSLT instruction.
   *
   * @Param instruction InstructionInfo describing the current XSLT element
   * @Param context Current XPath evaluation context
   * @return void
   */
  @Override
  void enter(InstructionInfo instruction, XPathContext context) {
    logger.info('TRACE ENTER: ' + 'constructType=' + instruction?.getConstructType() + ', line=' + instruction?.getLineNumber() + ', systemId=' + instruction?.getSystemId())
  }
  /**
   * Called when leaving an XSLT instruction.
   *
   * @Param instruction InstructionInfo describing the evaluated XSLT element
   * @return void
   */
  @Override
  void leave(InstructionInfo instruction) {
    logger.info('TRACE LEAVE: ' + 'constructType=' + instruction?.getConstructType() + ', line=' + instruction?.getLineNumber() + ', systemId=' + instruction?.getSystemId())
  }
  @Override
  /**
   * Called when processing of an item in a sequence begins.
   *
   * @Param item Item currently being processed
   * @return void
   */
  void startCurrentItem(Item item) {
    logger.info('TRACE START ITEM: ' + item?.toString())
  }
  /**
   * Called when processing of an item in a sequence ends.
   *
   * @Param item Item whose processing finished
   * @return void
   */
  @Override
  void endCurrentItem(Item item) {
    logger.info('TRACE END ITEM: ' + item?.toString())
  }
  /**
   * Sets the output destination logger. Deliberately left ignored as the internal CustomTraceLogger is maintained.
   *
   * @Param logger Logger instance assigned by Saxon
   * @return void
   */
  @Override
  void setOutputDestination(Logger logger) {
  /*
  * Deliberately ignored.
  *
  * The Configuration logger remains our
  * CustomTraceLogger instance.
  */
  }
}
/**
 * @NAME SaxonDiagnosticReflection
 * @description Reflection helper class designed to safely read and write deep fields within the Saxon internal object graph.
 * @usage Used by processData to inspect, configure, and override internal Saxon components at runtime.
 */
class SaxonDiagnosticReflection {
  /**
   * Reads a field value by traversing recursively up the target object's class hierarchy.
   *
   * @Param object Target object instance to inspect
   * @Param fieldName Name of the declared field to retrieve
   * @return Object field value if found, null otherwise
   */
  static Object readFieldRecursive(Object object, String fieldName) {
    if (object == null) {
      return null
    }
    Class current = object.getClass()
    while (current != null) {
      try {
        Field field = current.getDeclaredField(fieldName)
        field.setAccessible(true)
        return field.get(object)
      } catch (NoSuchFieldException ignored) {
        current = current.getSuperclass()
      }
    }
    return null
  }
  /**
   * Writes a field value recursively by searching up the class hierarchy.
   *
   * @Param object Target object instance to modify
   * @Param fieldName Name of the declared field to write
   * @Param value Object value to set on the target field
   * @return boolean true if successfully updated, false otherwise
   */
  static boolean writeFieldRecursive(Object object, String fieldName, Object value) {
    if (object == null) {
      return false
    }
    Class current = object.getClass()
    while (current != null) {
      try {
        Field field = current.getDeclaredField(fieldName)
        field.setAccessible(true)
        field.set(object, value)
        return true
      } catch (NoSuchFieldException ignored) {
        current = current.getSuperclass()
      } catch (Exception e) {
        return false
      }
    }
    return false
  }
/**
   * Recursively finds a field value across an object graph up to a designated depth limit.
   *
   * @Param object Root object instance to start inspection
   * @Param fieldName Field name to locate
   * @Param maxDepth Maximum recursion depth limit
   * @return Object field value if located, null otherwise
   */
  static Object findFieldValueRecursive(Object object, String fieldName, int maxDepth) {
    if (object == null || maxDepth< 0) {
      return null
    }
    Object direct = readFieldRecursive(object, fieldName)
    if (direct != null) {
      return direct
    }
    return null
  }
  /**
   * Locates the internal PreparedStylesheet object from a compiled TemplatesImpl instance.
   *
   * @Param templates Source compiled templates object
   * @return Object internal PreparedStylesheet reference if resolved, null otherwise
   */
  static Object getPreparedStylesheet(Object templates) {
    if (templates == null) {
      return null
    }
    Object executable = readFieldRecursive(templates, 'executable')
    if (executable == null) {
      return null
    }
    return readFieldRecursive(executable, 'preparedStylesheet')
  }
  /**
   * Generates a hex-encoded SHA-256 hash string for the given raw byte array.
   *
   * @Param data Byte array payload to hash
   * @return String hexadecimal SHA-256 hash
   */
  static String sha256(byte[] data) {
    if (data == null) {
      return null
    }
    MessageDigest digest = MessageDigest.getInstance('SHA-256')
    byte[] hash = digest.digest(data)
    StringBuilder result = new StringBuilder()
    for (byte b : hash) {
      result.append(String.format('%02x', b & 0xff))
    }
    return result.toString()
  }
  /*
  * Load the CustomMessageEmitter using the TCCL.
  *
  * This is important because Saxon's DynamicLoader
  * ultimately uses the supplied ClassLoader / TCCL.
  */
  static Class loadCustomEmitter(ClassLoader classLoader) {
    return classLoader.loadClass(SaxonDiagnosticConfig.CUSTOM_EMITTER)
  }
  /*
  * Configure Saxon's DynamicLoader.
  *
  * Based on the DynamicLoader source supplied:
  *
  * DynamicLoader.setClassLoader(ClassLoader)
  *
  * The DynamicLoader may be reachable from the
  * Configuration through a private field.
  */
  static String configureDynamicLoader(Object configuration, ClassLoader classLoader) {
    if (configuration == null) {
      return 'Configuration is null'
    }
    /*
    * Try common field names.
*/
    String[] names = [ 'dynamicLoader', 'loader', 'classLoader' ]
    for (String name : names) {
      Object loader = readFieldRecursive(configuration, name)
      if (loader == null) {
        continue
      }
      try {
        loader.getClass().getMethod('setClassLoader', ClassLoader.class).invoke(loader, classLoader)
        return "DynamicLoader configured via field '" + name + "': " + loader.getClass().name
      } catch (Exception ignored) {
         /*
          * Continue searching.
          */
      }
    }
    return 'DynamicLoader field not located; TCCL remains: ' + classLoader
  }
  /*
  * Convert the native XSLTTraceListener output into a valid
  * XML document.
  *
  * If the native output already contains one valid XML document,
  * it is returned unchanged.
  *
  * If the native output contains multiple XML fragments,
  * they are wrapped in a single root element.
  *
  * If the output is empty, an empty XML document is returned.
  */
  static String createTraceXml(String trace) {
    if (trace == null || trace.trim().length() == 0) {
      return '<?xml version="1.0" encoding="UTF-8"?>\n' + '<saxon-xslt-trace/>\n'
    }
    String trimmed = trace.trim()
    /*
    * First check whether the complete output is already
    * a valid XML document.
    */
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
      factory.setNamespaceAware(true)
      factory.newDocumentBuilder().parse(new ByteArrayInputStream(trimmed.getBytes('UTF-8')))
      return trimmed
    } catch (Exception ignored) {
     /*
      * The native trace may contain multiple XML fragments
      * or diagnostic output.
      */
    }
    /*
    * Wrap the native trace output in a single XML root.
    */
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + '<saxon-xslt-trace>\n' + trace + '\n</saxon-xslt-trace>\n'
  }
}
/*
* ============================================================
* CPI MESSAGE PROCESSOR
* ============================================================
*/
Message processData( Message message ) {
  /*
  * ========================================================
  * 1. Get Exchange
  * ========================================================
  */
  Exchange exchange = message.exchange
  if ( exchange == null ) {
    throw new IllegalStateException( 'Message.exchange is null' )
  }
  /*
  * ========================================================
  * 2. Resolve the endpoint from the URI
  *
  * This is the important URI-based selection mechanism.
  *
  * Change SaxonDiagnosticConfig.ENDPOINT_URI to inspect
  * another XSLT.
  * ========================================================
  */
  def endpoint = exchange.context.getEndpoint( SaxonDiagnosticConfig.ENDPOINT_URI )
  if ( endpoint == null ) {
    throw new IllegalStateException( 'Could not resolve endpoint: ' + SaxonDiagnosticConfig.ENDPOINT_URI )
  }
  if ( !(endpoint instanceof XsltSaxonEndpoint) ) {
    throw new IllegalStateException( 'Resolved endpoint is not XsltSaxonEndpoint: ' + endpoint.class.name )
  }
  XsltSaxonEndpoint saxonEndpoint = (XsltSaxonEndpoint) endpoint
  /*
  * ========================================================
  * 3. Get the XSLT builder
  *
  * This is the supported getter:
  *
  * endpoint.getXslt()
  * ========================================================
  */
  def builder = saxonEndpoint.getXslt()
  if ( builder == null ) {
    throw new IllegalStateException( 'XsltSaxonEndpoint.getXslt() returned null' )
  }
  /*
  * ========================================================
  * 4. Get TransformerFactory
  *
  * IMPORTANT:
  * Do NOT call:
  * builder.getTransformerFactory()
  * That method does not exist.
  * XsltEndpoint exposes:
  * getTransformerFactory()
  * ========================================================
  */
  def transformerFactory = saxonEndpoint.getTransformerFactory()
  if ( transformerFactory == null ) {
  /*
  * Fallback: inspect builder fields.
  */
    transformerFactory = SaxonDiagnosticReflection.readFieldRecursive( builder, 'transformerFactory' )
  }
  if ( transformerFactory == null ) {
    throw new IllegalStateException( 'Could not locate transformerFactory' )
  }
  /*
  * ========================================================
  * 5. Get Saxon Configuration
  * ========================================================
  */
  def configuration = transformerFactory.getConfiguration()
  if ( configuration == null ) {
    throw new IllegalStateException( 'TransformerFactory.getConfiguration() returned null' )
  }
  /*
  * ========================================================
  * 6. TCCL - Xslt (Saxon) does not see my custom 
  * ========================================================
  */
  ClassLoader tccl = Thread.currentThread().getContextClassLoader()
  /*
  * ========================================================
  * 7. Configure DynamicLoader
  * ========================================================
  */
  String dynamicLoaderDiagnostic = SaxonDiagnosticReflection.configureDynamicLoader( configuration, tccl )
  /*
  * ========================================================
  * 8. Install Logger
  * ========================================================
  */
  CustomTraceLogger traceLogger = new CustomTraceLogger()
  configuration.setLogger( traceLogger )
  /*
  * ========================================================
  * 9. Install TraceListener
  * ========================================================
  */
  /*
  * Native XSLT trace logger.
  *
  * The trace listener writes this output to MPL attachments from close(),
  * after the XSLT execution has completed.
  */
  CustomTraceLogger xsltTraceLogger = new CustomTraceLogger()
  CustomTraceListener traceListener = new CustomTraceListener( traceLogger, xsltTraceLogger )
  XSLTTraceListener xsltTraceListener = new XSLTTraceListener()
  xsltTraceListener.setOutputDestination( xsltTraceLogger )
  TraceListener combinedTraceListener = TraceEventMulticaster.add( traceListener, xsltTraceListener )
  configuration.setTraceListener( combinedTraceListener )
  /*
  * ========================================================
  * 10. Install ErrorListener
  *
  * This is independent from the Logger and TraceListener.
  * ========================================================
  */
  CustomErrorListener errorListener = new CustomErrorListener()
  configuration.setErrorListener( errorListener )
  /*
  * ========================================================
  * 11. Load CustomMessageEmitter
  *
  * First verify that the TCCL can load it.
  * ========================================================
  */
  Class customEmitterClass = SaxonDiagnosticReflection.loadCustomEmitter( tccl )
  /*
  * ========================================================
  * 12. Get compiled Templates
  * ========================================================
  */
  def templates = SaxonDiagnosticReflection.readFieldRecursive( builder, 'template' )
  /*
  * ========================================================
  * 13. Locate PreparedStylesheet
  * ========================================================
  */
  def preparedStylesheet = SaxonDiagnosticReflection.getPreparedStylesheet( templates )
  /*
  * ========================================================
  * 14. Modify PreparedStylesheet message receiver
  *
  * This is the critical difference from changing:
  *
  * configuration.setMessageEmitterClass(...)
  *
  * The already compiled PreparedStylesheet has its own
  * messageReceiverClassName.
  * ========================================================
  */
  String receiverBefore = null
  String receiverAfter = null
  boolean receiverChanged = false
  if ( preparedStylesheet != null ) {
    receiverBefore = SaxonDiagnosticReflection.readFieldRecursive( preparedStylesheet, 'messageReceiverClassName' )?.toString()
    receiverChanged = SaxonDiagnosticReflection.writeFieldRecursive( preparedStylesheet, 'messageReceiverClassName', SaxonDiagnosticConfig.CUSTOM_EMITTER )
    receiverAfter = SaxonDiagnosticReflection.readFieldRecursive( preparedStylesheet, 'messageReceiverClassName' )?.toString()
  }
  /*
  * ========================================================
  * 15. Direct CustomMessageEmitter test
  * ========================================================
  */
  def customEmitterInstance = customEmitterClass.getDeclaredConstructor() .newInstance()
  /*
  * ========================================================
  * 16. Configuration.makeEmitter() test
  * ========================================================
*/
  def configurationEmitter = configuration.makeEmitter( SaxonDiagnosticConfig.CUSTOM_EMITTER, new Properties() )
  /*
  * ========================================================
  * 17. XSLT source bytes
  * ========================================================
  */
  byte[] stylesheetBytes = null
  String stylesheetHash = null
  try {
    def resolver = builder.getUriResolver()
    if ( resolver != null ) {
      def source = resolver.resolve( SaxonDiagnosticConfig.XSLT_RESOURCE, null )
      if ( source != null ) {
        stylesheetBytes = exchange.context.typeConverter.convertTo( byte[].class, source )
        if ( stylesheetBytes != null ) {
          stylesheetHash = SaxonDiagnosticReflection.sha256( stylesheetBytes )
        }
      }
    }
  } catch ( Exception e ) {
    /*
    * Keep diagnostics alive even if byte conversion
    * is not possible.
    */
  }
  /*
  * ========================================================
  * 18. Build diagnostic report
  * ========================================================
  */
  StringBuilder report = new StringBuilder()
  report.append( '========================================\n' )
  report.append( 'URI-SPECIFIC SAXON XSLT DIAGNOSTICS\n' )
  report.append( '========================================\n\n' )
  report.append( 'Endpoint URI:\n' )
  report.append( SaxonDiagnosticConfig.ENDPOINT_URI )
  report.append( '\n\n' )
  report.append( 'Endpoint:\n' )
  report.append( saxonEndpoint.class.name )
  report.append( '\n\n' )
  report.append( 'Endpoint identity:\n' )
  report.append( System.identityHashCode( saxonEndpoint ) )
  report.append( '\n\n' )
  report.append( 'Builder:\n' )
  report.append( builder.class.name )
  report.append( '\n\n' )
  report.append( 'Builder identity:\n' )
  report.append( System.identityHashCode( builder ) )
  report.append( '\n\n' )
  report.append( 'TransformerFactory:\n' )
  report.append( transformerFactory.class.name )
  report.append( '\n\n' )
  report.append( 'Configuration:\n' )
  report.append( configuration.class.name )
  report.append( '\n\n' )
  report.append( 'Configuration identity:\n' )
  report.append( System.identityHashCode( configuration ) )
  report.append( '\n\n' )
  report.append( '========================================\n' )
  report.append( 'CLASSLOADER\n' )
  report.append( '========================================\n\n' )
  report.append( 'Thread Context ClassLoader:\n' )
  report.append( tccl )
  report.append( '\n\n' )
  report.append( 'CustomMessageEmitter ClassLoader:\n' )
  report.append( customEmitterClass.classLoader )
  report.append( '\n\n' )
  report.append( 'CustomMessageEmitter instance:\n' )
  report.append( customEmitterInstance.class.name )
  report.append( '\n\n' )
  report.append( 'DynamicLoader configuration:\n' )
  report.append( dynamicLoaderDiagnostic )
  report.append( '\n\n' )
  report.append( '========================================\n' )
  report.append( 'COMPILED XSLT OBJECTS\n' )
  report.append( '========================================\n\n' )
  report.append( 'Templates:\n' )
  report.append( templates?.class?.name )
  report.append( '\n\n' )
  report.append( 'XsltExecutable:\n' )
  def executable = SaxonDiagnosticReflection.readFieldRecursive( templates, 'executable' )
  report.append( executable?.class?.name )
  report.append( '\n\n' )
  report.append( 'PreparedStylesheet:\n' )
  report.append( preparedStylesheet?.class?.name )
  report.append( '\n\n' )
  report.append( 'PreparedStylesheet identity:\n' )
  report.append( preparedStylesheet == null ? 'null' : System.identityHashCode( preparedStylesheet ) )
  report.append( '\n\n' )
  report.append( '========================================\n' )
  report.append( 'MESSAGE RECEIVER\n' )
  report.append( '========================================\n\n' )
  report.append( 'messageReceiverClassName BEFORE:\n' )
  report.append( receiverBefore )
  report.append( '\n\n' )
  report.append( 'Attempt to modify:\n' )
  report.append( receiverChanged )
  report.append( '\n\n' )
  report.append( 'messageReceiverClassName AFTER:\n' )
  report.append( receiverAfter )
  report.append( '\n\n' )
  report.append( '========================================\n' )
  report.append( 'CUSTOM EMITTER TEST\n' )
  report.append( '========================================\n\n' )
  report.append( 'Class:\n' )
  report.append( customEmitterClass.name )
  report.append( '\n\n' )
  report.append( 'ClassLoader:\n' )
  report.append( customEmitterClass.classLoader )
  report.append( '\n\n' )
  report.append( 'Direct instantiation:\nSUCCESS\n\n' )
  report.append( 'Configuration.makeEmitter():\n' )
  report.append( configurationEmitter.class.name )
  report.append( '\n\n' )
  report.append( '========================================\n' )
  report.append( 'ERROR LISTENER\n' )
  report.append( '========================================\n\n' )
  report.append( 'Configuration ErrorListener:\n' )
  report.append( configuration.getErrorListener()?.class?.name )
  report.append( '\n\n' )
  report.append( 'ErrorListener identity:\n' )
  report.append( configuration.getErrorListener() == null ? 'null' : System.identityHashCode( configuration.getErrorListener() ) )
  report.append( '\n\n' )
  report.append( 'Captured ErrorListener output:\n' )
  report.append( errorListener.getOutput() )
  report.append( '\n\n' )
  report.append( '========================================\n' )
  report.append( 'XSLT SOURCE BYTE DIAGNOSTICS\n' )
  report.append( '========================================\n\n' )
  report.append( 'Resource URI:\n' )
  report.append( SaxonDiagnosticConfig.XSLT_RESOURCE )
  report.append( '\n\n' )
  report.append( 'Stylesheet bytes available:\n' )
  report.append( stylesheetBytes != null )
  report.append( '\n\n' )
  report.append( 'Stylesheet size:\n' )
  report.append( stylesheetBytes?.length )
  report.append( '\n\n' )
  report.append( 'Stylesheet SHA-256:\n' )
  report.append( stylesheetHash )
  report.append( '\n\n' )
  report.append( '========================================\n' )
  report.append( 'CURRENT CONFIGURATION\n' )
  report.append( '========================================\n\n' )
  report.append( 'Configuration MessageEmitter:\n' )
  report.append( configuration.getMessageEmitterClass() )
  report.append( '\n\n' )
  report.append( 'Configuration Logger:\n' )
  report.append( configuration.getLogger()?.class?.name )
  report.append( '\n\n' )
  report.append( 'Configuration TraceListener:\n' )
  report.append( configuration.getTraceListener()?.class?.name )
  report.append( '\n\n' )
  report.append( 'Configuration ErrorListener:\n' )
  report.append( configuration.getErrorListener()?.class?.name )
  report.append( '\n\n' )
  report.append( 'Native XSLT Trace Logger:\n' )
  report.append( xsltTraceLogger.class.name )
  report.append( '\n\n' )
  report.append( 'Native XSLT Trace Listener:\n' )
  report.append( xsltTraceListener.class.name )
  report.append( '\n\n' )
  report.append( '========================================\n' )
  report.append( 'END DIAGNOSTICS\n' )
  report.append( '========================================\n' )
  /*
  * ========================================================
  * 19. Attach diagnostic output
  * ========================================================
  */
  def messageLog = messageLogFactory.getMessageLog( message )
  if ( messageLog != null ) {
    /*
    * --------------------------------------------------------
    * Diagnostic report
    * --------------------------------------------------------
    */
    messageLog.addAttachmentAsString( 'saxon-uri-diagnostics.txt', report.toString(), 'text/plain' )
  }
  /*
  * ========================================================
  * 20. Save diagnostic objects
  *
  * These properties are currently not required, just in case...
  * ========================================================
  */
  message.setProperty( SaxonDiagnosticConfig.LOGGER_PROPERTY, traceLogger )
  message.setProperty( SaxonDiagnosticConfig.NATIVE_LOGGER_PROPERTY, xsltTraceLogger )
  message.setProperty( SaxonDiagnosticConfig.LISTENER_PROPERTY, combinedTraceListener )
  message.setProperty( SaxonDiagnosticConfig.NATIVE_LISTENER_PROPERTY, xsltTraceListener )
  message.setProperty( SaxonDiagnosticConfig.ERROR_LISTENER_PROPERTY, errorListener )
  message.setProperty( SaxonDiagnosticConfig.CONFIGURATION_PROPERTY, configuration )
  /*
  * ========================================================
  * 21. Return message
  * ========================================================
  */
  return message
}
