//! What the phone needs beyond single-paragraph text edits: edits and formatting across paragraphs, paragraph
//! layout (alignment, indents, line spacing), fonts, find and replace, a list of objects with previews, and the
//! page setup for printing. Everything here is a thin translation of commands the Linux core already has.

use amipro_document::{FrameContent, InlineObject, LineSpacing, PictureSource};
use amipro_editing::IndentChange;

use super::*;

/// One UTF-16 range that matched a search.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct FoundText {
    pub paragraph: u32,
    pub start: u32,
    pub end: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum LineSpacingChoice {
    Single,
    OneAndHalf,
    Double,
    /// Back to the paragraph style's spacing.
    Style,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum ObjectKind {
    Picture,
    Table,
    TextFrame,
    PageBreak,
    ColumnBreak,
    PageNumber,
    Other,
}

/// An object (U+FFFC) in the main text: where it is, what it is, and how big it is meant to be.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ObjectInfo {
    pub paragraph: u32,
    pub offset: u32,
    pub kind: ObjectKind,
    pub label: String,
    /// The frame's size in twips when the document gives one (0 otherwise).
    pub width_twips: i32,
    pub height_twips: i32,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PictureImage {
    pub width: u32,
    pub height: u32,
    pub png: Vec<u8>,
}

/// The text of a table, row by row.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct TableView {
    pub columns: u32,
    pub cells: Vec<String>,
}

/// The first page layout's paper, margins and the text of its header and footer (page numbers show as U+E000).
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PageSetup {
    pub width_twips: i32,
    pub height_twips: i32,
    pub margin_left: i32,
    pub margin_right: i32,
    pub margin_top: i32,
    pub margin_bottom: i32,
    pub header: Vec<Paragraph>,
    pub footer: Vec<Paragraph>,
    /// The header (footer) starts on page 2, not page 1.
    pub header_from_second_page: bool,
    pub footer_from_second_page: bool,
}

/// The token a page number is shown as in a header or footer paragraph.
pub const PAGE_NUMBER_TOKEN: char = '\u{E000}';

fn text_position(editor: &Editor, paragraph: u32, text: &str, unit: u32) -> Result<TextPosition, AmiError> {
    let byte = byte_of(text, unit).ok_or_else(|| failed("Position is outside the paragraph"))?;
    Ok(position(editor, paragraph, byte))
}

/// Two positions in document order.
fn ordered(editor: &Editor, p0: u32, s0: u32, p1: u32, s1: u32) -> Result<(TextPosition, TextPosition), AmiError> {
    let ((p0, s0), (p1, s1)) = if (p0, s0) <= (p1, s1) { ((p0, s0), (p1, s1)) } else { ((p1, s1), (p0, s0)) };
    let flow = editor.document().main_flow();
    let a = flow.paragraph(p0 as usize).ok_or_else(|| failed("No such paragraph"))?;
    let b = flow.paragraph(p1 as usize).ok_or_else(|| failed("No such paragraph"))?;
    Ok((text_position(editor, p0, &a.text, s0)?, text_position(editor, p1, &b.text, s1)?))
}

fn range_has_object(editor: &Editor, a: TextPosition, b: TextPosition) -> bool {
    let flow = editor.document().main_flow();
    (a.paragraph..=b.paragraph).any(|index| {
        let Some(paragraph) = flow.paragraph(index) else { return false };
        let from = if index == a.paragraph { a.byte } else { 0 };
        let to = if index == b.paragraph { b.byte } else { paragraph.text.len() };
        paragraph.text.get(from..to).is_some_and(|text| text.contains(OBJECT_CHAR))
    })
}

fn bgr(rgb: u32) -> u32 {
    ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF)
}

fn same(a: char, b: char, match_case: bool) -> bool {
    a == b || (!match_case && a.to_lowercase().next() == b.to_lowercase().next())
}

