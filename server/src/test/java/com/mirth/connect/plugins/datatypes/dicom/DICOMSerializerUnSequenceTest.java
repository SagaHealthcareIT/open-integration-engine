// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Saga IT, LLC

package com.mirth.connect.plugins.datatypes.dicom;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.codec.binary.Base64;
import org.dcm4che2.data.BasicDicomObject;
import org.dcm4che2.data.DicomElement;
import org.dcm4che2.data.DicomObject;
import org.dcm4che2.data.Tag;
import org.dcm4che2.data.UID;
import org.dcm4che2.data.VR;
import org.dcm4che2.io.DicomOutputStream;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.mirth.connect.model.converters.DICOMConverter;

/**
 * Round-trip tests for sequences whose VR is not in dcm4che2's dictionary. Under implicit VR those
 * are read as UN, and toXML emits the wire VR around structured items, which ContentHandlerAdapter
 * refuses. Before the fromXML fix any such message threw
 * IllegalStateException("state:EXPECT_FRAG").
 *
 * The reference in every case is DICOMConverter's own output - what the engine emits for the same
 * message with no transformer attached.
 */
public class DICOMSerializerUnSequenceTest {

    private static final int PRIVATE_SEQUENCE_TAG = 0x0029E131;
    private static final int NESTED_PRIVATE_SEQUENCE_TAG = 0x00291140;
    private static final int PRIVATE_BLOB_TAG = 0x00291003;

    @Test
    public void testUnSequenceRoundTrip() throws Exception {
        byte[] original = writeDicomFile(unSequenceObject(), UID.ImplicitVRLittleEndian);

        DICOMSerializer serializer = new DICOMSerializer();
        String xml = serializer.toXML(Base64.encodeBase64String(original));

        // Guard against a vacuous pass: the XML must carry the shape that breaks the parser.
        Element sequenceElement = findElementByTag(xml, "0029E131");
        assertNotNull("private sequence missing from XML", sequenceElement);
        assertEquals("UN", sequenceElement.getAttribute("vr"));
        assertTrue("expected a structured item", sequenceElement.getElementsByTagName("item").getLength() > 0);

        assertRoundTripMatchesPassThrough(serializer, original);

        DicomObject item = readBack(serializer, original).getNestedDicomObject(PRIVATE_SEQUENCE_TAG);
        assertNotNull("private sequence lost", item);
        assertEquals("VALUE1", item.getString(Tag.CodeValue));
    }

    @Test
    public void testExplicitVrWireUnSequenceRoundTrip() throws Exception {
        // Explicit VR carrying UN with undefined length on the wire, which a PACS produces when it
        // re-encodes an implicit-VR object whose private VR it does not know. dcm4che2 cannot be
        // asked to write this, so the dataset is hand-built.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(new byte[] { 0x29, 0x00, 0x31, (byte) 0xE1, 'U', 'N', 0, 0, -1, -1, -1, -1 });
        body.write(new byte[] { (byte) 0xFE, (byte) 0xFF, 0x00, (byte) 0xE0, -1, -1, -1, -1 });
        body.write(new byte[] { 0x08, 0x00, 0x00, 0x01, 6, 0, 0, 0, 'V', 'A', 'L', 'U', 'E', '1' });
        body.write(new byte[] { (byte) 0xFE, (byte) 0xFF, 0x0D, (byte) 0xE0, 0, 0, 0, 0 });
        body.write(new byte[] { (byte) 0xFE, (byte) 0xFF, (byte) 0xDD, (byte) 0xE0, 0, 0, 0, 0 });
        byte[] original = handCraftedFile(body.toByteArray());

        DICOMSerializer serializer = new DICOMSerializer();
        assertEquals("UN", findElementByTag(serializer.toXML(Base64.encodeBase64String(original)), "0029E131").getAttribute("vr"));
        assertRoundTripMatchesPassThrough(serializer, original);

        DicomObject item = readBack(serializer, original).getNestedDicomObject(PRIVATE_SEQUENCE_TAG);
        assertNotNull("private sequence lost", item);
        assertEquals("VALUE1", item.getString(Tag.CodeValue));
    }

