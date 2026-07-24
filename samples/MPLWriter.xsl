<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:saxon="http://saxon.sf.net/" xmlns:mpl="java:script.MPLWriter" exclude-result-prefixes="xs saxon mpl">
  <!-- SAP CPI injects the Message Processing Log object -->
  <xsl:param name="SAP_MessageProcessingLog"/>
  <!-- Enable MPL debug -->
  <xsl:param name="debugEnabled" select="true()"/>
  <!-- Saxon template execution tracing -->
  <xsl:mode on-multiple-match="use-last" use-accumulators="itemCount totalQuantity" saxon:trace="yes"/>
  <!-- Count items -->
  <xsl:accumulator name="itemCount" as="xs:integer" initial-value="0" saxon:trace="yes">
    <xsl:accumulator-rule match="Item" select="$value + 1"/>
  </xsl:accumulator>
  <!-- Sum quantities -->
  <xsl:accumulator name="totalQuantity" as="xs:integer" initial-value="0" saxon:trace="yes">
    <xsl:accumulator-rule match="Quantity/text()" select="$value + xs:integer(.)"/>
  </xsl:accumulator>
  <xsl:template match="/">
    <!-- Check if MessageProcessingLog is missing or null -->
    <xsl:if test="empty($SAP_MessageProcessingLog)">
      <xsl:sequence select="error(xs:QName('mpl:MissingMPL'), 'Please include a simple groovy script before the xslt like that: import com.sap....Message&#10; def Message processData(Message m){return m;}')"/>
    </xsl:if>
    <xsl:variable name="orderId" select="string(Order/Header/ID)"/>
    <xsl:variable name="customer" select="string(Order/Header/CustomerNumber)"/>
    <!-- MPL properties -->
    <xsl:variable name="status1" select="mpl:setStringProperty($SAP_MessageProcessingLog,'XSLT_STATUS','STARTED')"/>
    <xsl:variable name="orderProperty" select="mpl:setStringProperty($SAP_MessageProcessingLog,'ORDER_ID',$orderId)"/>
    <!-- MPL Custom Header Properties -->
    <xsl:variable name="header1" select="mpl:addCustomHeaderProperty($SAP_MessageProcessingLog,'PROCESSING_TYPE','XSLT3_DEMO',$debugEnabled)"/>
    <xsl:variable name="header2" select="mpl:addCustomHeaderProperty($SAP_MessageProcessingLog,'CUSTOMER_NUMBER',$customer,$debugEnabled)"/>
    <!-- HTML attachment -->
    <xsl:variable name="html" select="
      mpl:addAttachmentAsString(
        $SAP_MessageProcessingLog,
        'order-summary.html',
        concat(
          '&lt;html&gt;',
          '&lt;body&gt;',
          '&lt;h1&gt;SAP CPI XSLT 3.0 Demo&lt;/h1&gt;',
          '&lt;h2&gt;Order ',
          $orderId,
          '&lt;/h2&gt;',
          '&lt;p&gt;Customer: ',
          $customer,
          '&lt;/p&gt;',
          '&lt;table border=&quot;1&quot;&gt;',
          '&lt;tr&gt;&lt;th&gt;Material&lt;/th&gt;&lt;th&gt;Quantity&lt;/th&gt;&lt;/tr&gt;',
          string-join(
            for $i in Order/Items/Item
            return concat(
              '&lt;tr&gt;',
              '&lt;td&gt;',
              $i/Material,
              '&lt;/td&gt;',
              '&lt;td&gt;',
              $i/Quantity,
              '&lt;/td&gt;',
              '&lt;/tr&gt;'
            ),
            ''
          ),
          '&lt;/table&gt;',
          '&lt;p&gt;Items: ',
          accumulator-after('itemCount'),
          '&lt;/p&gt;',
          '&lt;p&gt;Total Quantity: ',
          accumulator-after('totalQuantity'),
          '&lt;/p&gt;',
          '&lt;/body&gt;',
          '&lt;/html&gt;'
        ),
        'text/html',
        $debugEnabled
      )
    "/>
    <!-- Store calculated values -->
    <xsl:variable name="itemCount" select="mpl:setStringProperty($SAP_MessageProcessingLog,'ITEM_COUNT',string(accumulator-after('itemCount')))"/>
    <xsl:variable name="quantity" select="mpl:setStringProperty($SAP_MessageProcessingLog,'TOTAL_QUANTITY',string(accumulator-after('totalQuantity')))"/>
    <xsl:variable name="status2" select="mpl:setStringProperty($SAP_MessageProcessingLog,'XSLT_STATUS','COMPLETED')"/>
    <!-- XML result -->
    <OrderResult>
      <Header>
        <ID>
          <xsl:value-of select="$orderId"/>
        </ID>
        <Customer>
          <xsl:value-of select="$customer"/>
        </Customer>
      </Header>
      <Items>
        <xsl:apply-templates select="Order/Items/Item"/>
      </Items>
      <Summary>
        <ItemCount>
          <xsl:value-of select="accumulator-after('itemCount')"/>
        </ItemCount>
        <TotalQuantity>
          <xsl:value-of select="accumulator-after('totalQuantity')"/>
        </TotalQuantity>
      </Summary>
    </OrderResult>
  </xsl:template>
  <!-- Item transformation -->
  <xsl:template match="Item">
    <Item>
      <Material>
        <xsl:value-of select="Material"/>
      </Material>
      <Quantity>
        <xsl:value-of select="Quantity"/>
      </Quantity>
    </Item>
  </xsl:template>
</xsl:stylesheet>