/// Every match of `query` in the main text, as UTF-16 ranges inside one paragraph.
fn search(editor: &Editor, query: &str, match_case: bool, whole_word: bool) -> Vec<FoundText> {
    const LIMIT: usize = 5000;
    let needle: Vec<char> = query.chars().collect();
    let mut found = Vec::new();
    if needle.is_empty() || needle.contains(&OBJECT_CHAR) || needle.contains(&'\n') {
        return found;
    }
    let word = |ch: char| ch.is_alphanumeric() || ch == '_';
    let flow = editor.document().main_flow();
    for index in 0..flow.len() {
        let Some(paragraph) = flow.paragraph(index) else { continue };
        let chars: Vec<(char, u32)> = {
            let mut unit = 0u32;
            paragraph
                .text
                .chars()
                .map(|ch| {
                    let at = unit;
                    unit += ch.len_utf16() as u32;
                    (ch, at)
                })
                .collect()
        };
        let total = paragraph.text.encode_utf16().count() as u32;
        let mut at = 0;
        while at + needle.len() <= chars.len() {
            let hit = needle.iter().enumerate().all(|(k, &ch)| same(chars[at + k].0, ch, match_case));
            let bounded = !whole_word
                || ((at == 0 || !word(chars[at - 1].0))
                    && (at + needle.len() == chars.len() || !word(chars[at + needle.len()].0)));
            if hit && bounded {
                let end = chars.get(at + needle.len()).map_or(total, |(_, unit)| *unit);
                found.push(FoundText { paragraph: index as u32, start: chars[at].1, end });
                if found.len() >= LIMIT {
                    return found;
                }
                at += needle.len();
            } else {
                at += 1;
            }
        }
    }
    found
}

fn object_info(document: &Document, paragraph: u32, offset: u32, object: &InlineObject) -> ObjectInfo {
    let (kind, label, width, height) = match object {
        InlineObject::Break { kind, .. } => match kind {
            amipro_document::BreakKind::Page => (ObjectKind::PageBreak, "Page break".to_string(), 0, 0),
            amipro_document::BreakKind::Column => (ObjectKind::ColumnBreak, "Column break".to_string(), 0, 0),
        },
        InlineObject::PageNumber(_) => (ObjectKind::PageNumber, "Page number".to_string(), 0, 0),
        InlineObject::FrameAnchor(id) => match document.frame(*id) {
            Some(frame) => {
                let (width, height) = match frame.placement {
                    amipro_document::Placement::Fixed { rect, .. } | amipro_document::Placement::Repeating { rect, .. } => {
                        (rect.right - rect.left, rect.bottom - rect.top)
                    }
                    amipro_document::Placement::BelowParagraph { size, .. } | amipro_document::Placement::Inline { size, .. } => {
                        (size.width, size.height)
                    }
                    _ => (0, 0),
                };
                match &frame.content {
                    FrameContent::Picture(picture) => {
                        let label = if picture.description.trim().is_empty() {
                            format!("Picture {}", picture.extension.trim_start_matches('.').to_uppercase()).trim().to_string()
                        } else {
                            picture.description.clone()
                        };
                        (ObjectKind::Picture, label, width, height)
                    }
                    FrameContent::Table(table) => {
                        (ObjectKind::Table, format!("Table, {} rows by {} columns", table.rows.len(), table.columns.len()), width, height)
                    }
                    FrameContent::Text(_) => (ObjectKind::TextFrame, "Text frame".to_string(), width, height),
                    FrameContent::Legacy(_) => (ObjectKind::Other, "Drawing, chart or equation".to_string(), width, height),
                }
            }
            None => (ObjectKind::Other, "Frame".to_string(), 0, 0),
        },
        _ => (ObjectKind::Other, "Field or mark".to_string(), 0, 0),
    };
    ObjectInfo { paragraph, offset, kind, label, width_twips: width, height_twips: height }
}

