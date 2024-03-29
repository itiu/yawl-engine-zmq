/*
 * Copyright (c) 2004-2011 The YAWL Foundation. All rights reserved.
 * The YAWL Foundation is a collaboration of individuals and
 * organisations who are committed to improving workflow technology.
 *
 * This file is part of YAWL. YAWL is free software: you can
 * redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation.
 *
 * YAWL is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with YAWL. If not, see <http://www.gnu.org/licenses/>.
 */

package org.yawlfoundation.yawl.unmarshal;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLReaderFactory;
import org.yawlfoundation.yawl.schema.YSchemaVersion;

import java.io.StringReader;

/**
 * Validates a specification XML against its appropriate schema.
 * 
 * @author Lachlan Aldred
 * @author Michael Adams (updated for 2.1)
 * 
 */

public class YawlXMLSpecificationValidator extends DefaultHandler {
    StringBuilder _errorsString = new StringBuilder("");

    private static YawlXMLSpecificationValidator _myInstance;

    private YawlXMLSpecificationValidator() {}

    public static YawlXMLSpecificationValidator getInstance() {
        if (_myInstance == null) {
            _myInstance = new YawlXMLSpecificationValidator();
        }
        return _myInstance;
    }


    public void warning(SAXParseException ex) {
        addMessage(ex, "Warning");
    }


    public void error(SAXParseException ex) {
        addMessage(ex, "Invalid");
    }


    public void fatalError(SAXParseException ex) throws SAXException {
        addMessage(ex, "Error");
    }


    private void addMessage(SAXParseException e, String errType) {
        String lineNum = getLineNumber(e);
        _errorsString.append(String.format("%s#%s#%s\n", errType, lineNum, e.getMessage()));
    }


    private String getLineNumber(SAXParseException e) {
        return (e.getSystemId() != null) ?
               "[ln: " + e.getLineNumber() + " col: " + e.getColumnNumber() + "]" : "";
    }


    public String checkSchema(InputSource input, YSchemaVersion version) {
        _errorsString.delete(0, _errorsString.length());
        try {
            XMLReader parser = setUpChecker(version);
            parser.parse(input);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return _errorsString.toString();
    }


    /**
     * Checks the schema agsinst the XML schema for that version.
     * @param specStr the specification xml.
     * @param version the spec version
     * @return a string of error messages generated by XERCES with each error separated by
     * a carriage return.
     */
    public String checkSchema(String specStr, YSchemaVersion version) {
        return checkSchema(new InputSource(new StringReader(specStr)), version) ;
    }


    /**
     * Sets the checker up for a run.
     * @param version the version of the schema
     * @return a reader configured to do the checking.
     * @throws SAXException
     */
    private XMLReader setUpChecker(YSchemaVersion version) throws SAXException {
        XMLReader parser = XMLReaderFactory.createXMLReader(
                "org.apache.xerces.parsers.SAXParser");
        parser.setProperty("http://apache.org/xml/properties/schema/external-schemaLocation",
                                version.getSchemaLocation());

        parser.setContentHandler(this);
        parser.setErrorHandler(this);
        parser.setFeature("http://xml.org/sax/features/validation", true);
        parser.setFeature("http://apache.org/xml/features/validation/schema", true);
        parser.setFeature("http://apache.org/xml/features/validation/schema-full-checking", true);
        return parser;
    }

}
