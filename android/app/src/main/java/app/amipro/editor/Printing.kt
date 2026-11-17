package app.amipro.editor

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import android.text.style.LeadingMarginSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import java.io.FileOutputStream
import uniffi.amipro_mobile.Align
import uniffi.amipro_mobile.ObjectInfo
import uniffi.amipro_mobile.ObjectKind
import uniffi.amipro_mobile.PageSetup
import uniffi.amipro_mobile.Paragraph
import uniffi.amipro_mobile.PictureImage
import uniffi.amipro_mobile.TableView

/** What a print job needs from the open document, read once when Print is chosen. */
class PrintData(
    val paragraphs: List<Paragraph>,
    val objects: List<ObjectInfo>,
    val setup: PageSetup,
    val picture: (ObjectInfo) -> PictureImage?,
    val table: (ObjectInfo) -> TableView?,
    val frameText: (ObjectInfo) -> String?,
)

/** The paper the document asks for, as the print dialog's starting choice (the person can still change it). */
fun printAttributesFor(setup: PageSetup): PrintAttributes {
    val wMils = setup.widthTwips * 1000 / 1440
    val hMils = setup.heightTwips * 1000 / 1440
    val known = listOf(
        PrintAttributes.MediaSize.NA_LETTER, PrintAttributes.MediaSize.NA_LEGAL, PrintAttributes.MediaSize.ISO_A4,
        PrintAttributes.MediaSize.ISO_A5, PrintAttributes.MediaSize.ISO_A3, PrintAttributes.MediaSize.NA_TABLOID,
    )
    val portrait = hMils >= wMils
    val match = known.firstOrNull { Math.abs(it.widthMils - minOf(wMils, hMils)) < 60 && Math.abs(it.heightMils - maxOf(wMils, hMils)) < 60 }
    val media = (match ?: PrintAttributes.MediaSize("amipro_custom", "Document", minOf(wMils, hMils), maxOf(wMils, hMils)))
        .let { if (portrait) it.asPortrait() else it.asLandscape() }
    return PrintAttributes.Builder().setMediaSize(media).build()
}

/**
 * Prints the document on the paper the print dialog chose (which also offers "Save as PDF"): the document's own margins,
 * header and footer with page numbers, each paragraph's face, size, alignment, indents and line spacing, character formatting,
 * page breaks, pictures (the ones the phone can draw), tables and text frames after the paragraph that holds them.
 * Not done: columns, and frames placed at fixed spots on a page.
 */