/// The k-th object of a paragraph, found from its text offset.
fn object_at<'a>(document: &'a Document, paragraph: u32, offset: u32) -> Option<(&'a InlineObject, usize)> {
    let source = document.main_flow().paragraph(paragraph as usize)?;
    let mut unit = 0u32;
    let mut k = 0usize;
    for ch in source.text.chars() {
        if unit == offset {
            return (ch == OBJECT_CHAR).then(|| source.objects.get(k)).flatten().map(|object| (object, k));
        }
        if ch == OBJECT_CHAR {
            k += 1;
        }
        unit += ch.len_utf16() as u32;
    }
    None
}

fn raster_png(raster: amipro_pictures::Raster) -> Option<PictureImage> {
    let image = image::RgbaImage::from_raw(raster.width, raster.height, raster.pixels)?;
    let longest = image.width().max(image.height());
    let image = if longest > 1600 {
        let scale = 1600.0 / f64::from(longest);
        image::imageops::resize(
            &image,
            ((f64::from(image.width()) * scale) as u32).max(1),
            ((f64::from(image.height()) * scale) as u32).max(1),
            image::imageops::FilterType::Triangle,
        )
    } else {
        image
    };
    let mut png = Vec::new();
    image::DynamicImage::ImageRgba8(image.clone())
        .write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)
        .ok()?;
    Some(PictureImage { width: image.width(), height: image.height(), png })
}

/// What a picture's bytes show: a bitmap, any common raster format, or Ami Pro's own screen image of it.
fn decode_picture(bytes: &[u8], screen: Option<&[u8]>) -> Option<PictureImage> {
    use amipro_pictures::dib;
    let decoded = if bytes.starts_with(b"BM") {
        dib::decode_bmp(bytes)
    } else {
        dib::Header::read(bytes)
            .and_then(|_| dib::decode_packed(bytes, dib::ColourTable::Rgb))
            .or_else(|| {
                image::load_from_memory(bytes).ok().map(|image| {
                    let rgba = image.into_rgba8();
                    amipro_pictures::Raster { width: rgba.width(), height: rgba.height(), pixels: rgba.into_raw() }
                })
            })
    };
    decoded
        .and_then(raster_png)
        .or_else(|| screen.and_then(amipro_pictures::screen::decode).and_then(raster_png))
}

#[uniffi::export]
impl AmiDoc {
    /// Replaces the text from (`p0`, `s0`) to (`p1`, `s1`), across paragraphs; a `\n` in `text` starts a paragraph.
    /// Refused when the range holds a picture, frame or table anchor.
    pub fn replace_range(&self, p0: u32, s0: u32, p1: u32, s1: u32, text: String) -> Result<(), AmiError> {
        let mut state = self.lock();
        let (a, b) = ordered(&state.editor, p0, s0, p1, s1)?;
        if range_has_object(&state.editor, a, b) {
            return Err(AmiError::ProtectedObject);
        }
        if a == b && text.is_empty() {
            return Ok(());
        }
        state
            .editor
            .replace_document_range(Cursor { paragraph: a.paragraph, byte: a.byte }, Cursor { paragraph: b.paragraph, byte: b.byte }, text)
            .map_err(failed)?;
        state.edited = true;
        Ok(())
    }

    /// The text of a range for copying: paragraphs joined by `\n`, objects left out.
    pub fn text_in_range(&self, p0: u32, s0: u32, p1: u32, s1: u32) -> Result<String, AmiError> {
        let state = self.lock();
        let (a, b) = ordered(&state.editor, p0, s0, p1, s1)?;
        let flow = state.editor.document().main_flow();
        let mut out = String::new();
        for index in a.paragraph..=b.paragraph {
            let Some(paragraph) = flow.paragraph(index) else { continue };
            let from = if index == a.paragraph { a.byte } else { 0 };
            let to = if index == b.paragraph { b.byte } else { paragraph.text.len() };
            if index > a.paragraph {
                out.push('\n');
            }
            out.extend(paragraph.text.get(from..to).unwrap_or("").chars().filter(|ch| *ch != OBJECT_CHAR));
        }
        Ok(out)
    }

