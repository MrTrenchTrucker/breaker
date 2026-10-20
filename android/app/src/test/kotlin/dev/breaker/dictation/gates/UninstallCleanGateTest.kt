package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An uninstall must remove everything: history, settings and the speech model.
 *
 * History, settings and the model live in app-private storage and go with the
 * app on uninstall, but Android's default (`allowBackup` true) lets Auto Backup
 * restore app data on a reinstall. The manifest therefore sets `allowBackup`
 * false and points at two rules files, and each rules file excludes every
 * domain from both a cloud backup and a device transfer. Nothing may be
 * included, and a comment that names an exclude is not an exclude: the files
 * are parsed as XML, so comments are not content.
 */
internal class UninstallCleanGateTest {

    private val extractionPath: String = "res/xml/data_extraction_rules.xml"
    private val backupPath: String = "res/xml/backup_rules.xml"

    private val domains: List<String> = listOf("root", "file", "database", "sharedpref", "external")

    private val goodManifest: String = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".BreakerApp"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules" />
</manifest>
"""

    private val goodExtraction: String = """<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="."/>
        <exclude domain="file" path="."/>
        <exclude domain="database" path="."/>
        <exclude domain="sharedpref" path="."/>
        <exclude domain="external" path="."/>
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="."/>
        <exclude domain="file" path="."/>
        <exclude domain="database" path="."/>
        <exclude domain="sharedpref" path="."/>
        <exclude domain="external" path="."/>
    </device-transfer>
</data-extraction-rules>
"""

    private val goodBackup: String = """<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="root" path="."/>
    <exclude domain="file" path="."/>
    <exclude domain="database" path="."/>
    <exclude domain="sharedpref" path="."/>
    <exclude domain="external" path="."/>
</full-backup-content>
"""

    private val expectedAttributes: Map<String, String> = mapOf(
        "android:allowBackup" to "false",
        "android:dataExtractionRules" to "@xml/data_extraction_rules",
        "android:fullBackupContent" to "@xml/backup_rules",
    )

    /** The exclude allow-list for one section of a rules file: all five domains, path ".". */
    private fun excludes(): List<XmlExpect> =
        domains.map { XmlExpect("exclude", mapOf("domain" to it, "path" to "."), key = "domain") }

    private fun extractionAllowed(): XmlExpect = XmlExpect(
        name = "data-extraction-rules",
        attributes = emptyMap(),
        children = listOf(
            XmlExpect("cloud-backup", emptyMap(), excludes()),
            XmlExpect("device-transfer", emptyMap(), excludes()),
        ),
    )

    private fun backupAllowed(): XmlExpect = XmlExpect("full-backup-content", emptyMap(), excludes())

    /** One line per difference between a data extraction rules [xml] and its allow-list; none means it matches. */
    private fun extractionProblems(xml: String): List<String> = XmlAllowList.problems(XmlTree.parse(xml), extractionAllowed())

    /** One line per difference between a backup rules [xml] and its allow-list; none means it matches. */
    private fun backupProblems(xml: String): List<String> = XmlAllowList.problems(XmlTree.parse(xml), backupAllowed())

    /** One line per difference between a manifest [xml] and the three backup attributes; none means it matches. */
    private fun manifestProblems(xml: String): List<String> {
        val root: XmlNode = XmlTree.parse(xml)
        if (root.name != "manifest") {
            return listOf("manifest is <${root.name}>, expected <manifest>")
        }
        val application: XmlNode? = root.children.firstOrNull { it.name == "application" }
        if (application == null) {
            return listOf("manifest lacks the element <application>")
        }
        val found: MutableList<String> = ArrayList()
        for (key in expectedAttributes.keys.sorted()) {
            val have: String? = application.attributes[key]
            val want: String = expectedAttributes.getValue(key)
            if (have == null) {
                found.add("manifest/application lacks the attribute $key")
            } else if (have != want) {
                found.add("manifest/application attribute $key is \"$have\", expected \"$want\"")
            }
        }
        return found
    }

    /** The values the manifest gives for the three backup attributes, in allow-list order; null where unstated. */
    private fun manifestSettings(manifestXml: String): List<String?> {
        val application: XmlNode? = XmlTree.parse(manifestXml).children.firstOrNull { it.name == "application" }
        return expectedAttributes.keys.sorted().map { application?.attributes?.get(it) }
    }

    /** The domain of every exclude that is a direct child of the element named [section]. */
    private fun sectionDomains(xml: String, section: String): List<String> {
        val node: XmlNode? = XmlTree.parse(xml).children.firstOrNull { it.name == section }
        return if (node == null) emptyList() else node.children.map { it.attributes["domain"] ?: "" }
    }

    /** The domain of every exclude that is a direct child of the root element. */
    private fun rootExcludeDomains(xml: String): List<String> =
        XmlTree.parse(xml).children.map { it.attributes["domain"] ?: "" }

    private fun assertRejected(label: String, problems: List<String>, fragments: List<String>) {
        assertTrue("app: the uninstall gate accepted $label", problems.isNotEmpty())
        for (fragment in fragments) {
            assertTrue(
                "app: the uninstall gate did not say \"$fragment\" for $label, it said $problems",
                problems.any { it.contains(fragment) },
            )
        }
    }

    private val manifestFiring: List<XmlSample> = listOf(
        XmlSample("backup allowed", "android:allowBackup=\"false\"", "android:allowBackup=\"true\"", "attribute android:allowBackup is \"true\""),
        XmlSample("backup not stated", "\n        android:allowBackup=\"false\"", "", "lacks the attribute android:allowBackup"),
        XmlSample("no extraction rules", "\n        android:dataExtractionRules=\"@xml/data_extraction_rules\"", "", "lacks the attribute android:dataExtractionRules"),
        XmlSample("no full backup content", "\n        android:fullBackupContent=\"@xml/backup_rules\"", "", "lacks the attribute android:fullBackupContent"),
        XmlSample("extraction rules at the wrong file", "@xml/data_extraction_rules", "@xml/other_rules", "attribute android:dataExtractionRules is \"@xml/other_rules\""),
        XmlSample("full backup content at the wrong file", "@xml/backup_rules", "@xml/other_rules", "attribute android:fullBackupContent is \"@xml/other_rules\""),
    )

    private val extractionFiring: List<XmlSample> = listOf(
        XmlSample("the cloud backup drops the database", "<cloud-backup>\n        <exclude domain=\"root\" path=\".\"/>\n        <exclude domain=\"file\" path=\".\"/>\n        <exclude domain=\"database\" path=\".\"/>", "<cloud-backup>\n        <exclude domain=\"root\" path=\".\"/>\n        <exclude domain=\"file\" path=\".\"/>", "lacks the element <exclude>", "domain=\"database\""),
        XmlSample("an include in the cloud backup", "</cloud-backup>", "<include domain=\"database\" path=\".\"/>\n    </cloud-backup>", "holds the element <include>"),
        XmlSample("an include in the device transfer", "</device-transfer>", "<include domain=\"root\" path=\".\"/>\n    </device-transfer>", "holds the element <include>"),
        XmlSample("a domain only inside a comment", "\n        <exclude domain=\"sharedpref\" path=\".\"/>", "\n        <!-- <exclude domain=\"sharedpref\" path=\".\"/> -->", "lacks the element <exclude>", "domain=\"sharedpref\""),
        XmlSample("the device transfer dropped", "    <device-transfer>\n        <exclude domain=\"root\" path=\".\"/>\n        <exclude domain=\"file\" path=\".\"/>\n        <exclude domain=\"database\" path=\".\"/>\n        <exclude domain=\"sharedpref\" path=\".\"/>\n        <exclude domain=\"external\" path=\".\"/>\n    </device-transfer>\n", "", "lacks the element <device-transfer>"),
        XmlSample("the cloud backup dropped", "    <cloud-backup>\n        <exclude domain=\"root\" path=\".\"/>\n        <exclude domain=\"file\" path=\".\"/>\n        <exclude domain=\"database\" path=\".\"/>\n        <exclude domain=\"sharedpref\" path=\".\"/>\n        <exclude domain=\"external\" path=\".\"/>\n    </cloud-backup>\n", "", "lacks the element <cloud-backup>"),
        XmlSample("an attribute on the root", "<data-extraction-rules>", "<data-extraction-rules extra=\"1\">", "has the attribute extra"),
        XmlSample("a wrong path on an exclude", "<exclude domain=\"root\" path=\".\"/>", "<exclude domain=\"root\" path=\"models\"/>", "attribute path is \"models\""),
    )

    private val backupFiring: List<XmlSample> = listOf(
        XmlSample("an include", "</full-backup-content>", "<include domain=\"database\" path=\".\"/>\n</full-backup-content>", "holds the element <include>"),
        XmlSample("a wrong path on an exclude", "<exclude domain=\"root\" path=\".\"/>", "<exclude domain=\"root\" path=\"settings\"/>", "attribute path is \"settings\""),
    )

    private val droppedDomain: List<XmlSample> = domains.map { domain ->
        XmlSample(
            "the $domain domain dropped",
            "<exclude domain=\"$domain\" path=\".\"/>",
            "",
            "lacks the element <exclude>",
            "domain=\"$domain\"",
        )
    }

    private val quietManifest: List<XmlSample> = listOf(
        XmlSample("a comment that names forbidden things", "<application", "<!-- android:allowBackup=\"true\" android:fullBackupContent=\"@xml/other\" -->\n    <application"),
    )

    private val quietExtraction: List<XmlSample> = listOf(
        XmlSample("a comment that names an include", "<data-extraction-rules>", "<data-extraction-rules>\n    <!-- <include domain=\"database\" path=\".\"/> -->"),
        XmlSample("single quoted excludes", "<exclude domain=\"root\" path=\".\"/>", "<exclude domain='root' path='.' />"),
    )

    private val quietBackup: List<XmlSample> = listOf(
        XmlSample("a comment that names an include", "<full-backup-content>", "<full-backup-content>\n    <!-- <include domain=\"database\" path=\".\"/> -->"),
        XmlSample("single quoted excludes", "<exclude domain=\"root\" path=\".\"/>", "<exclude domain='root' path='.' />"),
    )

    @Test
    fun `the real manifest refuses backup and points at the two rules files`() {
        val manifest: String = AppSourceFiles.mainFile("AndroidManifest.xml")
        assertTrue("app: src/main/AndroidManifest.xml is empty", manifest.isNotBlank())
        assertEquals("app: the manifest differs from its backup allow-list: ${manifestProblems(manifest)}", emptyList<String>(), manifestProblems(manifest))
    }

    @Test
    fun `the real rules files exclude every domain in every section`() {
        val extraction: String = AppSourceFiles.mainFile(extractionPath)
        val backup: String = AppSourceFiles.mainFile(backupPath)
        assertTrue("app: src/main/$extractionPath is empty", extraction.isNotBlank())
        assertTrue("app: src/main/$backupPath is empty", backup.isNotBlank())
        assertEquals("app: $extractionPath differs from its allow-list: ${extractionProblems(extraction)}", emptyList<String>(), extractionProblems(extraction))
        assertEquals("app: $backupPath differs from its allow-list: ${backupProblems(backup)}", emptyList<String>(), backupProblems(backup))
        assertEquals("app: the cloud backup does not exclude all five domains", domains, sectionDomains(extraction, "cloud-backup"))
        assertEquals("app: the device transfer does not exclude all five domains", domains, sectionDomains(extraction, "device-transfer"))
        assertEquals("app: the full backup content does not exclude all five domains", domains, rootExcludeDomains(backup))
    }

    @Test
    fun `the exact good texts pass, and so do comments and single quotes`() {
        assertEquals("app: the exact good manifest was rejected", emptyList<String>(), manifestProblems(goodManifest))
        assertEquals("app: the exact good extraction rules were rejected", emptyList<String>(), extractionProblems(goodExtraction))
        assertEquals("app: the exact good backup rules were rejected", emptyList<String>(), backupProblems(goodBackup))
        for (sample in quietManifest) {
            assertEquals("app: the uninstall gate flagged ${sample.label}", emptyList<String>(), manifestProblems(XmlSamples.apply("manifest", goodManifest, sample)))
        }
        for (sample in quietExtraction) {
            assertEquals("app: the uninstall gate flagged ${sample.label}", emptyList<String>(), extractionProblems(XmlSamples.apply("extraction rules", goodExtraction, sample)))
        }
        for (sample in quietBackup) {
            assertEquals("app: the uninstall gate flagged ${sample.label}", emptyList<String>(), backupProblems(XmlSamples.apply("backup rules", goodBackup, sample)))
        }
    }

    @Test
    fun `a manifest that allows backup or points at no rules file is rejected with what is wrong`() {
        for (sample in manifestFiring) {
            assertRejected(sample.label, manifestProblems(XmlSamples.apply("manifest", goodManifest, sample)), sample.fragments)
        }
        assertRejected("no application", manifestProblems("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"></manifest>"), listOf("lacks the element <application>"))
        assertRejected("another root element", manifestProblems("<resources />"), listOf("is <resources>, expected <manifest>"))
    }

    @Test
    fun `a rules file that drops a domain, includes something or only comments an exclude is rejected`() {
        for (sample in extractionFiring) {
            assertRejected(sample.label, extractionProblems(XmlSamples.apply("extraction rules", goodExtraction, sample)), sample.fragments)
        }
        for (sample in droppedDomain) {
            assertRejected(sample.label, extractionProblems(XmlSamples.apply("extraction rules", goodExtraction, sample)), sample.fragments)
        }
        for (sample in backupFiring) {
            assertRejected(sample.label, backupProblems(XmlSamples.apply("backup rules", goodBackup, sample)), sample.fragments)
        }
        for (sample in droppedDomain) {
            assertRejected(sample.label, backupProblems(XmlSamples.apply("backup rules", goodBackup, sample)), sample.fragments)
        }
    }

    @Test
    fun `text that is not safe or not well formed XML is refused, not passed`() {
        val broken: List<String> = listOf(
            "",
            "   \n",
            "not xml",
            "<data-extraction-rules>",
            "<full-backup-content>",
            "<full-backup-content><exclude domain=\"root\" path=\".\" domain=\"file\" /></full-backup-content>",
            "<?xml version=\"1.0\"?><!DOCTYPE full-backup-content [<!ENTITY x \"y\">]><full-backup-content />",
        )
        for (xml in broken) {
            assertThrows("app: the uninstall gate passed manifest text that is not safe XML: $xml", IllegalStateException::class.java) { manifestProblems(xml) }
            assertThrows("app: the uninstall gate passed extraction text that is not safe XML: $xml", IllegalStateException::class.java) { extractionProblems(xml) }
            assertThrows("app: the uninstall gate passed backup text that is not safe XML: $xml", IllegalStateException::class.java) { backupProblems(xml) }
        }
    }

    @Test
    fun `a missing rules file is reported by name and is never read as empty`() {
        assertNull("app: a file that does not exist was read as text", AppSourceFiles.mainFileOrNull("res/xml/no_such_rules.xml"))
        val failure: IllegalStateException = assertThrows(
            "app: reading a missing file did not fail",
            IllegalStateException::class.java,
        ) { AppSourceFiles.mainFile("res/xml/no_such_rules.xml") }
        val said: String = failure.message ?: ""
        assertTrue("app: the missing-file failure does not name the file: $said", said.startsWith("app: ") && said.contains("res/xml/no_such_rules.xml"))
    }

    @Test
    fun `the three backup attributes are read from a manifest text`() {
        assertEquals("app: the backup settings were misread", listOf<String?>("false", "@xml/data_extraction_rules", "@xml/backup_rules"), manifestSettings(goodManifest))
        val loose = goodManifest.replace("android:allowBackup=\"false\"", "android:allowBackup=\"true\"")
        assertEquals("app: a manifest that allows backup was read as refusing it", listOf<String?>("true", "@xml/data_extraction_rules", "@xml/backup_rules"), manifestSettings(loose))
        val unstated = goodManifest.replace("\n        android:allowBackup=\"false\"", "")
        assertEquals("app: a manifest that states nothing was read as stating false", listOf<String?>(null, "@xml/data_extraction_rules", "@xml/backup_rules"), manifestSettings(unstated))
        val other = goodManifest.replace("@xml/data_extraction_rules", "@xml/other_rules")
        assertEquals("app: extraction rules at another file were read as the good one", listOf<String?>("false", "@xml/other_rules", "@xml/backup_rules"), manifestSettings(other))
    }
}
