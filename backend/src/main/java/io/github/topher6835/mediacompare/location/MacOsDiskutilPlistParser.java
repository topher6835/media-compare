package io.github.topher6835.mediacompare.location;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Strict structural parser for the small diskutil plist subset used by this profile. */
public final class MacOsDiskutilPlistParser {

    public static final int MAX_PLIST_BYTES = 1_024 * 1_024;

    public DiskutilInfo parse(byte[] plist) {
        return diskutilInfo(values(plist));
    }

    public MountedDiskutilInfo parseMounted(byte[] plist) {
        Map<String, Element> values = values(plist);
        return new MountedDiskutilInfo(diskutilInfo(values), requireString(values, "MountPoint"));
    }

    private static Map<String, Element> values(byte[] plist) {
        if (plist == null || plist.length == 0 || plist.length > MAX_PLIST_BYTES) {
            throw new IllegalArgumentException("diskutil plist has an invalid size");
        }

        final Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            document = builder.parse(new ByteArrayInputStream(plist));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new IllegalArgumentException("diskutil output is not a supported XML plist", exception);
        }

        Element plistElement = document.getDocumentElement();
        if (plistElement == null || !"plist".equals(plistElement.getTagName())) {
            throw new IllegalArgumentException("diskutil output must have a plist root");
        }
        Element dictionary = onlyElementChild(plistElement, "plist");
        if (!"dict".equals(dictionary.getTagName())) {
            throw new IllegalArgumentException("diskutil plist root value must be a dictionary");
        }

        return dictionaryValues(dictionary);
    }

    private static DiskutilInfo diskutilInfo(Map<String, Element> values) {
        String fileSystemType = requireString(values, "FilesystemType").toLowerCase(Locale.ROOT);
        if (!MacOsApfsLocationContextEvidence.FILE_SYSTEM_TYPE.equals(fileSystemType)) {
            throw new UnsupportedFileSystemException();
        }
        String rawVolumeUuid = requireString(values, "VolumeUUID");
        final String volumeUuid;
        try {
            UUID parsed = UUID.fromString(rawVolumeUuid);
            volumeUuid = parsed.toString();
            if (!volumeUuid.equalsIgnoreCase(rawVolumeUuid)) {
                throw new IllegalArgumentException("diskutil VolumeUUID is not canonical UUID text");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("diskutil VolumeUUID is malformed", exception);
        }
        return new DiskutilInfo(fileSystemType, volumeUuid);
    }

    private static Element onlyElementChild(Element parent, String description) {
        Element child = null;
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element) {
                if (child != null) {
                    throw new IllegalArgumentException(description + " contains multiple root values");
                }
                child = element;
            } else if (node.getNodeType() == Node.TEXT_NODE && !node.getTextContent().isBlank()) {
                throw new IllegalArgumentException(description + " contains unexpected text");
            }
        }
        if (child == null) {
            throw new IllegalArgumentException(description + " has no root value");
        }
        return child;
    }

    private static Map<String, Element> dictionaryValues(Element dictionary) {
        var values = new HashMap<String, Element>();
        String pendingKey = null;
        NodeList nodes = dictionary.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (!(node instanceof Element element)) {
                if (node.getNodeType() == Node.TEXT_NODE && !node.getTextContent().isBlank()) {
                    throw new IllegalArgumentException("diskutil dictionary contains unexpected text");
                }
                continue;
            }
            if (pendingKey == null) {
                if (!"key".equals(element.getTagName())) {
                    throw new IllegalArgumentException("diskutil dictionary value has no key");
                }
                pendingKey = element.getTextContent();
                if (pendingKey.isEmpty() || values.containsKey(pendingKey)) {
                    throw new IllegalArgumentException("diskutil dictionary contains an invalid duplicate key");
                }
            } else {
                values.put(pendingKey, element);
                pendingKey = null;
            }
        }
        if (pendingKey != null) {
            throw new IllegalArgumentException("diskutil dictionary key has no value");
        }
        return Map.copyOf(values);
    }

    private static String requireString(Map<String, Element> values, String key) {
        Element value = values.get(key);
        if (value == null || !"string".equals(value.getTagName())) {
            throw new IllegalArgumentException("diskutil plist requires string key " + key);
        }
        String text = value.getTextContent();
        if (text == null || text.isBlank() || !text.equals(text.trim())) {
            throw new IllegalArgumentException("diskutil plist key " + key + " has an invalid value");
        }
        return text;
    }

    public record DiskutilInfo(String fileSystemType, String volumeUuid) {
    }

    public record MountedDiskutilInfo(DiskutilInfo volume, String mountPoint) {
    }

    public static final class UnsupportedFileSystemException extends IllegalArgumentException {
        private UnsupportedFileSystemException() {
            super("diskutil filesystem is outside the local APFS profile");
        }
    }
}