    /// Like [`AmiDoc::set_attribute`] but over a range that may span paragraphs.
    pub fn set_attribute_between(&self, p0: u32, s0: u32, p1: u32, s1: u32, attr: Attr, on: bool) -> Result<(), AmiError> {
        let mut state = self.lock();
        let (start, end) = ordered(&state.editor, p0, s0, p1, s1)?;
        if start == end {
            return Err(failed("Nothing is selected"));
        }
        state
            .editor
            .execute(&Command::SetCharacterAttribute { start, end, attribute: attr.core(), on })
            .map_err(failed)?;
        state.edited = true;
        Ok(())
    }

    /// Face, size and colour (`0xRRGGBB`) over a range that may span paragraphs; `None` keeps what is there.
    pub fn set_font_between(
        &self,
        p0: u32,
        s0: u32,
        p1: u32,
        s1: u32,
        family: Option<String>,
        size_points: Option<f32>,
        colour: Option<u32>,
    ) -> Result<(), AmiError> {
        let mut state = self.lock();
        let (start, end) = ordered(&state.editor, p0, s0, p1, s1)?;
        if start == end {
            return Err(failed("Nothing is selected"));
        }
        state
            .editor
            .execute(&Command::SetFontParts {
                start,
                end,
                family,
                size: size_points.map(|size| size.clamp(4.0, 144.0)),
                colour: colour.map(bgr),
            })
            .map_err(failed)?;
        state.edited = true;
        Ok(())
    }

    /// The button states for a range that may span paragraphs: on only where every paragraph's part is on.
    pub fn format_state_between(&self, p0: u32, s0: u32, p1: u32, s1: u32) -> Option<FormatState> {
        let ((p0, s0), (p1, s1)) = if (p0, s0) <= (p1, s1) { ((p0, s0), (p1, s1)) } else { ((p1, s1), (p0, s0)) };
        if p0 == p1 {
            return self.format_state(p0, s0, s1);
        }
        let mut combined: Option<FormatState> = None;
        for index in p0..=p1 {
            let length = self.paragraph(index)?.text.encode_utf16().count() as u32;
            let from = if index == p0 { s0 } else { 0 };
            let to = if index == p1 { s1 } else { length };
            let part = self.format_state(index, from, to)?;
            combined = Some(match combined {
                None => part,
                Some(all) => FormatState {
                    bold: all.bold && part.bold,
                    italic: all.italic && part.italic,
                    underline: all.underline && part.underline,
                    strikethrough: all.strikethrough && part.strikethrough,
                    ..all
                },
            });
        }
        combined
    }

    /// Alignment of paragraphs `first..=last`.
    pub fn set_alignment(&self, first: u32, last: u32, align: Align) -> Result<(), AmiError> {
        let mut state = self.lock();
        let flow = state.editor.document().main_flow_id();
        let alignment = match align {
            Align::Left => Alignment::Left,
            Align::Right => Alignment::Right,
            Align::Centre => Alignment::Centre,
            Align::Justify => Alignment::Justify,
        };
        state
            .editor
            .execute(&Command::SetParagraphAlignment {
                flow,
                first: first.min(last) as usize,
                last: first.max(last) as usize,
                alignment: Some(alignment),
            })
            .map_err(failed)?;
        state.edited = true;
        Ok(())
    }

    /// Indents in twips of paragraphs `first..=last`; `None` keeps that indent as it is.
    pub fn set_indents(&self, first: u32, last: u32, left: Option<i32>, first_line: Option<i32>, right: Option<i32>) -> Result<(), AmiError> {
        let mut state = self.lock();
        let flow = state.editor.document().main_flow_id();
        state
            .editor
            .execute(&Command::SetParagraphIndents {
                flow,
                first: first.min(last) as usize,
                last: first.max(last) as usize,
                indents: IndentChange {
                    all: left.map(|v| v.clamp(0, 14_400)),
                    first_line: first_line.map(|v| v.clamp(-14_400, 14_400)),
                    rest: None,
                    right: right.map(|v| v.clamp(0, 14_400)),
                },
            })
            .map_err(failed)?;
        state.edited = true;
        Ok(())
    }

