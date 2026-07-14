package cn.vectory.ocdroid.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPickerAccessibilityTest {
    @Test
    fun `accessibility states expose selected question retry and unread without visual icons`() {
        assertEquals(
            listOf(
                PickerAccessibilityState.Selected,
                PickerAccessibilityState.Question,
                PickerAccessibilityState.Retry,
                PickerAccessibilityState.Unread,
            ),
            pickerAccessibilityStates(true, true, true, true),
        )
    }
}
