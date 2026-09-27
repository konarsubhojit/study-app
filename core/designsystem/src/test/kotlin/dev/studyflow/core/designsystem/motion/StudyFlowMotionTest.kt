package dev.studyflow.core.designsystem.motion

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class StudyFlowMotionTest {
    @Test
    fun `spatial motion uses an interruption-safe spring`() {
        assertInstanceOf(SpringSpec::class.java, StudyFlowMotion.spatial<Float>())
    }

    @Test
    fun `effects motion remains duration based`() {
        assertInstanceOf(TweenSpec::class.java, StudyFlowMotion.effects<Float>())
    }
}
