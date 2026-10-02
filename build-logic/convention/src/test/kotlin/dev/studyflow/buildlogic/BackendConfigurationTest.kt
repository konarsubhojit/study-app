package dev.studyflow.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BackendConfigurationTest {
    @TempDir
    lateinit var projectDir: File

    private val repository = File("../..").canonicalFile

    @Test
    fun `production debug and release fail during configuration without backend settings`() {
        listOf("assembleProductionDebug", "assembleProductionRelease", "check").forEach { task ->
            val output = runner(task).buildAndFail().output
            listOf(
                "studyflow.supabaseProjectRef",
                "STUDYFLOW_SUPABASE_PROJECT_REF",
                "studyflow.apiBaseUrl",
                "studyflow.storageBaseUrl",
            ).forEach { assertTrue(output.contains(it), output) }
            assertFalse(output.contains("configuration completed"), output)
        }
    }

    @Test
    fun `mock configures without either backend URL`() {
        assertTrue(runner("assembleMockDebug").build().output.contains("backend=false api= storage="))
    }

    @Test
    fun `a project ref resolves both services and per-service overrides win`() {
        val output =
            runner(
                "assembleProductionDebug",
                "-Pstudyflow.supabaseProjectRef=abcdefgh",
                "-Pstudyflow.apiBaseUrl=https://api.example.test",
            ).build().output
        assertTrue(output.contains("api=https://api.example.test storage=https://abcdefgh.supabase.co/functions/v1/storage"), output)
    }

    @Test
    fun `both explicit URLs configure production without a ref`() {
        assertTrue(
            runner(
                "assembleProductionRelease",
                "-Pstudyflow.apiBaseUrl=https://api.example.test",
                "-Pstudyflow.storageBaseUrl=https://storage.example.test",
            ).build().output.contains("backend=true"),
        )
    }

    @Test
    fun `one override or blank overrides are not enough`() {
        listOf("-Pstudyflow.apiBaseUrl=https://api.example.test", "-Pstudyflow.storageBaseUrl=").forEach {
            assertTrue(runner("assembleProductionDebug", it).buildAndFail().output.contains("Production requires"))
        }
    }

    @Test
    fun `the environment can supply the project ref`() {
        val output = runner("assembleProductionDebug", projectRef = "abcdefgh").build().output
        assertTrue(output.contains("api=https://abcdefgh.supabase.co/functions/v1/api"), output)
        assertTrue(output.contains("storage=https://abcdefgh.supabase.co/functions/v1/storage"), output)
    }

    @Test
    fun `no tracked file contains the retired domain`() {
        val process = ProcessBuilder("git", "ls-files", "-z").directory(repository).start()
        val paths = process.inputStream.readBytes().decodeToString().split('\u0000').filter(String::isNotEmpty)
        check(process.waitFor() == 0)
        val forbidden = Regex("studyflow" + "\\.dev")
        paths.forEach { path ->
            val file = File(repository, path)
            if (file.isFile) assertFalse(forbidden.containsMatchIn(file.readText()), path)
        }
    }

    private fun runner(
        vararg arguments: String,
        projectRef: String? = null,
    ): GradleRunner {
        File(repository, "app/backend.gradle.kts").copyTo(File(projectDir, "backend.gradle.kts"), overwrite = true)
        File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"backend-test\"")
        File(projectDir, "build.gradle.kts").writeText(
            """
            apply(from = "backend.gradle.kts")
            println("configuration completed")
            println("backend=${'$'}{extra["backendConfigured"]} api=${'$'}{extra["apiBaseUrl"]} storage=${'$'}{extra["storageBaseUrl"]}")
            listOf("assembleMockDebug", "assembleProductionDebug", "assembleProductionRelease", "check").forEach {
                tasks.register(it)
            }
            """.trimIndent(),
        )
        val environment = System.getenv().filterKeys {
            !it.startsWith("ORG_GRADLE_PROJECT_") && it != "STUDYFLOW_SUPABASE_PROJECT_REF"
        }.toMutableMap()
        projectRef?.let { environment["STUDYFLOW_SUPABASE_PROJECT_REF"] = it }
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withEnvironment(environment)
            .withArguments(*arguments, "--stacktrace")
    }
}
