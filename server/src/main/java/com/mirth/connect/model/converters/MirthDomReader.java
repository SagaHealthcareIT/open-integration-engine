/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.model.converters;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.thoughtworks.xstream.io.naming.NameCoder;
import com.thoughtworks.xstream.io.xml.DomReader;
import com.thoughtworks.xstream.io.xml.XmlFriendlyReplacer;

public class MirthDomReader extends DomReader {

    public MirthDomReader(Element rootElement) {
        super(rootElement);
    }

    public MirthDomReader(Document document) {
        super(document);
    }

    public MirthDomReader(Element rootElement, NameCoder nameCoder) {
        super(rootElement, nameCoder);
    }

    public MirthDomReader(Document document, NameCoder nameCoder) {
        super(document, nameCoder);
    }

    public MirthDomReader(Element rootElement, XmlFriendlyReplacer replacer) {
        super(rootElement, replacer);
    }

    public MirthDomReader(Document document, XmlFriendlyReplacer replacer) {
        super(document, replacer);
    }

    protected void reloadCurrentElement() {
        reassignCurrentElement(getCurrent());
    }

    /*
     * Mirth migration code (MigratableConverter / Migratable.migrateX_X_X) mutates the DOM while
     * XStream is reading it. XStream 1.4.20 and earlier re-collected the child element list on
     * every reassignCurrentElement() call, so reloadCurrentElement() above was sufficient to make
     * DOM mutations visible to the reader. XStream 1.4.21 moved child collection into a private
     * cache (DomReader.childElements / childrenStack) that reassignCurrentElement() no longer
     * refreshes, which caused stale child lists (e.g. UnknownFieldException on elements a migration
     * step had removed). Overriding getChildCount()/getChild() to read directly from the live DOM
     * restores the semantics this reader has always required.
     */
    @Override
    protected int getChildCount() {
        NodeList childNodes = ((Element) getCurrent()).getChildNodes();
        int count = 0;

        for (int i = 0; i < childNodes.getLength(); i++) {
            if (childNodes.item(i) instanceof Element) {
                count++;
            }
        }

        return count;
    }

    @Override
    protected Object getChild(int index) {
        NodeList childNodes = ((Element) getCurrent()).getChildNodes();
        int elementIndex = -1;

        for (int i = 0; i < childNodes.getLength(); i++) {
            Node node = childNodes.item(i);

            if (node instanceof Element && ++elementIndex == index) {
                return node;
            }
        }

        throw new IndexOutOfBoundsException("No child element at index " + index + " for element " + ((Element) getCurrent()).getTagName());
    }
}