    @Test
    public void testUnSequenceNestedInsideStandardSequence() throws Exception {
        // The private sequence sits inside an item of a standard SQ, so it has to be found by the
        // document-wide walk rather than only at the top level.
        BasicDicomObject inner = new BasicDicomObject();
        inner.putString(Tag.CodeMeaning, VR.LO, "MEANING");
        BasicDicomObject outerItem = new BasicDicomObject();
        outerItem.putString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.4");
        outerItem.putNestedDicomObject(NESTED_PRIVATE_SEQUENCE_TAG, inner);
        BasicDicomObject dcm = baseObject("1.2.3.4.5.6.7.8.11");
        dcm.putNestedDicomObject(Tag.ReferencedSeriesSequence, outerItem);

        byte[] original = writeDicomFile(dcm, UID.ImplicitVRLittleEndian);
        DICOMSerializer serializer = new DICOMSerializer();
        assertEquals("UN", findElementByTag(serializer.toXML(Base64.encodeBase64String(original)), "00291140").getAttribute("vr"));
        assertRoundTripMatchesPassThrough(serializer, original);

        DicomObject outer = readBack(serializer, original).getNestedDicomObject(Tag.ReferencedSeriesSequence);
        assertNotNull("outer sequence lost", outer);
        assertEquals("MEANING", outer.getNestedDicomObject(NESTED_PRIVATE_SEQUENCE_TAG).getString(Tag.CodeMeaning));
    }

    @Test
    public void testEncapsulatedPixelDataUntouched() throws Exception {
        BasicDicomObject dcm = baseObject("1.2.3.4.5.6.7.8.10");
        byte[] frame = new byte[] { 9, 8, 7, 6, 5, 4 };
        DicomElement pixelData = dcm.putFragments(Tag.PixelData, VR.OB, false);
        pixelData.addFragment(new byte[0]); // basic offset table
        pixelData.addFragment(frame);

        byte[] original = writeDicomFile(dcm, UID.JPEGBaseline1);
        DICOMSerializer serializer = new DICOMSerializer();
        assertEquals("OB", findElementByTag(serializer.toXML(Base64.encodeBase64String(original)), "7FE00010").getAttribute("vr"));
        assertRoundTripMatchesPassThrough(serializer, original);

        DicomElement result = readBack(serializer, original).get(Tag.PixelData);
        assertTrue("pixel data no longer encapsulated", result.hasFragments());
        assertEquals("basic offset table no longer empty", 0, result.getFragment(0).length);
        assertArrayEquals(frame, result.getFragment(1));
    }

    @Test
    public void testPrivateBlobUntouched() throws Exception {
        // A private element whose items hold encoded bytes is a fragment container, not a sequence.
        // It round-trips today and must keep doing so.
        BasicDicomObject dcm = baseObject("1.2.3.4.5.6.7.8.13");
        DicomElement fragments = dcm.putFragments(PRIVATE_BLOB_TAG, VR.UN, false);
        fragments.addFragment(new byte[] { 'A', 'B', 'C', 'D' });
        fragments.addFragment(new byte[] { 'E', 'F', 'G', 'H' });

        byte[] original = writeDicomFile(dcm, UID.ImplicitVRLittleEndian);
        DICOMSerializer serializer = new DICOMSerializer();
        assertEquals("UN", findElementByTag(serializer.toXML(Base64.encodeBase64String(original)), "00291003").getAttribute("vr"));
        assertRoundTripMatchesPassThrough(serializer, original);

        DicomElement result = readBack(serializer, original).get(PRIVATE_BLOB_TAG);
        assertTrue("blob was promoted to a sequence", result.hasFragments());
        assertArrayEquals(new byte[] { 'A', 'B', 'C', 'D' }, result.getFragment(0));
        assertArrayEquals(new byte[] { 'E', 'F', 'G', 'H' }, result.getFragment(1));
    }

    @Test
    public void testBlobBesideAPromotedSequenceIsUnharmed() throws Exception {
        // What vendors actually send: a private sequence and a private blob in one dataset. The
        // message used to fail on the sequence, so the blob was never reached; now that it parses,
        // the blob has to come back exactly as the non-XML path returns it, and its fragment bytes
        // must not pick up the XML's indentation.
        BasicDicomObject dcm = unSequenceObject();
        DicomElement fragments = dcm.putFragments(PRIVATE_BLOB_TAG, VR.UN, false);
        fragments.addFragment(new byte[] { 'A', 'B', 'C', 'D' });
        fragments.addFragment(new byte[] { 'E', 'F', 'G', 'H' });

        byte[] original = writeDicomFile(dcm, UID.ImplicitVRLittleEndian);
        DICOMSerializer serializer = new DICOMSerializer();
        assertRoundTripMatchesPassThrough(serializer, original);

        DicomObject result = readBack(serializer, original);
        assertNotNull("private sequence lost", result.getNestedDicomObject(PRIVATE_SEQUENCE_TAG));
        assertArrayEquals(new byte[] { 'A', 'B', 'C', 'D' }, result.get(PRIVATE_BLOB_TAG).getFragment(0));
    }

