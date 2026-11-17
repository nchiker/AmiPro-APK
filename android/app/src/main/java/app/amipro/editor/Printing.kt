package app.amipro.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import java.io.FileOutputStream
import uniffi.amipro_mobile.Align
import uniffi.amipro_mobile.Paragraph

/**
 * Prints what the phone shows: the text with its character formatting, paragraph style look and alignment, on the paper size
 * the print dialog chose (which also offers "Save as PDF"). Pictures, frames and tables are not shown on the phone and are
 * not printed (an object is printed as the same box the screen shows). Margins are 0.75 inch.
 */
class DocumentPrintAdapter(
    private val context: Context,
    private val jobName: String,
    private val paragraphs: List<Paragraph>,
) : PrintDocumentAdapter() {
    private class Piece(val layout: StaticLayout, val firstLine: Int, val lastLine: Int)

    private var attributes: PrintAttributes? = null
    private var pages: List<List<Piece>> = emptyList()

    override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        if (cancel.isCanceled) { callback.onLayoutCancelled(); return }
        val media = new.mediaSize ?: PrintAttributes.MediaSize.NA_LETTER
        val landscape = new.mediaSize?.isPortrait == false
        val wPt = (if (landscape) media.heightMils else media.widthMils) * 72f / 1000f
        val hPt = (if (landscape) media.widthMils else media.heightMils) * 72f / 1000f
        val margin = 54f
        val width = (wPt - 2 * margin).toInt().coerceAtLeast(72)
        val height = (hPt - 2 * margin).toInt().coerceAtLeast(72)
        pages = paginate(width, height)
        attributes = new
        val info = PrintDocumentInfo.Builder(jobName.ifBlank { "Document" } + ".pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pages.size.coerceAtLeast(1))
            .build()
        callback.onLayoutFinished(info, old?.mediaSize != new.mediaSize || old?.minMargins != new.minMargins || old == null)
    }

    override fun onWrite(range: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback) {
        val attrs = attributes ?: run { callback.onWriteFailed("Not laid out"); return }
        val pdf = PrintedPdfDocument(context, attrs)
        try {
            val count = pages.size.coerceAtLeast(1)
            for (n in 0 until count) {
                if (cancel.isCanceled) { callback.onWriteCancelled(); return }
                if (range.none { n >= it.start && n <= it.end } && range.none { it == PageRange.ALL_PAGES }) continue
                val page = pdf.startPage(n)
                drawPage(page.canvas, pages.getOrNull(n).orEmpty())
                pdf.finishPage(page)
            }
            FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        } finally {
            pdf.close()
        }
    }

    private fun drawPage(canvas: Canvas, pieces: List<Piece>) {
        canvas.translate(54f, 54f)
        var y = 0f
        for (p in pieces) {
            val top = p.layout.getLineTop(p.firstLine)
            val bottom = p.layout.getLineBottom(p.lastLine)
            canvas.save()
            canvas.translate(0f, y - top)
            canvas.clipRect(0f, top.toFloat(), p.layout.width.toFloat(), bottom.toFloat())
            p.layout.draw(canvas)
            canvas.restore()
            y += (bottom - top)
        }
    }

    /** Fills pages line by line; a line is never cut. An empty document prints one empty page. */
    private fun paginate(width: Int, height: Int): List<List<Piece>> {
        val pages = mutableListOf<MutableList<Piece>>()
        var current = mutableListOf<Piece>()
        var used = 0
        for (p in paragraphs) {
            val layout = layoutOf(p, width)
            var line = 0
            while (line < layout.lineCount) {
                var last = line - 1
                var h = used
                while (last + 1 < layout.lineCount) {
                    val lh = layout.getLineBottom(last + 1) - layout.getLineTop(last + 1)
                    if (h + lh > height && (h > 0)) break
                    h += lh; last++
                }
                if (last < line) { // nothing fits on this page any more
                    pages += current; current = mutableListOf(); used = 0; continue
                }
                current += Piece(layout, line, last)
                used = h
                line = last + 1
                if (line < layout.lineCount) { pages += current; current = mutableListOf(); used = 0 }
            }
        }
        if (current.isNotEmpty() || pages.isEmpty()) pages += current
        return pages
    }

    private fun layoutOf(p: Paragraph, width: Int): StaticLayout {
        val shown = p.text.replace(Char(0xFFFC), Char(0x25A3)).replace(Char(0x2028), '\n')
        val text = SpannableStringBuilder(shown)
        // Effective look of each run: the paragraph style's, then each span in order overriding what it sets.
        val cuts = sortedSetOf(0, shown.length)
        for (s in p.spans) { cuts += s.start.toInt().coerceIn(0, shown.length); cuts += s.end.toInt().coerceIn(0, shown.length) }
        val points = cuts.toList()
        for (i in 0 until points.size - 1) {
            val from = points[i]; val to = points[i + 1]
            var bold = p.bold; var italic = p.italic; var underline = p.underline; var strike = false
            var size = p.sizePoints.toFloat(); var family = p.family; var colour: Long? = null
            for (s in p.spans) {
                if (s.start.toInt() > from || s.end.toInt() < to) continue
                s.bold?.let { bold = it }; s.italic?.let { italic = it }; s.underline?.let { underline = it }; s.strikethrough?.let { strike = it }
                s.sizePoints?.let { size = it.toFloat() }; s.family?.let { family = it }; s.colour?.let { colour = it.toLong() }
            }
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            text.setSpan(TypefaceSpan(generic(family)), from, to, flags)
            text.setSpan(StyleSpan(when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }), from, to, flags)
            text.setSpan(AbsoluteSizeSpan(size.toInt().coerceAtLeast(1), false), from, to, flags)
            if (underline) text.setSpan(UnderlineSpan(), from, to, flags)
            if (strike) text.setSpan(StrikethroughSpan(), from, to, flags)
            colour?.let { text.setSpan(ForegroundColorSpan(0xFF000000.toInt() or it.toInt()), from, to, flags) }
        }
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = p.sizePoints.toFloat(); color = 0xFF000000.toInt() }
        val align = when (p.align) {
            Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE; Align.CENTRE -> Layout.Alignment.ALIGN_CENTER; else -> Layout.Alignment.ALIGN_NORMAL
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setJustificationMode(if (p.align == Align.JUSTIFY) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
            .setIncludePad(false)
            .build()
    }

    /** Same rule as the screen: the nearest of serif, sans-serif and monospace. */
    private fun generic(family: String): String {
        val f = family.lowercase()
        return when {
            listOf("courier", "mono", "consolas", "lucida console", "terminal").any { it in f } -> "monospace"
            listOf("times", "roman", "georgia", "garamond", "palatino", "century", "book", "serif").any { it in f } && "sans" !in f -> "serif"
            else -> "sans-serif"
        }
    }
}