class DocumentPrintAdapter(
    private val context: Context,
    private val jobName: String,
    private val data: PrintData,
) : PrintDocumentAdapter() {
    /** One thing to draw on a page: [height] and [gap] (space above, not at the top of a page) are in points. */
    private class Item(val height: Float, val gap: Float, val breakBefore: Boolean = false, val draw: (Canvas) -> Unit)

    private class Page(val items: MutableList<Item> = mutableListOf())

    private var attributes: PrintAttributes? = null
    private var pages: List<Page> = emptyList()
    private var margins = FloatArray(4)
    private var paper = floatArrayOf(612f, 792f)

    override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        if (cancel.isCanceled) { callback.onLayoutCancelled(); return }
        val media = new.mediaSize ?: PrintAttributes.MediaSize.NA_LETTER
        val landscape = !media.isPortrait
        val wPt = (if (landscape) media.heightMils else media.widthMils) * 72f / 1000f
        val hPt = (if (landscape) media.widthMils else media.heightMils) * 72f / 1000f
        paper = floatArrayOf(wPt, hPt)
        val s = data.setup
        // The document's margins (at least a quarter inch; a printer cannot reach the edge).
        margins = floatArrayOf(s.marginLeft / 20f, s.marginTop / 20f, s.marginRight / 20f, s.marginBottom / 20f).map { it.coerceIn(18f, 216f) }.toFloatArray()
        val width = (wPt - margins[0] - margins[2]).toInt().coerceAtLeast(72)
        val height = (hPt - margins[1] - margins[3]).toInt().coerceAtLeast(72)
        pages = paginate(width, height)
        attributes = new
        val info = PrintDocumentInfo.Builder(jobName.ifBlank { "Document" } + ".pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pages.size.coerceAtLeast(1))
            .build()
        callback.onLayoutFinished(info, old == null || old.mediaSize != new.mediaSize || old.minMargins != new.minMargins)
    }

    override fun onWrite(range: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback) {
        val attrs = attributes ?: run { callback.onWriteFailed("Not laid out"); return }
        val pdf = PrintedPdfDocument(context, attrs)
        try {
            val count = pages.size.coerceAtLeast(1)
            for (n in 0 until count) {
                if (cancel.isCanceled) { callback.onWriteCancelled(); return }
                if (range.none { n >= it.start && n <= it.end }) continue
                val page = pdf.startPage(n)
                drawPage(page.canvas, n, count, pages.getOrNull(n))
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

    private fun drawPage(canvas: Canvas, index: Int, count: Int, page: Page?) {
        val width = (paper[0] - margins[0] - margins[2]).toInt().coerceAtLeast(72)
        // Header and footer sit in the margins, with this page's number.
        band(data.setup.header.takeUnless { data.setup.headerFromSecondPage && index == 0 }.orEmpty(), width, index + 1)?.let { (layouts, total) ->
            canvas.save(); canvas.translate(margins[0], (margins[1] - total).coerceAtLeast(10f) / 1.4f)
            layouts.forEach { it.draw(canvas); canvas.translate(0f, it.height.toFloat()) }
            canvas.restore()
        }
        band(data.setup.footer.takeUnless { data.setup.footerFromSecondPage && index == 0 }.orEmpty(), width, index + 1)?.let { (layouts, total) ->
            canvas.save(); canvas.translate(margins[0], paper[1] - margins[3] / 2f - total / 2f)
            layouts.forEach { it.draw(canvas); canvas.translate(0f, it.height.toFloat()) }
            canvas.restore()
        }
        canvas.translate(margins[0], margins[1])
        var y = 0f
        page?.items?.forEachIndexed { i, item ->
            if (i > 0) y += item.gap
            canvas.save()
            canvas.translate(0f, y)
            item.draw(canvas)
            canvas.restore()
            y += item.height
        }
    }

    private fun band(paragraphs: List<Paragraph>, width: Int, page: Int): Pair<List<StaticLayout>, Float>? {
        if (paragraphs.isEmpty()) return null
        val layouts = paragraphs.map { p -> layoutOf(p.copy(text = p.text.replace(PAGE_TOKEN.toString(), page.toString()), spans = emptyList()), 0, p.text.replace(PAGE_TOKEN.toString(), page.toString()).length, width) }
        return layouts to layouts.sumOf { it.height }.toFloat()
    }

    /** Fills pages with items in document order; a line, picture or table row is never cut; a page break starts a new page. */
    private fun paginate(width: Int, height: Int): List<Page> {
        val items = mutableListOf<Item>()
        val byParagraph = data.objects.groupBy { it.paragraph.toInt() }
        for ((index, p) in data.paragraphs.withIndex()) {
            val objects = byParagraph[index].orEmpty()
            val breaks = objects.filter { it.kind == ObjectKind.PAGE_BREAK }.map { it.offset.toInt() }.sorted()
            // The text between page breaks, each part laid out from its own start.
            val cuts = listOf(0) + breaks.map { it + 1 } + listOf(p.text.length)
            for (k in 0 until cuts.size - 1) {
                val from = cuts[k]; val to = if (k < breaks.size) breaks[k] else cuts.last()
                if (k > 0) items += Item(0f, 0f, breakBefore = true) {}
                if (from > to) continue
                val layout = layoutOf(p, from, to, width)
                for (line in 0 until layout.lineCount) {
                    val top = layout.getLineTop(line).toFloat(); val bottom = layout.getLineBottom(line).toFloat()
                    items += Item(bottom - top, if (line == 0) PARAGRAPH_GAP else 0f) { canvas ->
                        canvas.clipRect(0f, 0f, width.toFloat(), bottom - top)
                        canvas.translate(0f, -top)
                        layout.draw(canvas)
                    }
                }
            }
            for (o in objects) objectItems(o, width, height).forEach { items += it }
        }
        val pages = mutableListOf(Page())
        var used = 0f
        for (item in items) {
            if (item.breakBefore) { if (pages.last().items.isNotEmpty()) { pages += Page(); used = 0f }; continue }
            val empty = pages.last().items.isEmpty()
            if (!empty && used + item.gap + item.height > height) { pages += Page(); used = 0f }
            val first = pages.last().items.isEmpty()
            pages.last().items += item
            used += (if (first) 0f else item.gap) + item.height
        }
        return pages
    }

    private val outline = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = 0xFF000000.toInt() }

    /** A picture, table or text frame after the paragraph that holds it. */
    private fun objectItems(o: ObjectInfo, width: Int, pageHeight: Int): List<Item> = when (o.kind) {
        ObjectKind.PICTURE -> {
            val image = data.picture(o)?.let { BitmapFactory.decodeByteArray(it.png, 0, it.png.size) }
            if (image == null) boxed(o.label, width)
            else {
                val natural = image.width * 0.75f
                var w = (if (o.widthTwips > 0) o.widthTwips / 20f else natural).coerceAtMost(width.toFloat())
                var h = w * image.height / image.width
                if (h > pageHeight) { h = pageHeight.toFloat(); w = h * image.width / image.height }
                listOf(Item(h, PARAGRAPH_GAP) { canvas -> canvas.drawBitmap(image, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG)) })
            }
        }
        ObjectKind.TABLE -> data.table(o)?.let { tableItems(it, if (o.widthTwips > 0) (o.widthTwips / 20f).coerceAtMost(width.toFloat()) else width.toFloat()) } ?: boxed(o.label, width)
        ObjectKind.TEXT_FRAME -> data.frameText(o)?.takeIf { it.isNotBlank() }?.let { boxed(it, width, tall = true) } ?: emptyList()
        else -> emptyList()
    }

    /** Text in an outlined box (also what stands for a picture the phone cannot draw). */
    private fun boxed(text: String, width: Int, tall: Boolean = false): List<Item> {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = 0xFF000000.toInt() }
        val shown = if (tall) text else "[$text]"
        val layout = StaticLayout.Builder.obtain(shown, 0, shown.length, paint, width - 8).build()
        val h = layout.height + 8f
        return listOf(Item(h, PARAGRAPH_GAP) { canvas ->
            canvas.drawRect(0f, 0f, width.toFloat(), h, outline)
            canvas.translate(4f, 4f); layout.draw(canvas)
        })
    }

    private fun tableItems(table: TableView, width: Float): List<Item> {
        val columns = table.columns.toInt().coerceAtLeast(1)
        val cellWidth = width / columns
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = 0xFF000000.toInt() }
        return table.cells.chunked(columns).mapIndexed { r, row ->
            val layouts = row.map { text -> StaticLayout.Builder.obtain(text, 0, text.length, paint, (cellWidth - 6).toInt().coerceAtLeast(10)).build() }
            val h = (layouts.maxOfOrNull { it.height } ?: 0) + 6f
            Item(h, if (r == 0) PARAGRAPH_GAP else 0f) { canvas ->
                layouts.forEachIndexed { c, layout ->
                    canvas.drawRect(c * cellWidth, 0f, (c + 1) * cellWidth, h, outline)
                    canvas.save(); canvas.translate(c * cellWidth + 3f, 3f); layout.draw(canvas); canvas.restore()
                }
            }
        }
    }

    /** One paragraph (or the part of it between page breaks) as a layout in a column [width] points wide. */
    private fun layoutOf(p: Paragraph, from: Int, to: Int, width: Int): StaticLayout {
        val whole = p.text.replace(Char(0xFFFC), ' ').replace(Char(0x2028), '\n')
        val shown = whole.substring(from.coerceIn(0, whole.length), to.coerceIn(0, whole.length))
        val text = SpannableStringBuilder(shown)
        // Effective look of each run: the paragraph style's, then each span in order overriding what it sets.
        val cuts = sortedSetOf(0, shown.length)
        for (s in p.spans) { cuts += (s.start.toInt() - from).coerceIn(0, shown.length); cuts += (s.end.toInt() - from).coerceIn(0, shown.length) }
        val points = cuts.toList()
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            var bold = p.bold; var italic = p.italic; var underline = p.underline; var strike = false
            var size = p.sizePoints; var family = p.family; var colour: Long? = null
            for (s in p.spans) {
                if (s.start.toInt() - from > a || s.end.toInt() - from < b) continue
                s.bold?.let { bold = it }; s.italic?.let { italic = it }; s.underline?.let { underline = it }; s.strikethrough?.let { strike = it }
                s.sizePoints?.let { size = it }; s.family?.let { family = it }; s.colour?.let { colour = it.toLong() }
            }
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            text.setSpan(TypefaceSpan(generic(family)), a, b, flags)
            text.setSpan(StyleSpan(when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }), a, b, flags)
            text.setSpan(AbsoluteSizeSpan(size.toInt().coerceAtLeast(1), false), a, b, flags)
            if (underline) text.setSpan(UnderlineSpan(), a, b, flags)
            if (strike) text.setSpan(StrikethroughSpan(), a, b, flags)
            colour?.let { text.setSpan(ForegroundColorSpan(0xFF000000.toInt() or it.toInt()), a, b, flags) }
        }
        val left = (p.indentLeft / 20).coerceAtLeast(0)
        val firstLine = ((p.indentLeft + p.indentFirst) / 20).coerceAtLeast(0)
        if (text.isNotEmpty() && (left > 0 || firstLine > 0)) text.setSpan(LeadingMarginSpan.Standard(firstLine, left), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = p.sizePoints; color = 0xFF000000.toInt() }
        val align = when (p.align) {
            Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE; Align.CENTRE -> Layout.Alignment.ALIGN_CENTER; else -> Layout.Alignment.ALIGN_NORMAL
        }
        val usable = (width - p.indentRight / 20).coerceAtLeast(36)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, usable)
            .setAlignment(align)
            .setLineSpacing(0f, p.lineSpacingFactor)
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

    private companion object {
        const val PARAGRAPH_GAP = 5f
        val PAGE_TOKEN = Char(0xE000)
    }
}
