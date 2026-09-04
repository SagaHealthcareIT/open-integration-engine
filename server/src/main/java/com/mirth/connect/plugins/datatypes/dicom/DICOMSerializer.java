/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.plugins.datatypes.dicom;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.codec.binary.Base64InputStream;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.io.IOUtils;
import org.dcm4che2.data.BasicDicomObject;
import org.dcm4che2.data.DicomObject;
import org.dcm4che2.data.Tag;
import org.dcm4che2.io.ContentHandlerAdapter;
import org.dcm4che2.io.DicomInputStream;
import org.dcm4che2.io.SAXWriter;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.mirth.connect.donkey.model.message.MessageSerializer;
import com.mirth.connect.donkey.model.message.MessageSerializerException;
import com.mirth.connect.donkey.util.Base64Util;
import com.mirth.connect.model.converters.DICOMConverter;
import com.mirth.connect.model.converters.DocumentSerializer;
import com.mirth.connect.model.converters.IMessageSerializer;
import com.mirth.connect.model.datatype.SerializerProperties;
import com.mirth.connect.model.util.DefaultMetaData;
import com.mirth.connect.util.ErrorMessageBuilder;

public class DICOMSerializer implements IMessageSerializer {
    private final DocumentSerializer documentSerializer = new DocumentSerializer();
    private final DocumentSerializer parserSerializer = createParserSerializer();

    public DICOMSerializer() {

    }

    public DICOMSerializer(SerializerProperties DICOMProperties) {

    }

    public static Map<String, String> getDefaultProperties() {
        return new HashMap<String, String>();
    }

    public static byte[] removePixelData(byte[] content) throws IOException {
        DicomObject dicomObject = DICOMConverter.byteArrayToDicomObject(content, false);
        dicomObject.remove(Tag.PixelData);

        return DICOMConverter.dicomObjectToByteArray(dicomObject);
    }

    @Override
    public boolean isSerializationRequired(boolean toXml) {
        boolean serializationRequired = false;

        return serializationRequired;
    }

    @Override
    public String transformWithoutSerializing(String message, MessageSerializer outboundSerializer) throws MessageSerializerException {
        return null;
    }

    @Override
    public String fromXML(String source) throws MessageSerializerException {
        if (source == null || source.length() == 0) {
            return org.apache.commons.lang3.StringUtils.EMPTY;
        }

        try {
            // re-parse the xml to Mirth format
            Document document = documentSerializer.fromXML(source);
            Element element = document.getDocumentElement();
            Node node = element.getChildNodes().item(0);

            // change back to <attr> tag for all tags under <dicom> tag
            while (node != null) {
                renameTagToAttr(document, node);
                node = node.getNextSibling();
            }

            NodeList items = document.getElementsByTagName("item");

            // change back to <attr> tag for all tags under <item> tags
            if (items != null) {
                for (int i = 0; i < items.getLength(); i++) {
                    Node itemNode = items.item(i);

                    if (itemNode.getChildNodes() != null) {
                        NodeList itemNodes = itemNode.getChildNodes();

                        for (int j = 0; j < itemNodes.getLength(); j++) {
                            Node nodeItem = itemNodes.item(j);
                            renameTagToAttr(document, nodeItem);
                        }
                    }
                }
            }

            /*
             * dcm4che2 cannot round-trip a sequence whose VR was not in its dictionary. Under
             * implicit VR such an element is read as UN, and DicomInputStream promotes it to a
             * sequence in memory, but the streaming SAXWriter has already written the wire VR, so
             * toXML emits vr="UN" around structured <item> children. ContentHandlerAdapter enters
             * its sequence state only for vr="SQ" and otherwise expects fragments, so it throws
             * IllegalStateException("state:EXPECT_FRAG") at the first element inside the item.
             *
             * Present those elements the way DicomInputStream already reads them.
             */
            NodeList attrElements = document.getElementsByTagName("attr");

            for (int i = 0; i < attrElements.getLength(); i++) {
                Element attrElement = (Element) attrElements.item(i);
                removeWhitespaceBetweenItems(attrElement);

                if (attrElement.getAttribute("vr").equals("UN") && hasDatasetItem(attrElement)) {
                    attrElement.setAttribute("vr", "SQ");
                }
            }

            // find the charset
            String charset = null;
            Element charsetElement = (Element) document.getElementsByTagName("tag00080005").item(0);

            if (charsetElement != null) {
                charset = charsetElement.getNodeValue();
            } else {
                charset = "utf-8";
            }

            // parse the Document into a DicomObject
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            SAXParser parser = factory.newSAXParser();
            DicomObject dicomObject = new BasicDicomObject();
            ContentHandlerAdapter contentHandler = new ContentHandlerAdapter(dicomObject);
            byte[] documentBytes = parserSerializer.toXML(document).trim().getBytes(charset);
            parser.parse(new InputSource(new ByteArrayInputStream(documentBytes)), contentHandler);
            return StringUtils.newStringUsAscii(Base64Util.encodeBase64(DICOMConverter.dicomObjectToByteArray(dicomObject)));
        } catch (Exception e) {
            throw new MessageSerializerException("Error converting XML to DICOM", e, ErrorMessageBuilder.buildErrorMessage(this.getClass().getSimpleName(), "Error converting XML to DICOM", e));
        }
    }

