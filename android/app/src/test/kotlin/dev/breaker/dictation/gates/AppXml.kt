package dev.breaker.dictation.gates

import java.io.StringReader
import java.util.TreeMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

/** One element of a parsed XML file: comments are dropped and [text] is its character data. */
internal class XmlNode(
    val name: String,
    val attributes: Map<String, String>,
    val children: List<XmlNode>,
    val text: String,
)

/**
 * Reads an XML file the way a gate must: secure processing is on, no document type
 * declaration, no entity and no outside file is ever read, and text that is not well
 * formed is refused. Attribute names are kept as written, so `android:name` stays
 * `android:name` and the namespace declaration is an attribute like any other.
 * Same technique as the commit sub-module's gate helpers.
 */
internal object XmlTree {
    const val ANDROID_NAMESPACE: String = "http://schemas.android.com/apk/res/android"

    /** The root element of [xml]; throws [IllegalStateException] when [xml] cannot be parsed safely. */
    fun parse(xml: String): XmlNode {
        val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.isNamespaceAware = false
        factory.isCoalescing = true
        factory.isIgnoringComments = true
        val documentParser = factory.newDocumentBuilder()
        documentParser.setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) {}
            override fun error(exception: SAXParseException) {
                throw exception
            }
            override fun fatalError(exception: SAXParseException) {
                throw exception
            }
        })
        val document = try {
            documentParser.parse(InputSource(StringReader(xml)))
        } catch (e: SAXException) {
            error("app: the XML text cannot be parsed: ${e.message}")
        }
        return convert(document.documentElement)
    }

    private fun convert(element: Element): XmlNode {
        val attributes: MutableMap<String, String> = TreeMap()
        val map = element.attributes
        for (i in 0 until map.length) {
            val attribute = map.item(i)
            attributes[attribute.nodeName] = attribute.nodeValue
        }
        val children: MutableList<XmlNode> = ArrayList()
        val text = StringBuilder()
        var child: Node? = element.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.ELEMENT_NODE -> children.add(convert(child as Element))
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(child.nodeValue)
            }
            child = child.nextSibling
        }
        return XmlNode(element.nodeName, attributes, children, text.toString())
    }
}

/**
 * What one element may be: its name, exactly its attributes, and exactly its child elements.
 * When [key] names an attribute, a child is matched to this entry by its name AND the value
 * of that attribute, so several elements of one name (the permissions) are each listed.
 */
internal class XmlExpect(
    val name: String,
    val attributes: Map<String, String>,
    val children: List<XmlExpect> = emptyList(),
    val key: String? = null,
)

/** Compares a parsed tree to an exact allow-list and says in words what differs. */
internal object XmlAllowList {

    /** One line per difference; an empty list means the tree is exactly [expect]. */
    fun problems(root: XmlNode, expect: XmlExpect): List<String> {
        val found: MutableList<String> = ArrayList()
        compare(root, expect, root.name, found)
        return found
    }

    private fun compare(node: XmlNode, expect: XmlExpect, path: String, found: MutableList<String>) {
        if (node.name != expect.name) {
            found.add("$path is <${node.name}>, expected <${expect.name}>")
            return
        }
        for (key in node.attributes.keys.sorted()) {
            val have: String = node.attributes.getValue(key)
            val want: String? = expect.attributes[key]
            if (want == null) {
                found.add("$path has the attribute $key, which is not allowed")
            } else if (want != have) {
                found.add("$path attribute $key is \"$have\", expected \"$want\"")
            }
        }
        for (key in expect.attributes.keys.sorted()) {
            if (key !in node.attributes) {
                found.add("$path lacks the attribute $key")
            }
        }
        if (node.text.isNotBlank()) {
            found.add("$path holds character data, which is not allowed")
        }
        val remaining: MutableList<XmlNode> = node.children.toMutableList()
        for (want in expect.children) {
            val match: XmlNode? = remaining.firstOrNull { sameKind(it, want) }
            if (match == null) {
                val keyText: String = if (want.key == null) "" else " with ${want.key}=\"${want.attributes[want.key]}\""
                found.add("$path lacks the element <${want.name}>$keyText")
            } else {
                remaining.remove(match)
                compare(match, want, path + "/" + want.name, found)
            }
        }
        for (extra in remaining) {
            val named: String? = extra.attributes["android:name"]
            val nameText: String = if (named == null) "" else " (android:name=\"$named\")"
            found.add("$path holds the element <${extra.name}>, which is not allowed$nameText")
        }
    }

    private fun sameKind(node: XmlNode, want: XmlExpect): Boolean =
        node.name == want.name && (want.key == null || node.attributes[want.key] == want.attributes[want.key])
}

/** One edit of a good text and what the gate must say about the result; [fragments] are empty for a quiet sample. */
internal class XmlSample(val label: String, val from: String, val to: String, vararg fragments: String) {
    val fragments: List<String> = fragments.toList()
}

/** Samples are built by editing a good text. */
internal object XmlSamples {
    /** [source] with every [from] replaced; fails when [from] is not there, so a sample cannot go quiet by a typo. */
    fun edit(kind: String, label: String, source: String, from: String, to: String): String {
        check(source.contains(from)) { "app: the $kind sample \"$label\" has no text \"$from\" to change" }
        return source.replace(from, to)
    }

    /** [sample] applied to [good]. */
    fun apply(kind: String, good: String, sample: XmlSample): String = edit(kind, sample.label, good, sample.from, sample.to)
}
