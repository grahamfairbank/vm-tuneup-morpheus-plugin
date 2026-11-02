package io.github.grahamfairbank.vmtuneup.core.hostcpu

import groovy.transform.CompileStatic
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses command output from a host as XML without trusting it: no DTDs, no entities, no external files, no
 * XInclude, and a size cap. libvirt never writes a DOCTYPE or an entity, so any input that has one is refused before
 * the parser sees it (a second guard in case the JVM's parser ignores one of the features below).
 */
@CompileStatic
final class SafeXml {

	private SafeXml() {}

	/** Largest input accepted, in characters. Real domcapabilities output is about 100 KB. */
	static final int MAX_CHARS = 4 * 1024 * 1024

	/** Raised for input that isn't usable XML. The message is plain words, safe to show. */
	static final class XmlProblem extends Exception {
		XmlProblem(String message) { super(message) }
	}

	static Document parse(String xml, String what) throws XmlProblem {
		if (xml == null || !xml.trim()) throw new XmlProblem("${what}: no output".toString())
		if (xml.length() > MAX_CHARS) throw new XmlProblem("${what}: output too large (${xml.length()} characters)".toString())
		String text = xml
		// virsh may print a warning line before the XML; start at the first '<'.
		int start = text.indexOf('<')
		if (start < 0) throw new XmlProblem("${what}: output is not XML".toString())
		text = text.substring(start)
		String upper = text.toUpperCase()
		if (upper.contains('<!DOCTYPE') || upper.contains('<!ENTITY')) {
			throw new XmlProblem("${what}: output has a DOCTYPE or entity declaration, which libvirt never writes; refused".toString())
		}
		try {
			DocumentBuilder builder = factory().newDocumentBuilder()
			builder.setEntityResolver({ String publicId, String systemId -> new InputSource(new StringReader('')) } as org.xml.sax.EntityResolver)
			builder.setErrorHandler(new ErrorHandler() {
				void warning(SAXParseException e) {}
				void error(SAXParseException e) throws SAXException { throw e }
				void fatalError(SAXParseException e) throws SAXException { throw e }
			})
			return builder.parse(new InputSource(new StringReader(text)))
		} catch (SAXParseException e) {
			throw new XmlProblem("${what}: not valid XML (line ${e.lineNumber}: ${e.message})".toString())
		} catch (Exception e) {
			throw new XmlProblem("${what}: could not be read as XML (${e.class.simpleName})".toString())
		}
	}

	@groovy.transform.PackageScope
	static DocumentBuilderFactory factory() {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance()
		f.setNamespaceAware(false)
		f.setValidating(false)
		f.setXIncludeAware(false)
		f.setExpandEntityReferences(false)
		// Required: without it we don't parse at all.
		f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
		f.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
		// Belt and braces; not every parser knows every feature.
		optional(f, 'http://xml.org/sax/features/external-general-entities', false)
		optional(f, 'http://xml.org/sax/features/external-parameter-entities', false)
		optional(f, 'http://apache.org/xml/features/nonvalidating/load-external-dtd', false)
		try { f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, '') } catch (IllegalArgumentException ignored) {}
		try { f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, '') } catch (IllegalArgumentException ignored) {}
		return f
	}

	private static void optional(DocumentBuilderFactory f, String feature, boolean value) {
		try { f.setFeature(feature, value) } catch (Exception ignored) {}
	}

	// ---- small DOM helpers

	/** Direct child elements named {@code tag}, in document order. */
	static List<Element> children(Element parent, String tag) {
		List<Element> out = new ArrayList<Element>()
		if (parent == null) return out
		NodeList nodes = parent.childNodes
		for (int i = 0; i < nodes.length; i++) {
			Node n = nodes.item(i)
			if (n.nodeType == Node.ELEMENT_NODE && ((Element) n).tagName == tag) out.add((Element) n)
		}
		return out
	}

	static Element child(Element parent, String tag) {
		List<Element> c = children(parent, tag)
		return c.isEmpty() ? null : c[0]
	}

	/** Trimmed text of an element, or null when missing or empty. */
	static String text(Element e) {
		String s = e?.textContent?.trim()
		return s ?: null
	}

	/** Trimmed attribute value, or null when missing or empty. */
	static String attr(Element e, String name) {
		if (e == null || !e.hasAttribute(name)) return null
		String s = e.getAttribute(name)?.trim()
		return s ?: null
	}
}
