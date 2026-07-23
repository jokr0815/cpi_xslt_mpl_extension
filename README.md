# Useful Functions for XSLT in SAP Integration Suite (CPI)

## Overview

Officially unsupported XSLT java extension to direclty manipulate the SAP Integrations Suite Message Processing Logs (MPL) without groovy directly from XSLT with the following methods (similar to groovy):
- - mpl:setStringProperty($SAP_MessageProcessingLog, $string-key, $string-value)
- mpl:addCustomHeaderProperty($SAP_MessageProcessingLog, $string-key, $string-value, $debugEnabled)
- mpl:addAttachmentAsString($SAP_MessageProcessingLog, $string-filename, $string-content,$string-mimetype, $debugEnabled)

---

## Installation / Setup

To use the `MPLWriter` helper capabilities within your iFlow:

1. Download the compiled [MPLWriter.jar](https://github.com/jokr0815/cpi_xslt_mpl_extension/releases/download/v1.0.0/MPLWriter.jar) from the latest release.
2. Open your Integration Flow (iFlow) in SAP Integration Suite.
3. Under the **Resources** tab, navigate to **Archives** (or **Scripts / Libraries**).
4. Upload `MPLWriter.jar` into your iFlow archive.

---

## Key Built-In Capabilities

By binding standard SAP CPI classes in XSLT, you can:
- **Read Exchange Headers & Properties:** Access dynamic header values or iFlow properties directly inside XPath expressions.
- **Write Custom Header Properties:** Store search-indexed keys for the Message Processing Log (MPL) UI.
- **Access Values During Transformation:** Avoid adding extra Content Modifier steps before or after your XSLT mapping.

---

## XSLT Namespace Declarations

To access built-in SAP functions, declare the corresponding Java packages at the top of your XSLT stylesheet:

```xml
<xsl:stylesheet version="2.0"
    xmlns:xsl="[http://www.w3.org/1999/XSL/Transform](http://www.w3.org/1999/XSL/Transform)"
    xmlns:cpi="java:com.sap.it.api.mapping.MappingContext"
    exclude-result-prefixes="cpi">