    /// Line spacing of paragraphs `first..=last`.
    pub fn set_line_spacing(&self, first: u32, last: u32, choice: LineSpacingChoice) -> Result<(), AmiError> {
        let mut state = self.lock();
        let flow = state.editor.document().main_flow_id();
        let (first, last) = (first.min(last) as usize, first.max(last) as usize);
        // A paragraph's own 1 1/2 or Double spacing is stored as the kind alone; its height is the style's.
        let commands: Vec<Command> = (first..=last)
            .map(|index| {
                let spacing = match choice {
                    LineSpacingChoice::Single => Some(LineSpacing::Single),
                    LineSpacingChoice::OneAndHalf => Some(LineSpacing::OneAndAHalf(0)),
                    LineSpacingChoice::Double => Some(LineSpacing::Double(0)),
                    LineSpacingChoice::Style => None,
                };
                Command::SetParagraphLineSpacing { flow, first: index, last: index, spacing }
            })
            .collect();
        state.editor.execute(&Command::Several(commands)).map_err(failed)?;
        state.edited = true;
        Ok(())
    }

    /// Faces to offer: the ones the document uses, then common ones.
    pub fn fonts(&self) -> Vec<String> {
        let state = self.lock();
        let document = state.editor.document();
        let mut names: Vec<String> = document.styles().values().map(|style| style.font_family.clone()).collect();
        let flow = document.main_flow();
        for index in 0..flow.len() {
            if let Some(paragraph) = flow.paragraph(index) {
                names.extend(paragraph.runs.iter().filter_map(|run| run.format.font_family.clone()));
            }
        }
        names.extend(["Times New Roman", "Arial", "Courier New", "Helvetica", "Georgia", "Verdana"].map(String::from));
        let mut seen = std::collections::HashSet::new();
        names.retain(|name| !name.trim().is_empty() && seen.insert(name.to_lowercase()));
        names
    }

    /// Every place `query` occurs, in order.
    pub fn find(&self, query: String, match_case: bool, whole_word: bool) -> Vec<FoundText> {
        search(&self.lock().editor, &query, match_case, whole_word)
    }

    /// Replaces every match as one undoable step; returns how many.
    pub fn replace_all(&self, query: String, with: String, match_case: bool, whole_word: bool) -> Result<u32, AmiError> {
        let mut state = self.lock();
        let hits = search(&state.editor, &query, match_case, whole_word);
        if hits.is_empty() {
            return Ok(0);
        }
        let mut commands = Vec::with_capacity(hits.len());
        // Last first, so earlier matches keep their positions.
        for hit in hits.iter().rev() {
            let Some(paragraph) = state.editor.document().main_flow().paragraph(hit.paragraph as usize) else { continue };
            let start = text_position(&state.editor, hit.paragraph, &paragraph.text, hit.start)?;
            let end = text_position(&state.editor, hit.paragraph, &paragraph.text, hit.end)?;
            commands.push(Command::ReplaceText { start, end, text: with.clone() });
        }
        state.editor.execute(&Command::Several(commands)).map_err(failed)?;
        state.edited = true;
        Ok(hits.len() as u32)
    }

    /// Every object of the main text, in order.
    pub fn objects(&self) -> Vec<ObjectInfo> {
        let state = self.lock();
        let document = state.editor.document();
        let flow = document.main_flow();
        let mut out = Vec::new();
        for index in 0..flow.len() {
            let Some(paragraph) = flow.paragraph(index) else { continue };
            if paragraph.objects.is_empty() {
                continue;
            }
            let mut unit = 0u32;
            let mut k = 0usize;
            for ch in paragraph.text.chars() {
                if ch == OBJECT_CHAR {
                    if let Some(object) = paragraph.objects.get(k) {
                        out.push(object_info(document, index as u32, unit, object));
                    }
                    k += 1;
                }
                unit += ch.len_utf16() as u32;
            }
        }
        out
    }