    @Test
    public void testNonUnContainerIsNotPromoted() throws Exception {
        // Only UN is promoted. Handing the parser a sequence under any other VR emits bytes a
        // reader rejects, which is worse than the failure it replaces.
        Document document = parse(new DICOMSerializer().toXML(Base64.encodeBase64String(writeDicomFile(unSequenceObject(), UID.ImplicitVRLittleEndian))));
        findElementByTag(document, "0029E131").setAttribute("vr", "LO");

        try {
            new DICOMSerializer().fromXML(serialize(document));
            fail("a non-UN container was promoted");
        } catch (Exception e) {
            Throwable cause = e;

            while (cause.getCause() != null) {
                cause = cause.getCause();
            }

            // dcm4che2 refusing to build fragments under a VR that cannot hold them, which is the
            // pre-existing behaviour for this document.
            assertTrue("unexpected failure: " + cause, cause instanceof UnsupportedOperationException);
        }
    }

    @Test
    public void testMixedItemsPromoteAndEmptyTheTextItems() throws Exception {
        // Edge of the rule, pinned rather than left to chance: when a container mixes encoded-text
        // items with structured ones, promoting it empties the text items, because the parser
        // ignores text under a sequence. dcm4che2 cannot read such a container off the wire in the
        // first place, so this is reachable only from hand-authored XML.
        Document document = parse(new DICOMSerializer().toXML(Base64.encodeBase64String(writeDicomFile(unSequenceObject(), UID.ImplicitVRLittleEndian))));
        Element sequenceElement = findElementByTag(document, "0029E131");
        Element textItem = document.createElement("item");
        textItem.setAttribute("len", "8");
        textItem.setTextContent("AQIDBA==");
        sequenceElement.insertBefore(textItem, sequenceElement.getElementsByTagName("item").item(0));

        DicomObject result = DICOMConverter.byteArrayToDicomObject(Base64.decodeBase64(new DICOMSerializer().fromXML(serialize(document))), false);
        DicomElement resultElement = result.get(PRIVATE_SEQUENCE_TAG);
        assertEquals(VR.SQ, resultElement.vr());
        assertEquals(2, resultElement.countItems());
        assertTrue("text item should be empty", resultElement.getDicomObject(0).isEmpty());
        assertEquals("VALUE1", resultElement.getDicomObject(1).getString(Tag.CodeValue));
    }

    @Test
    public void testWhitespaceOnlyValueIsPreserved() throws Exception {
        // A private element whose whole value is whitespace. SAXWriter trims a string VR's value
        // away, but writes a UN value through literally, so this arrives in fromXML's document as a
        // text node indistinguishable from indentation. It is the element's value and has to
        // survive the whitespace removal that runs for the sake of fragment containers.
        BasicDicomObject dcm = baseObject("1.2.3.4.5.6.7.8.14");
        dcm.putBytes(PRIVATE_BLOB_TAG, VR.UN, "    ".getBytes(StandardCharsets.US_ASCII));

        byte[] original = writeDicomFile(dcm, UID.ImplicitVRLittleEndian);
        DICOMSerializer serializer = new DICOMSerializer();
        Element valueElement = findElementByTag(serializer.toXML(Base64.encodeBase64String(original)), "00291003");
        assertEquals("UN", valueElement.getAttribute("vr"));
        assertEquals("whitespace value did not reach the document", "    ", valueElement.getTextContent());

        assertRoundTripMatchesPassThrough(serializer, original);
        assertArrayEquals(new byte[] { ' ', ' ', ' ', ' ' }, readBack(serializer, original).get(PRIVATE_BLOB_TAG).getBytes());
    }

    @Test
    public void testUnSequenceNestedInsideUnSequence() throws Exception {
        // Vendors nest private sequences inside private sequences, and under implicit VR every
        // level reads as UN, so promoting the outer one has to leave the inner one promotable.
        BasicDicomObject inner = new BasicDicomObject();
        inner.putString(Tag.CodeMeaning, VR.LO, "MEANING");
        BasicDicomObject outerItem = new BasicDicomObject();
        outerItem.putString(Tag.CodeValue, VR.SH, "VALUE1");
        outerItem.putNestedDicomObject(NESTED_PRIVATE_SEQUENCE_TAG, inner);
        BasicDicomObject dcm = baseObject("1.2.3.4.5.6.7.8.15");
        dcm.putNestedDicomObject(PRIVATE_SEQUENCE_TAG, outerItem);

        byte[] original = writeDicomFile(dcm, UID.ImplicitVRLittleEndian);
        DICOMSerializer serializer = new DICOMSerializer();
        String xml = serializer.toXML(Base64.encodeBase64String(original));
        assertEquals("UN", findElementByTag(xml, "0029E131").getAttribute("vr"));
        assertEquals("UN", findElementByTag(xml, "00291140").getAttribute("vr"));

        assertRoundTripMatchesPassThrough(serializer, original);

        DicomObject outer = readBack(serializer, original).getNestedDicomObject(PRIVATE_SEQUENCE_TAG);
        assertNotNull("outer private sequence lost", outer);
        assertEquals("MEANING", outer.getNestedDicomObject(NESTED_PRIVATE_SEQUENCE_TAG).getString(Tag.CodeMeaning));
    }

