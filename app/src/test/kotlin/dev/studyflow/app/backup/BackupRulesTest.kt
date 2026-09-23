package dev.studyflow.app.backup

import android.content.pm.ApplicationInfo
import android.content.res.XmlResourceParser
import dev.studyflow.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The backup rules are a privacy promise, so they are asserted rather than reviewed (issue #78).
 *
 * A future change that enables cloud backup, or that quietly drops an exclude while adding one,
 * fails here: tokens are unreadable off-device, a restored "running" timer is a bug the user
 * cannot fix on their own, and caches are worthless in a backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRulesTest {
    @Test
    fun `the app opts out of platform backup entirely`() {
        val info = RuntimeEnvironment.getApplication().applicationInfo

        assertEquals(
            "android:allowBackup must stay false; the export archive is how data moves between devices",
            0,
            info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }

    @Test
    fun `cloud backup and device transfer exclude the secrets, the timer anchor and the caches`() {
        val rules = readRules(R.xml.data_extraction_rules)

        listOf("cloud-backup", "device-transfer").forEach { section ->
            val excludes = rules.filter { it.section == section && it.tag == "exclude" }
            assertTrue(
                "$section must exclude every domain wholesale",
                excludes.any { it.domain == "root" } && excludes.any { it.domain == "file" },
            )
            assertExcludesSensitiveData(section, excludes)
        }
    }

    @Test
    fun `the pre-Android 12 rules say the same thing`() {
        val excludes = readRules(R.xml.full_backup_content).filter { it.tag == "exclude" }

        assertTrue("full_backup_content must exclude every domain wholesale", excludes.any { it.domain == "root" })
        assertExcludesSensitiveData("full-backup-content", excludes)
    }

    @Test
    fun `nothing is ever opted back in`() {
        val rules = readRules(R.xml.data_extraction_rules) + readRules(R.xml.full_backup_content)

        assertTrue(
            "an <include> would back up data the app has promised never to copy off the device",
            rules.none { it.tag == "include" },
        )
    }

    private fun assertExcludesSensitiveData(
        section: String,
        excludes: List<Rule>,
    ) {
        assertTrue(
            "$section must exclude the Keystore-sealed token store, which cannot be restored anyway",
            excludes.any { it.path?.contains(TOKEN_STORE) == true },
        )
        assertTrue(
            "$section must exclude the active-timer anchor; a restored device must not resurrect a running timer",
            excludes.any { it.path?.contains(ACTIVE_TIMER) == true },
        )
        assertTrue(
            "$section must exclude cached material files, which are reproducible and large",
            excludes.any { it.path?.contains(MATERIALS) == true },
        )
    }

    private fun readRules(resourceId: Int): List<Rule> =
        RuntimeEnvironment
            .getApplication()
            .resources
            .getXml(resourceId)
            .use { parser -> parser.readRules() }

    private fun XmlResourceParser.readRules(): List<Rule> {
        val rules = mutableListOf<Rule>()
        var section = ""
        var event = eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val rule = ruleOrNull(section)
                if (rule == null) section = name else rules += rule
            }
            event = next()
        }
        return rules
    }

    private fun XmlResourceParser.ruleOrNull(section: String): Rule? =
        if (name == "exclude" || name == "include") {
            Rule(section = section, tag = name, domain = attribute("domain"), path = attribute("path"))
        } else {
            null
        }

    private fun XmlResourceParser.attribute(name: String): String? =
        getAttributeValue(ANDROID_NAMESPACE, name) ?: getAttributeValue(null, name)

    private data class Rule(
        val section: String,
        val tag: String,
        val domain: String?,
        val path: String?,
    )

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val TOKEN_STORE = "studyflow.auth.tokens"
        const val ACTIVE_TIMER = "active_timer.pb"
        const val MATERIALS = "materials"
    }
}
