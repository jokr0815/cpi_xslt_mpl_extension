# Useful Functions for XSLT in SAP Integration Suite (CPI)

## Overview

When executing XSLT transformations in SAP Integration Suite (Cloud Integration), you can leverage **built-in SAP Java classes** directly from your stylesheet. You do **not** need to write custom Java code to perform essential runtime tasks like reading/writing headers or fetching exchange properties.

CPI's Saxon XSLT engine can invoke Java classes directly via namespace declarations.

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