    /// The picture at an object, as PNG; `None` for a kind that cannot be drawn here (a metafile, a drawing).
    pub fn picture_image(&self, paragraph: u32, offset: u32) -> Option<PictureImage> {
        let state = self.lock();
        let document = state.editor.document();
        let (InlineObject::FrameAnchor(id), _) = object_at(document, paragraph, offset)? else { return None };
        let FrameContent::Picture(picture) = &document.frame(*id)?.content else { return None };
        let PictureSource::Embedded(object) = picture.source else { return None };
        let bytes = document.objects().get(&object)?.bytes.clone();
        let screen = picture.rendering.and_then(|id| document.objects().get(&id)).map(|object| object.bytes.clone());
        decode_picture(&bytes, screen.as_deref())
    }

    /// Plain text for search: each main-text paragraph, then (after the paragraph that holds it) the text of
    /// every table cell and text frame. Pictures, drawings, charts and equations hold no text here.
    pub fn plain_text(&self) -> String {
        let mut out = String::new();
        let objects = self.objects();
        for (index, p) in self.paragraphs().iter().enumerate() {
            let text = p.text.replace(OBJECT_CHAR, "").replace('\u{2028}', "\n");
            if !text.trim().is_empty() {
                out.push_str(&text);
                out.push('\n');
            }
            for o in objects.iter().filter(|o| o.paragraph as usize == index) {
                let extra = match o.kind {
                    ObjectKind::Table => self.table_text(o.paragraph, o.offset).map(|t| t.cells.join("\n")),
                    ObjectKind::TextFrame => self.frame_text(o.paragraph, o.offset),
                    _ => None,
                };
                if let Some(extra) = extra.filter(|e| !e.trim().is_empty()) {
                    out.push_str(&extra);
                    out.push('\n');
                }
            }
        }
        out
    }

    /// The text of a table at an object.
    pub fn table_text(&self, paragraph: u32, offset: u32) -> Option<TableView> {
        let state = self.lock();
        let document = state.editor.document();
        let (InlineObject::FrameAnchor(id), _) = object_at(document, paragraph, offset)? else { return None };
        let FrameContent::Table(table) = &document.frame(*id)?.content else { return None };
        let cells = table
            .cells
            .iter()
            .map(|cell| match cell.content {
                amipro_document::CellContent::Text(flow) => document
                    .flow(flow)
                    .map(|flow| {
                        flow.paragraphs.iter().map(|paragraph| paragraph.text.replace(OBJECT_CHAR, "").replace('\u{2028}', "\n")).collect::<Vec<_>>().join("\n")
                    })
                    .unwrap_or_default(),
            })
            .collect();
        Some(TableView { columns: table.columns.len().max(1) as u32, cells })
    }

    /// The text of a text frame at an object.
    pub fn frame_text(&self, paragraph: u32, offset: u32) -> Option<String> {
        let state = self.lock();
        let document = state.editor.document();
        let (InlineObject::FrameAnchor(id), _) = object_at(document, paragraph, offset)? else { return None };
        let FrameContent::Text(flow) = &document.frame(*id)?.content else { return None };
        Some(
            document
                .flow(*flow)?
                .paragraphs
                .iter()
                .map(|paragraph| paragraph.text.replace(OBJECT_CHAR, "").replace('\u{2028}', "\n"))
                .collect::<Vec<_>>()
                .join("\n"),
        )
    }