    @Test
    public void testToXmlStaysIndented() throws Exception {
        // toXML's output is what transformer scripts and the message browser display, so it stays
        // indented. Only the document handed to the SAX parser inside fromXML is written flat,
        // which is why there are two serializers rather than one with indenting turned off.
        String xml = new DICOMSerializer().toXML(Base64.encodeBase64String(writeDicomFile(unSequenceObject(), UID.ImplicitVRLittleEndian)));
        assertTrue("toXML output is no longer indented", xml.replace("\r\n", "\n").contains("\n    <tag"));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private BasicDicomObject baseObject(String sopInstanceUid) {
        BasicDicomObject dcm = new BasicDicomObject();
        dcm.putString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage);
        dcm.putString(Tag.SOPInstanceUID, VR.UI, sopInstanceUid);
        return dcm;
    }

    private BasicDicomObject unSequenceObject() {
        BasicDicomObject dcm = baseObject("1.2.3.4.5.6.7.8.9");
        dcm.putString(Tag.PatientName, VR.PN, "TEST^PATIENT");
        BasicDicomObject item = new BasicDicomObject();
        item.putString(Tag.CodeValue, VR.SH, "VALUE1");
        dcm.putNestedDicomObject(PRIVATE_SEQUENCE_TAG, item);
        return dcm;
    }

    private byte[] writeDicomFile(BasicDicomObject dcm, String transferSyntaxUid) throws Exception {
        dcm.initFileMetaInformation(transferSyntaxUid);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (DicomOutputStream dos = new DicomOutputStream(baos)) {
            dos.writeDicomFile(dcm);
        }

        return baos.toByteArray();
    }

    /** A file with real file meta and explicit VR, followed by the given raw dataset bytes. */
    private byte[] handCraftedFile(byte[] datasetBody) throws Exception {
        String sopInstanceUid = "1.2.3.4.5.6.7.8.12";
        ByteArrayOutputStream dataset = new ByteArrayOutputStream();
        writeExplicitUid(dataset, 0x0008, 0x0016, UID.SecondaryCaptureImageStorage);
        writeExplicitUid(dataset, 0x0008, 0x0018, sopInstanceUid);
        dataset.write(datasetBody);

        BasicDicomObject fileMeta = new BasicDicomObject();
        fileMeta.initFileMetaInformation(UID.SecondaryCaptureImageStorage, sopInstanceUid, UID.ExplicitVRLittleEndian);
        ByteArrayOutputStream file = new ByteArrayOutputStream();

        try (DicomOutputStream dos = new DicomOutputStream(file)) {
            dos.writeFileMetaInformation(fileMeta);
            dos.write(dataset.toByteArray());
        }

        return file.toByteArray();
    }

    private void writeExplicitUid(ByteArrayOutputStream out, int group, int element, String uid) {
        byte[] value = uid.getBytes(StandardCharsets.US_ASCII);
        int length = value.length + (value.length % 2);
        out.write(group & 0xFF);
        out.write(group >> 8);
        out.write(element & 0xFF);
        out.write(element >> 8);
        out.write('U');
        out.write('I');
        out.write(length & 0xFF);
        out.write(length >> 8);
        out.write(value, 0, value.length);

        if (length > value.length) {
            out.write(0);
        }
    }

    /** The whole object must match what the engine emits for the same message with no transformer. */
    private void assertRoundTripMatchesPassThrough(DICOMSerializer serializer, byte[] original) throws Exception {
        byte[] viaXml = Base64.decodeBase64(serializer.fromXML(serializer.toXML(Base64.encodeBase64String(original))));
        byte[] passThrough = DICOMConverter.dicomObjectToByteArray(DICOMConverter.byteArrayToDicomObject(original, false));
        assertArrayEquals("transformer path diverged from pass-through", passThrough, viaXml);
    }

    private DicomObject readBack(DICOMSerializer serializer, byte[] original) throws Exception {
        return DICOMConverter.byteArrayToDicomObject(Base64.decodeBase64(serializer.fromXML(serializer.toXML(Base64.encodeBase64String(original)))), false);
    }

    private Element findElementByTag(String xml, String tag) throws Exception {
        return findElementByTag(parse(xml), tag);
    }

    private Element findElementByTag(Document document, String tag) {
        NodeList elements = document.getElementsByTagName("*");

        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);

            if (tag.equalsIgnoreCase(element.getAttribute("tag"))) {
                return element;
            }
        }

        return null;
    }

    private Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private String serialize(Document document) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }
}
