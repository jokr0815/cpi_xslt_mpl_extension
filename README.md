# How to Use Custom XSLT Java Extension "MPLWriter" in SAP Integration Suite (CPI)

## Overview

When developing transformations in SAP Integration Suite (Cloud Integration), developers frequently need to write entries to the **Message Processing Log (MPL)**—such as setting Custom Header Properties or attaching intermediate payloads for tracing—directly during the XSLT execution step.

Because standard XSLT 2.0 / 3.0 elements cannot access CPI-specific runtime services natively, a custom **Java Extension JAR** is used. Saxon (CPI's XSLT engine) invokes these Java methods directly from the stylesheet.

---

## Prerequisites & Setup Strategy

To use Java functions inside your XSLT mappings, follow these deployment options:

### Option A: Local iFlow Deployment (Recommended for Single iFlows)
1. Compile your Java project into a `.jar` archive (e.g., `mpl-xslt-extension.jar`).
2. Open your Integration Flow in SAP Integration Suite.
3. Navigate to **Resources** > **Archive** > **Add** > **Jar**.
4. Upload your `.jar` file into the iFlow's local bundle context.

### Option B: Script Collection Deployment (Recommended for Enterprise Reuse)
1. Navigate to your Integration Package in SAP Integration Suite.
2. Create or open a **Script Collection** resource.
3. Upload the `.jar` file into the Script Collection's **Archives** section.
4. In your iFlow, add the Script Collection under **Resources** > **Script Collection References**.

---

## Step-by-Step Implementation Guide

<Sequence>
  <Step title="1. Add XSLT Mapping to your iFlow" subtitle="Integration Flow Design">
    Place an **XSLT Mapping** step into your Integration Flow processing pipeline where the payload transformation occurs.
  </Step>
  <Step title="2. Declare Java Extension Namespace in XSLT" subtitle="XSLT Stylesheet Header">
    Declare a custom namespace at the top of your `.xsl` file pointing to the fully qualified Java class name:
    `xmlns:mplWriter="java:com.sap.custom.xslt.MPLWriter"`
  </Step>
  <Step title="3. Define Exchange Parameter Passing" subtitle="Runtime Injection">
    In your XSLT, declare a global parameter to receive the CPI log runtime object passed by the Camel exchange framework:
    `<xsl:param name="sapLog"/>`
  </Step>
  <Step title="4. Invoke Extension Functions in XSLT" subtitle="XPath Execution">
    Call the extension functions inside your templates using standard XPath variable evaluation.
  </Step>
</Sequence>

---

## XSLT Implementation Example

Below is a lean template demonstrating how to invoke the custom Java methods once the JAR is imported into your iFlow:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0"
    xmlns:xsl="[http://www.w3.org/1999/XSL/Transform](http://www.w3.org/1999/XSL/Transform)"
    xmlns:mplWriter="java:com.sap.custom.xslt.MPLWriter"
    exclude-result-prefixes="mplWriter">

    <!-- Global parameter automatically bound to the CPI Exchange log object -->
    <xsl:param name="sapLog"/>

    <xsl:template match="/">
        
        <!-- 1. Add Custom Header Property for indexed search in CPI UI -->
        <xsl:variable name="hdrStatus" 
                      select="mplWriter:addCustomHeaderProperty($sapLog, 'OrderID', //Order/ID/text(), true())"/>

        <!-- 2. Write an attachment directly to the Message Processing Log -->
        <xsl:variable name="attStatus" 
                      select="mplWriter:addAttachmentAsString($sapLog, 'SourcePayload.xml', string(.), 'text/xml', false())"/>

        <!-- Standard XSLT Transformation logic follows -->
        <TargetRoot>
            <xsl:apply-templates select="@*|node()"/>
        </TargetRoot>

    </xsl:template>

    <xsl:template match="@*|node()">
        <xsl:copy>
            <xsl:apply-templates select="@*|node()"/>
        </xsl:copy>
    </xsl:template>

</xsl:stylesheet>
