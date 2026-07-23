# Useful Functions for XSLT in SAP Integration Suite (CPI)

## Overview

When executing XSLT transformations in SAP Integration Suite (Cloud Integration), you can leverage **built-in SAP Java classes** directly from your stylesheet. You do **not** need to write, compile, or upload custom Java `.jar` files into your iFlow or Script Collection to perform essential runtime tasks like reading/writing headers or fetching exchange properties.

CPI's Saxon XSLT engine can invoke these standard Java classes directly via namespace declarations.

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
