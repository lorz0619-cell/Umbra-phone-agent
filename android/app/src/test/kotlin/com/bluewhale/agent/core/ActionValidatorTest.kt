package com.bluewhale.agent.core

import com.bluewhale.agent.model.DeviceAction
import com.bluewhale.agent.model.PhoneAction
import com.bluewhale.agent.model.SystemCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionValidatorTest {
    @Test
    fun rejectsUnsafeCoordinateOnlyVisualTap() {
        val result =
            ActionValidator.validate(PhoneAction.Tap(999, 500), width = 1080, height = 2400)

        assertTrue(result.isFailure)
    }

    @Test
    fun tapsCenterOfVisualBoundingBox() {
        val result =
            ActionValidator.validate(
                PhoneAction.Tap(
                    x = 10,
                    y = 10,
                    targetBounds = PhoneAction.NormalizedBounds(400, 200, 600, 400),
                    targetDescription = "搜索按钮",
                ),
                width = 1080,
                height = 2400,
            ).getOrThrow() as DeviceAction.Tap

        assertEquals(540, result.x)
        assertEquals(720, result.y)
        assertEquals("visual_box_center", result.strategy)
    }

    @Test
    fun rejectsOutOfBoundsTapAndZeroLengthSwipe() {
        assertTrue(
            ActionValidator.validate(PhoneAction.Tap(1000, 1), 1080, 2400).isFailure,
        )
        assertTrue(
            ActionValidator.validate(
                PhoneAction.Swipe(100, 100, 100, 100),
                1080,
                2400,
            ).isFailure,
        )
    }

    @Test
    fun clampsWaitAndSwipeDurations() {
        val wait =
            ActionValidator.validate(PhoneAction.Wait(99_000), 1080, 2400)
                .getOrThrow() as DeviceAction.Wait
        val swipe =
            ActionValidator.validate(
                PhoneAction.Swipe(10, 10, 900, 900, durationMs = 9_000),
                1080,
                2400,
            ).getOrThrow() as DeviceAction.Swipe

        assertEquals(15_000, wait.durationMs)
        assertEquals(2_000, swipe.durationMs)
    }

    @Test
    fun rejectsSearchResultBeingSnappedToEditableField() {
        assertTrue(
            ActionValidator.hasEditableSemanticConflict("QQ应用搜索结果", editable = true),
        )
        assertTrue(
            !ActionValidator.hasEditableSemanticConflict("搜索输入框", editable = true),
        )
    }

    @Test
    fun targetsTypeAtVisualInputBoxWithoutPriorTap() {
        val action =
            ActionValidator.validate(
                PhoneAction.Type(
                    text = "蜜雪冰城",
                    targetBounds = PhoneAction.NormalizedBounds(100, 80, 900, 180),
                    targetDescription = "搜索输入框",
                ),
                width = 1080,
                height = 2400,
            ).getOrThrow() as DeviceAction.Type

        assertEquals(540, action.targetX)
        assertEquals(312, action.targetY)
        assertEquals("visual_box", action.strategy)
    }

    @Test
    fun rejectsUntargetedTypeWhenNoFocusedEditableNodeExists() {
        val result =
            ActionValidator.validate(
                PhoneAction.Type("不能盲目输入"),
                width = 1080,
                height = 2400,
            )

        assertTrue(result.isFailure)
    }

    @Test
    fun validatesAllowListedSystemTools() {
        val navigation =
            ActionValidator.validate(
                PhoneAction.SystemTool(
                    SystemCapability.Navigate("无锡一中", "driving", "tencent"),
                ),
                1080,
                2400,
            ).getOrThrow() as DeviceAction.SystemTool
        val event =
            ActionValidator.validate(
                PhoneAction.SystemTool(
                    SystemCapability.CreateCalendarEvent(
                        title = "项目评审",
                        startTime = "2026-09-02T09:00:00+08:00",
                    ),
                ),
                1080,
                2400,
            )

        assertEquals("无锡一中", (navigation.capability as SystemCapability.Navigate).destination)
        assertTrue(event.isSuccess)
    }

    @Test
    fun rejectsUnsafeOrMalformedSystemToolArguments() {
        val scriptUrl =
            ActionValidator.validate(
                PhoneAction.SystemTool(SystemCapability.OpenUrl("javascript:alert(1)")),
                1080,
                2400,
            )
        val invalidAlarm =
            ActionValidator.validate(
                PhoneAction.SystemTool(SystemCapability.SetAlarm(25, 0)),
                1080,
                2400,
            )
        val invalidDate =
            ActionValidator.validate(
                PhoneAction.SystemTool(
                    SystemCapability.CreateCalendarEvent("会议", "明天上午九点"),
                ),
                1080,
                2400,
            )

        assertTrue(scriptUrl.isFailure)
        assertTrue(invalidAlarm.isFailure)
        assertTrue(invalidDate.isFailure)
    }

}