    @Override
    public String toXML(String source) throws MessageSerializerException {
        try {
            byte[] encodedMessage = org.apache.commons.codec.binary.StringUtils.getBytesUsAscii(source);

            StringWriter output = new StringWriter();
            DicomInputStream dis = new DicomInputStream(new BufferedInputStream(new Base64InputStream(new ByteArrayInputStream(encodedMessage))));
            /*
             * This parameter was added in dcm4che 2.0.28. We use it to retain the memory allocation
             * behavior from 2.0.25. http://www.mirthcorp.com/community/issues/browse/MIRTH-2166
             * http://www.dcm4che.org/jira/browse/DCM-554
             */
            dis.setAllocateLimit(-1);

            try {
            	TransformerFactory tf = TransformerFactory.newInstance();
            	tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            	tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                SAXTransformerFactory factory = (SAXTransformerFactory) tf;
                TransformerHandler handler = factory.newTransformerHandler();
                handler.getTransformer().setOutputProperty(OutputKeys.INDENT, "no");
                handler.setResult(new StreamResult(output));

                final SAXWriter writer = new SAXWriter(handler, null);
                dis.setHandler(writer);
                dis.readDicomObject(new BasicDicomObject(), -1);
                String serializedDicomObject = output.toString();

                // rename the "attr" element to the tag ID
                Document document = documentSerializer.fromXML(serializedDicomObject);
                NodeList attrElements = document.getElementsByTagName("attr");

                for (int i = 0; i < attrElements.getLength(); i++) {
                    Element attrElement = (Element) attrElements.item(i);
                    renameAttrToTag(document, attrElement);
                }

                return documentSerializer.toXML(document);
            } catch (Exception e) {
                throw e;
            } finally {
                IOUtils.closeQuietly(dis);
                IOUtils.closeQuietly(output);

                if (dis != null) {
                    dis.close();
                }
            }
        } catch (Exception e) {
            throw new MessageSerializerException("Error converting DICOM to XML", e, ErrorMessageBuilder.buildErrorMessage(this.getClass().getSimpleName(), "Error converting DICOM to XML", e));
        }
    }

    private void renameAttrToTag(Document document, Node node) throws DOMException {
        if (node.getNodeName().equals("attr")) {
            String tag = node.getAttributes().getNamedItem("tag").getNodeValue();

            if (!tag.equals("?")) {
                document.renameNode(node, null, "tag" + tag);
            }
        }
    }

    private void renameTagToAttr(Document document, Node node) throws DOMException {
        NamedNodeMap attr = node.getAttributes();

        if (attr != null) {
            Node tagAttr = attr.getNamedItem("tag");

            if (tagAttr != null) {
                String tag = "tag" + tagAttr.getNodeValue();

                if (!tag.equals("?") && tag.equals(node.getNodeName())) {
                    document.renameNode(node, null, "attr");
                }
            }
        }
    }

    /**
     * Serializes fromXML's document for the SAX parser, which is its only reader. Unindented
     * because ContentHandlerAdapter reads the indentation between an element and its items as part
     * of the element's value, putting back what removeWhitespaceBetweenItems took out.
     */
    private static DocumentSerializer createParserSerializer() {
        DocumentSerializer serializer = new DocumentSerializer();
        serializer.setIndent(false);
        return serializer;
    }

    /**
     * Drops the whitespace-only text nodes among an element's items. fromXML's input arrives
     * indented, and writing the document back out unindented does not remove text nodes already in
     * it, so they have to come out of the document itself.
     *
     * ContentHandlerAdapter reads that text as part of the element's value. onEndItem resets its
     * byte buffer but onStartItem does not, so only the text before the first item survives, and
     * under a UN fragment container - whose values are read as literal bytes - it is prepended to
     * fragment zero. OB and OW parse theirs as numeric tokens instead, and are unaffected.
     *
     * Only elements that have items are touched. A UN element with no items and an all-space value
     * is indistinguishable by its text alone, which is why the test is for items; there the
     * whitespace is the value.
     */
    private void removeWhitespaceBetweenItems(Element element) {
        boolean hasItems = false;

        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("item")) {
                hasItems = true;
                break;
            }
        }

        if (!hasItems) {
            return;
        }

        Node child = element.getFirstChild();

        while (child != null) {
            Node next = child.getNextSibling();

            if (child.getNodeType() == Node.TEXT_NODE && child.getNodeValue().trim().isEmpty()) {
                element.removeChild(child);
            }

            child = next;
        }
    }

    /**
     * Whether any item under this element contains elements. A fragment cannot: the parser rejects
     * elements in fragment state. So the container is a sequence.
     */
    private boolean hasDatasetItem(Element element) {
        for (Node item = element.getFirstChild(); item != null; item = item.getNextSibling()) {
            if (item.getNodeType() != Node.ELEMENT_NODE || !item.getNodeName().equals("item")) {
                continue;
            }

            for (Node child = item.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public Map<String, Object> getMetaDataFromMessage(String message) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(DefaultMetaData.VERSION_VARIABLE_MAPPING, "");
        map.put(DefaultMetaData.TYPE_VARIABLE_MAPPING, "DICOM");
        return map;
    }

    @Override
    public void populateMetaData(String message, Map<String, Object> map) {}

    @Override
    public String toJSON(String message) throws MessageSerializerException {
        return null;
    }

    @Override
    public String fromJSON(String message) throws MessageSerializerException {
        return null;
    }
}