    /// Deletes the object at (`paragraph`, `offset`) and what it owns (one undo step).
    pub fn delete_object(&self, paragraph: u32, offset: u32) -> Result<(), AmiError> {
        let mut state = self.lock();
        if object_at(state.editor.document(), paragraph, offset).is_none() {
            return Err(failed("There is no object there"));
        }
        let source = state.editor.document().main_flow().paragraph(paragraph as usize).ok_or_else(|| failed("No such paragraph"))?;
        let byte = byte_of(&source.text, offset).ok_or_else(|| failed("Bad position"))?;
        // A single Backspace or Delete onto a frame anchor does nothing in the core; deleting a selection that holds it
        // deletes the frame and what it owns.
        let start = position(&state.editor, paragraph, byte);
        let end = position(&state.editor, paragraph, byte + OBJECT_CHAR.len_utf8());
        state.editor.execute(&Command::ReplaceText { start, end, text: String::new() }).map_err(failed)?;
        state.edited = true;
        Ok(())
    }

    /// Paper, margins, header and footer of the document's first page layout, for printing.
    pub fn page_setup(&self) -> PageSetup {
        let state = self.lock();
        let document = state.editor.document();
        let (second_header, second_footer) = document.layouts().first().map_or((false, false), |l| (l.header_from_second_page, l.footer_from_second_page));
        let (width, height, margins, header, footer) = match document.layouts().first() {
            Some(layout) => (
                layout.right.width,
                layout.right.height,
                layout.right.layout.margins,
                layout.header.right,
                layout.footer.right,
            ),
            None => (12_240, 15_840, amipro_document::Margins::uniform(1_440), None, None),
        };
        let band = |frame: Option<amipro_document::FrameId>| -> Vec<Paragraph> {
            let Some(frame) = frame.and_then(|id| document.frame(id)) else { return Vec::new() };
            let FrameContent::Text(id) = &frame.content else { return Vec::new() };
            let Some(flow) = document.flow(*id) else { return Vec::new() };
            (0..flow.len())
                .filter_map(|index| {
                    let mut paragraph = view_in(document, flow, index)?;
                    let source = flow.paragraph(index)?;
                    // Same length: a page number becomes one token character, any other object a space.
                    let mut k = 0;
                    paragraph.text = paragraph
                        .text
                        .chars()
                        .map(|ch| {
                            if ch != OBJECT_CHAR {
                                return ch;
                            }
                            let page = matches!(source.objects.get(k), Some(InlineObject::PageNumber(_)));
                            k += 1;
                            if page { PAGE_NUMBER_TOKEN } else { ' ' }
                        })
                        .collect();
                    Some(paragraph)
                })
                .collect()
        };
        PageSetup {
            width_twips: width,
            height_twips: height,
            margin_left: margins.left,
            margin_right: margins.right,
            margin_top: margins.top,
            margin_bottom: margins.bottom,
            header: band(header),
            footer: band(footer),
            header_from_second_page: second_header,
            footer_from_second_page: second_footer,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn encoded(format: image::ImageFormat) -> Vec<u8> {
        let image = image::RgbaImage::from_fn(6, 4, |x, y| image::Rgba([(x * 40) as u8, (y * 60) as u8, 200, 255]));
        let mut out = Vec::new();
        image::DynamicImage::ImageRgba8(image).write_to(&mut std::io::Cursor::new(&mut out), format).unwrap();
        out
    }

    #[test]
    fn bitmaps_and_common_formats_decode_to_png() {
        for format in [image::ImageFormat::Bmp, image::ImageFormat::Png, image::ImageFormat::Gif, image::ImageFormat::Jpeg] {
            let shown = decode_picture(&encoded(format), None).unwrap_or_else(|| panic!("{format:?} should decode"));
            assert_eq!((shown.width, shown.height), (6, 4), "{format:?}");
            assert!(shown.png.starts_with(&[0x89, b'P', b'N', b'G']));
        }
    }

    #[test]
    fn unreadable_bytes_give_nothing() {
        assert!(decode_picture(b"not a picture at all", None).is_none());
        assert!(decode_picture(&[], None).is_none());
    }
}
