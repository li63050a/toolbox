package com.toolbox.app.ui.term

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.toolbox.app.log.Log

/**
 * 自写 VT100/ANSI 终端 View：等宽字体、滚动缓冲、基础 SGR 色彩、UTF-8 增量解码。
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var columns = 80
        private set
    private var rows = 24
        private set

    val termColumns: Int get() = columns
    val termRows: Int get() = rows
    private val scrollbackMax = 2000

    private val lines = ArrayList<CharArray>()          // 历史+可见，最后一行 = 光标行
    private val fgLine = ArrayList<Int>()               // 每行当前前景色(-1 默认, 0..15 调色板, ≥16 真彩编码)
    private val bgLine = ArrayList<Int>()
    private var cursorRow = 0
    private var cursorCol = 0
    private var fgColor = -1
    private var bgColor = -1
    private var scrollOffset = 0                        // 向上滚动查看历史

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val bgPaint = Paint()

    private var fontSize = dp(14f)
    private var charWidth = 0f
    private var lineHeight = 0f
    private var fontColor = Color.WHITE
    private var backgroundColor = Color.rgb(20, 22, 28)

    private val palette16 = intArrayOf(
        Color.rgb(30, 30, 30), Color.rgb(200, 40, 41), Color.rgb(72, 160, 34),
        Color.rgb(181, 137, 0), Color.rgb(42, 91, 200), Color.rgb(180, 0, 158),
        Color.rgb(0, 146, 155), Color.rgb(211, 215, 207),
        Color.rgb(128, 128, 128), Color.rgb(255, 85, 85), Color.rgb(114, 213, 80),
        Color.rgb(241, 218, 54), Color.rgb(83, 131, 250), Color.rgb(214, 41, 191),
        Color.rgb(0, 217, 224), Color.rgb(255, 255, 255)
    )

    var stdinConsumer: ((ByteArray) -> Unit)? = null
    var resizeCallback: ((Int, Int) -> Unit)? = null

    init {
        setBackgroundColor(backgroundColor)
        measureFont()
        ensureRows(24)
    }

    // ---------- 公开 API ----------

    /** IO 线程调用：写入终端数据 */
    fun write(data: ByteArray) {
        post { processInput(data) }
    }

    /** 追加产生：仅当 scrollOffset>0 时向下滚动回底部 */
    fun scrollDown() {
        if (scrollOffset > 0) {
            scrollOffset--
            invalidate()
        }
    }

    fun scrollUp() {
        if (scrollOffset < lines.size - rows) {
            scrollOffset++
            invalidate()
        }
    }

    fun clearScreen() {
        lines.clear(); fgLine.clear(); bgLine.clear()
        cursorRow = 0; cursorCol = 0; scrollOffset = 0
        ensureRows(24)
        invalidate()
    }

    fun setFontSize(sp: Float) {
        fontSize = dp(sp)
        measureFont()
        invalidate()
    }

    // ---------- 输入处理 ----------

    private val pending = StringBuilder()               // UTF-8 解码后字符串缓冲
    private var ansiState = 0                            // 0 普通, 1 ESC, 2 CSI, 3 OSC
    private val csiParams = StringBuilder()

    @Synchronized
    private fun processInput(data: ByteArray) {
        try {
            pending.append(decodeUtf8(data))
            var input = pending.toString()
            pending.setLength(0)

            var i = 0
            while (i < input.length) {
                val c = input[i]
                when {
                    ansiState == 2 -> { // CSI
                        if (c in '0'..'9' || c == ';' || c == '?') {
                            csiParams.append(c); i++
                        } else if (c == 0x1b.toChar()) {
                            ansiState = 1; i++
                        } else {
                            execCsi(c, csiParams.toString())
                            csiParams.setLength(0)
                            ansiState = 0; i++
                        }
                    }
                    ansiState == 3 -> { // OSC 忽略至 BEL 或 ESC
                        if (c == '\u0007') { ansiState = 0; i++ } else if (c == 0x1b.toChar()) { ansiState = 1; i++ } else i++
                    }
                    ansiState == 1 -> {
                        when (c) {
                            '[' -> { ansiState = 2; csiParams.setLength(0); i++ }
                            ']' -> { ansiState = 3; i++ }
                            '7' -> { saveCursor(); ansiState = 0; i++ }
                            '8' -> { restoreCursor(); ansiState = 0; i++ }
                            '(', ')', '#' -> { ansiState = 4 }  // 字符集声明：跳过下一字符
                            else -> { ansiState = 0; i++ }
                        }
                    }
                    ansiState == 4 -> { ansiState = 0; i++ }
                    else -> {
                        when (c) {
                            '\u0007' -> { }
                            '\b' -> cursorCol = (cursorCol - 1).coerceAtLeast(0)
                            '\t' -> cursorCol = ((cursorCol / 8) + 1) * 8
                            '\r' -> cursorCol = 0
                            '\n' -> newline()
                            else -> putChar(c)
                        }
                        if (cursorCol >= columns) wrap()
                        i++
                    }
                }
                if (lines.size > scrollbackMax + rows) {
                    trimHistory()
                }
            }
        } catch (e: Exception) {
            Log.e("Term", "解析输入异常", e)
        }
        invalidate()
    }

    private fun decodeUtf8(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            when {
                b < 0x80 -> {
                    sb.append(b.toChar())
                    i++
                }
                b in 0xC0..0xDF && i + 1 < data.size -> {
                    sb.append(String(data, i, 2, charset("UTF-8")))
                    i += 2
                }
                b in 0xE0..0xEF && i + 2 < data.size -> {
                    sb.append(String(data, i, 3, charset("UTF-8")))
                    i += 3
                }
                b in 0xF0..0xF7 && i + 3 < data.size -> {
                    sb.append(String(data, i, 4, charset("UTF-8")))
                    i += 4
                }
                else -> {
                    // 不完整多字节：留到下次
                    pending.append(String(data, i, data.size - i, charset("UTF-8")))
                    i = data.size
                }
            }
        }
        return sb.toString()
    }

    // ---------- 屏幕操作 ----------

    private fun ensureRows(n: Int) {
        while (lines.size < n) {
            lines.add(CharArray(columns) { ' ' })
            fgLine.add(-1); bgLine.add(-1)
        }
        cursorRow = cursorRow.coerceIn(0, lines.size - 1)
    }

    private fun trimHistory() {
        val remove = lines.size - scrollbackMax - rows / 2
        if (remove > 0) {
            repeat(remove) {
                if (lines.isNotEmpty()) lines.removeAt(0)
                if (fgLine.isNotEmpty()) fgLine.removeAt(0)
                if (bgLine.isNotEmpty()) bgLine.removeAt(0)
            }
            cursorRow = (cursorRow - remove).coerceAtLeast(0)
            if (scrollOffset > 0) scrollOffset = (scrollOffset - remove).coerceAtLeast(0)
        }
    }

    private fun putChar(c: Char) {
        ensureRows(cursorRow + 1)
        val line = lines[cursorRow]
        if (cursorCol < line.size) {
            line[cursorCol] = c
            fgLine[cursorRow] = fgColor
            bgLine[cursorRow] = bgColor
        }
        cursorCol++
        if (cursorCol >= columns) wrap()
    }

    private fun wrap() {
        cursorCol = 0
        newline()
    }

    private fun newline() {
        if (cursorRow + 1 >= lines.size) {
            lines.add(CharArray(columns) { ' ' })
            fgLine.add(-1); bgLine.add(-1)
        }
        cursorRow++
        if (scrollOffset > 0) scrollOffset++
    }

    private var savedRow = 0
    private var savedCol = 0
    private fun saveCursor() { savedRow = cursorRow; savedCol = cursorCol }
    private fun restoreCursor() { cursorRow = savedRow.coerceAtMost(lines.size - 1); cursorCol = savedCol }

    private fun paramInt(params: String, index: Int, default: Int): Int {
        val parts = params.split(';')
        val p = parts.getOrNull(index) ?: return default
        return p.toIntOrNull() ?: default
    }

    private fun execCsi(final: Char, params: String) {
        val p1 = paramInt(params, 0, 1)
        val p2 = paramInt(params, 1, 1)
        val p3 = paramInt(params, 2, 1)
        when (final) {
            'A' -> cursorRow = (cursorRow - p1.coerceAtLeast(1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + p1.coerceAtLeast(1)).coerceAtMost(lines.size - 1)
            'C' -> cursorCol = (cursorCol + p1.coerceAtLeast(1)).coerceAtMost(columns - 1)
            'D' -> cursorCol = (cursorCol - p1.coerceAtLeast(1)).coerceAtLeast(0)
            'G' -> cursorCol = (p1 - 1).coerceIn(0, columns - 1)
            'H', 'f' -> {
                cursorRow = (p1 - 1).coerceAtLeast(0)
                cursorCol = (p2 - 1).coerceIn(0, columns - 1)
                ensureRows(cursorRow + 1)
            }
            'J' -> when (params.firstOrNull() ?: '0') {
                '2', '3' -> {
                    for (r in cursorRow until cursorRow.coerceAtMost(lines.size - 1)) {
                        lines[r].fill(' ')
                    }
                    for (r in cursorRow + 1 until lines.size) {
                        lines[r].fill(' ')
                        fgLine[r] = -1; bgLine[r] = -1
                    }
                    if (cursorRow in lines.indices) {
                        for (c in cursorCol until columns) lines[cursorRow][c] = ' '
                        fgLine[cursorRow] = -1; bgLine[cursorRow] = -1
                    }
                }
                '1' -> {
                    for (r in 0..cursorRow) {
                        lines[r].fill(' ')
                        fgLine[r] = -1; bgLine[r] = -1
                    }
                    for (c in 0..cursorCol) lines[cursorRow][c] = ' '
                }
                else -> {
                    for (c in cursorCol until columns) lines[cursorRow][c] = ' '
                }
            }
            'K' -> {
                val r = cursorRow.coerceAtMost(lines.size - 1)
                when (params.firstOrNull() ?: '0') {
                    '1' -> for (c in 0..cursorCol) lines[r][c] = ' '
                    '2' -> for (c in 0 until columns) lines[r][c] = ' '
                    else -> for (c in cursorCol until columns) lines[r][c] = ' '
                }
                fgLine[r] = -1; bgLine[r] = -1
            }
            'm' -> applySgr(params)
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h', 'l' -> { /* 模式开关忽略 */ }
            'L', 'M', 'P', 'X', '@' -> { /* 插入/删除行：忽略 */ }
            else -> { /* 其他 CSI 忽略 */ }
        }
    }

    private fun applySgr(params: String) {
        if (params.isEmpty()) { fgColor = -1; bgColor = -1; return }
        val codes = params.split(';').mapNotNull { it.toIntOrNull() }
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> { fgColor = -1; bgColor = -1 }
                1 -> { /* 粗体：忽略亮度 */ }
                30, 31, 32, 33, 34, 35, 36, 37 -> fgColor = code - 30
                38 -> {
                    if (i + 2 < codes.size && codes[i + 1] == 5) { fgColor = codes[i + 2] % 16; i += 2 }
                    else if (i + 4 < codes.size && codes[i + 1] == 2) { fgColor = trueColor(codes[i + 2], codes[i + 3], codes[i + 4]); i += 4 }
                }
                39 -> fgColor = -1
                40, 41, 42, 43, 44, 45, 46, 47 -> bgColor = code - 40
                48 -> {
                    if (i + 2 < codes.size && codes[i + 1] == 5) { bgColor = codes[i + 2] % 16; i += 2 }
                    else if (i + 4 < codes.size && codes[i + 1] == 2) { bgColor = trueColor(codes[i + 2], codes[i + 3], codes[i + 4]); i += 4 }
                }
                49 -> bgColor = -1
                90, 91, 92, 93, 94, 95, 96, 97 -> fgColor = code - 90 + 8
                100, 101, 102, 103, 104, 105, 106, 107 -> bgColor = code - 100 + 8
            }
            i++
        }
    }

    // 真彩编码：+16 偏移避开调色板索引 0..15 与默认 -1
    private fun trueColor(r: Int, g: Int, b: Int): Int = (r shl 16) + (g shl 8) + b + 16

    private fun fgColorFor(idx: Int): Int =
        if (idx == -1) fontColor
        else if (idx < 16) palette16[idx % palette16.size]
        else Color.rgb(idx shr 16 and 0xFF, idx shr 8 and 0xFF, idx and 0xFF)

    private fun bgColorFor(idx: Int): Int =
        if (idx == -1) Color.TRANSPARENT
        else if (idx < 16) palette16[idx % palette16.size]
        else Color.rgb(idx shr 16 and 0xFF, idx shr 8 and 0xFF, idx and 0xFF)

    // ---------- 渲染 ----------

    private fun measureFont() {
        textPaint.textSize = fontSize
        val fm = textPaint.fontMetrics
        lineHeight = fm.descent - fm.ascent + dp(2f)
        charWidth = textPaint.measureText("M")
        columns = (width / charWidth).toInt().coerceAtLeast(20)
        rows = (height / lineHeight).toInt().coerceAtLeast(5)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measureFont()
        for (line in lines) {
            if (line.size != columns) {
                val nl = CharArray(columns) { ' ' }
                System.arraycopy(line, 0, nl, 0, minOf(line.size, columns))
                nl[nl.size - 1] = ' '
                // 原地替换
                lines[lines.indexOf(line)] = nl
            }
        }
        val oldRows = rows
        if (oldRows == 0 || oldRows > rows) ensureRows(rows.coerceAtLeast(5))
        cursorRow = cursorRow.coerceAtMost(lines.size - 1)
        cursorCol = cursorCol.coerceAtMost(columns - 1)
        resizeCallback?.invoke(columns, rows)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundColor)
        if (charWidth <= 0 || lineHeight <= 0) return

        val visibleStart = (lines.size - rows - scrollOffset).coerceAtLeast(0)
        textPaint.color = fontColor
        for (r in 0 until rows.coerceAtMost(lines.size - visibleStart)) {
            val line = lines[visibleStart + r]
            val baseY = (r + 1) * lineHeight
            for (c in 0 until columns) {
                val ch = line[c]
                if (ch < ' ') continue
                val bgIdx = bgLine.getOrNull(visibleStart + r) ?: -1
                if (bgIdx >= 0) {
                    bgPaint.color = bgColorFor(bgIdx)
                    canvas.drawRect(c * charWidth, r * lineHeight, (c + 1) * charWidth, (r + 1) * lineHeight, bgPaint)
                }
                textPaint.color = fgColorFor(fgLine.getOrNull(visibleStart + r) ?: -1)
                canvas.drawText(ch.toString(), c * charWidth, baseY - textPaint.fontMetrics.descent, textPaint)
            }
        }
        // 光标（仅当未滚动）
        if (scrollOffset == 0) {
            val cr = cursorRow.coerceAtMost(lines.size - 1)
            if (cr >= visibleStart && visibleStart + rows > cr) {
                val drawRow = cr - visibleStart
                textPaint.color = backgroundColor
                bgPaint.color = fontColor
                canvas.drawRect(
                    cursorCol * charWidth, drawRow * lineHeight,
                    (cursorCol + 1) * charWidth, (drawRow + 1) * lineHeight, bgPaint
                )
                val ch = lines[cr][cursorCol]
                if (ch >= ' ') {
                    canvas.drawText(ch.toString(), cursorCol * charWidth, (drawRow + 1) * lineHeight - textPaint.fontMetrics.descent, textPaint)
                }
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}