# Useful Functions for XSLT in SAP Integration Suite (CPI)

## Overview

Officially unsupported XSLT java extension to directly manipulate the SAP Integration Suite Message Processing Logs (MPL) without groovy directly from XSLT with the following methods (similar to groovy):
- `mpl:setStringProperty($SAP_MessageProcessingLog, $string-key, $string-value)`
- `mpl:addCustomHeaderProperty($SAP_MessageProcessingLog, $string-key, $string-value, $debugEnabled)`
- `mpl:addAttachmentAsString($SAP_MessageProcessingLog, $string-filename, $string-content, $string-mimetype, $debugEnabled)`

---

## What’s New in Release v1.0.2

* **Decoupled Architecture:** Extracted core tracing utilities into `MPLWriter.jar` to separate SAP CPI runtime dependencies from standard mapping definitions.
* **Direct MPL Integration:** Captures `xsl:message` output directly into the Message Processing Log (MPL) attachments using a custom `MessageEmitter`.
* **Deep Diagnostic Hooking:** Added runtime reflection helpers to dynamically inspect and bind custom listeners (`TraceListener`, `ErrorListener`, and `CustomTraceLogger`) to pre-compiled Saxon endpoints.

---

## Installation / Setup

To use the `MPLWriter` helper capabilities within your iFlow:

1. Download the compiled [MPLWriter.jar](https://github.com/jokr0815/cpi_xslt_mpl_extension/releases/latest/download/MPLWriter.jar) from the latest release.
2. Open your Integration Flow (iFlow) in SAP Integration Suite.
3. Under the **Resources** tab, navigate to **Archives** (or **Scripts / Libraries**).
4. Upload `MPLWriter.jar` into your iFlow archive.

---

## Key Built-In Capabilities & Compatibility

By binding standard SAP CPI classes in XSLT, you can:
- **Read Exchange Headers & Properties:** Access dynamic header values or iFlow properties directly inside XPath expressions.
- **Write Custom Header Properties:** Store search-indexed keys for the Message Processing Log (MPL) UI.
- **Access Values During Transformation:** Avoid adding extra Content Modifier steps before or after your XSLT mapping.

> **Note on Runtime Class Visibility:** This library avoids direct references to `com.sap.gateway.ip.core.customdev.util.Message` to prevent `ClassNotFoundException` errors caused by OSGi bundle visibility constraints in the XSLT engine runtime.

---

## XSLT Namespace Declarations

To access built-in SAP functions, declare the corresponding Java packages at the top of your XSLT stylesheet:

```xml
<xsl:stylesheet version="2.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:cpi="java:com.sap.it.api.mapping.MappingContext"
    exclude-result-prefixes="cpi">
```

---

## Legal Disclaimer & License

```text
MIT License

Copyright (c) HO2 Systemberatung GmbH

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

> **Disclaimer:** This code is provided as-is without any warranty for correctness, completeness, or fitness for a particular purpose. It relies on non-public runtime structures resolved via reflection. **Please note that this implementation is proof-of-concept software: it is NOT production-ready and NOT thread-safe.** Mutating shared Saxon endpoints and listeners at runtime under high concurrent load can cause state contamination across execution threads. This solution is completely independent and/or completely unsupported by SAP, by myself, and/or my company — use it strictly at your own risk.
