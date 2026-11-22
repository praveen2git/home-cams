package com.home.cams.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Background = Color(0xFF0D1117)
val TextHigh = Color(0xFFFFFFFF)
val TextMid = Color(0xA8FFFFFF)
val TextLow = Color(0x73FFFFFF)
val Accent = Color(0xFF38BDF8)
val Scrim = Color(0x8C000000)
private val RowFill = Color(0x0CFFFFFF)
private val RowFillFocused = Color(0x18FFFFFF)

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 16,
    color: Color = TextMid,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Start
) {
    BasicText(text, modifier, TextStyle(color = color, fontSize = size.sp, fontWeight = weight, textAlign = align))
}

/** A full-screen box that takes the remote's keys. [onKey] returns true when it used the key. */
@Composable
fun KeyBox(modifier: Modifier = Modifier, onKey: (KeyEvent) -> Boolean, content: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(modifier.fillMaxSize().onPreviewKeyEvent(onKey).focusRequester(focus).focusable()) { content() }
}

/** The three top-level places. */
enum class Tab(val label: String) { LIVE("Live"), PLAYBACK("Playback"), SETTINGS("Settings") }

/**
 * The tab strip on top of every top-level screen. With [onTab] the tabs take focus and OK opens one.
 * Without it the screen handles the keys itself and shows where the remote is through [marked].
 * [focus] goes on the current tab, for a page that has no row to focus.
 */
@Composable
fun TabBar(
    current: Tab,
    modifier: Modifier = Modifier,
    marked: Tab? = null,
    focus: FocusRequester? = null,
    onTab: ((Tab) -> Unit)? = null
) {
    val shape = RoundedCornerShape(8.dp)
    Row(modifier, Arrangement.spacedBy(8.dp)) {
        Tab.entries.forEach { tab ->
            var focused by remember { mutableStateOf(false) }
            val on = focused || marked == tab
            var m: Modifier = Modifier
            if (tab == current && focus != null) m = m.focusRequester(focus)
            if (onTab != null) {
                m = m.onFocusChanged { focused = it.isFocused }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onTab(tab) }
            }
            Label(
                tab.label,
                m.background(if (on) RowFillFocused else Color.Transparent, shape)
                    .border(2.dp, if (on) Accent else Color.Transparent, shape)
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                size = 16,
                color = if (tab == current) Accent else TextMid,
                weight = if (tab == current) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

/** A titled page of rows, with TV overscan padding. A top-level page passes its [tab] to show the tab strip. */
@Composable
fun Page(
    title: String,
    status: String? = null,
    tab: Tab? = null,
    tabFocus: FocusRequester? = null,
    onTab: (Tab) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 48.dp, vertical = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (tab != null) TabBar(tab, focus = tabFocus, onTab = onTab)
            Label(title, size = 26, color = TextHigh, weight = FontWeight.SemiBold)
            Label(status ?: " ", size = 15, color = Accent)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/** One row of a page. OK on the remote presses it. */
@Composable
fun MenuRow(label: String, value: String = "", modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .background(if (focused) RowFillFocused else RowFill, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else RowFillFocused, shape)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Label(label, size = 17, color = TextHigh, weight = FontWeight.Medium)
        Label(value, Modifier.weight(1f), size = 15, color = if (focused) Accent else TextMid, align = TextAlign.End)
    }
}

/** A row that opens a text box when pressed. OK on the keyboard saves, Back cancels. */
@Composable
fun TextRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    /** What an empty box starts with, so the viewer types less. */
    start: String = "",
    onValue: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(TextFieldValue()) }
    val rowFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current
    fun close() {
        runCatching { rowFocus.requestFocus() }
        editing = false
    }

    val shown = when {
        value.isEmpty() -> "Not set"
        password -> "•".repeat(6)
        else -> value
    }
    MenuRow(label, shown, modifier.focusRequester(rowFocus)) {
        // The cursor goes to the end, or typing would land in front of the old text.
        text = value.ifEmpty { start }.let { TextFieldValue(it, TextRange(it.length)) }
        editing = true
    }
    if (editing) {
        BackHandler { close() }
        LaunchedEffect(Unit) {
            fieldFocus.requestFocus()
            ime?.show()
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = TextHigh, fontSize = 17.sp),
            cursorBrush = SolidColor(Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = if (password) KeyboardType.Password else keyboard
            ),
            keyboardActions = KeyboardActions(onDone = {
                onValue(if (password) text.text else text.text.trim())
                close()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                .border(1.dp, Accent, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}
