//! Phone-facing wrapper over the Ami Pro for Linux core.
//!
//! The core (`amipro-document`, `amipro-formats`, `amipro-editing`) reads and
//! writes the files and keeps whatever it cannot model. This crate only
//! translates: bytes in, paragraphs and character spans out (as UTF-16
//! offsets, which is what Kotlin strings use), edits in, bytes out.

use std::sync::{Arc, Mutex, MutexGuard};

use amipro_document::{Alignment, CharacterFormat, Document, LineSpacing, NATIVE_MAGIC, StyleId, StyleOrigin};
use amipro_editing::{CharacterAttribute, Command, Cursor, Editor, TextPosition};
use amipro_formats::{ImportError, SamOptions, import_sam, protect, sam_document, sam_losses};

mod extras;

uniffi::setup_scaffolding!();

/// Marks an object (picture, frame anchor, ...) in paragraph text.
const OBJECT_CHAR: char = '\u{FFFC}';

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum AmiError {
    #[error("This document is protected with a password")]
    PasswordRequired,
    #[error("That password does not open this document")]
    WrongPassword,
    /// The edit would delete a picture, frame anchor or similar object the phone cannot show.
    #[error("That part holds a picture, frame or table that AmiPro cannot show. It is kept; edit the text around it.")]
    ProtectedObject,
    #[error("{detail}")]
    Failed { detail: String },
}

fn failed(message: impl ToString) -> AmiError {
    AmiError::Failed {
        detail: message.to_string(),
    }
}

/// Which format a document was opened from (and is saved back to).
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FileKind {
    /// Ami Pro 3.1 `.SAM`.
    Sam,
    /// This project's lossless `.amipro`.
    Native,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum Align {
    Left,
    Right,
    Centre,
    Justify,
}

/// Character formatting that differs from the paragraph style's, over
/// `[start, end)` in UTF-16 code units. `None` means "the style's".
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct Span {
    pub start: u32,
    pub end: u32,
    pub bold: Option<bool>,
    pub italic: Option<bool>,
    pub underline: Option<bool>,
    pub strikethrough: Option<bool>,
    pub size_points: Option<f32>,
    pub family: Option<String>,
    /// `0xRRGGBB`.
    pub colour: Option<u32>,
}

/// One paragraph with the paragraph style's own look already resolved.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct Paragraph {
    /// U+FFFC marks an object (picture, frame anchor, ...) the phone only shows as a placeholder.
    pub text: String,
    pub style_name: String,
    pub family: String,
    pub size_points: f32,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub align: Align,
    pub spans: Vec<Span>,
    /// Indents in twips (1/1440 inch), the paragraph's own over its style's.
    pub indent_left: i32,
    /// Added to `indent_left` on the first line (negative hangs).
    pub indent_first: i32,
    pub indent_right: i32,
    /// Line height as a multiple of single spacing (1.0, 1.5, 2.0, or what a custom height comes to).
    pub line_spacing_factor: f32,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct OpenReport {
    pub kind: FileKind,
    pub paragraphs: u32,
    /// What the core kept but the phone cannot show (sections, controls, warnings).
    pub notes: Vec<String>,
}

/// A character attribute the phone can switch on or off.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum Attr {
    Bold,
    Italic,
    Underline,
    Strikethrough,
}

impl Attr {
    fn core(self) -> CharacterAttribute {
        match self {
            Self::Bold => CharacterAttribute::Bold,
            Self::Italic => CharacterAttribute::Italic,
            Self::Underline => CharacterAttribute::Underline,
            Self::Strikethrough => CharacterAttribute::Strikethrough,
        }
    }
}

/// What the formatting buttons show for a selection (or the character before a caret).
/// An attribute is "on" only when it is on for every character of the selection.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct FormatState {
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub strikethrough: bool,
    /// Of the first character when sizes differ.
    pub size_points: f32,
    pub style_id: u64,
    pub style_name: String,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct StyleInfo {
    pub id: u64,
    pub name: String,
}

struct State {
    editor: Editor,
    edited: bool,
}

/// An open document.
#[derive(uniffi::Object)]
pub struct AmiDoc {
    state: Mutex<State>,
    kind: FileKind,
    password: Option<String>,
    report: OpenReport,
}

/// Opens `.SAM` or native `.amipro` bytes. `name` becomes the title of a SAM.
#[uniffi::export]
pub fn open_document(
    bytes: Vec<u8>,
    name: String,
    password: Option<String>,
) -> Result<Arc<AmiDoc>, AmiError> {
    if bytes.starts_with(NATIVE_MAGIC.as_bytes()) {
        let text = String::from_utf8(bytes).map_err(|_| failed("This file is not valid text"))?;
        let document = Document::decode_native(&text).map_err(failed)?;
        let paragraphs = document.main_flow().len() as u32;
        return Ok(Arc::new(AmiDoc::new(
            document,
            FileKind::Native,
            None,
            OpenReport {
                kind: FileKind::Native,
                paragraphs,
                notes: Vec::new(),
            },
        )));
    }
    let mut sam = bytes;
    let mut kept_password = None;
    if protect::is_protected(&sam) {
        let Some(password) = password else {
            return Err(AmiError::PasswordRequired);
        };
        sam = protect::unprotect(&sam, &password).map_err(|_| AmiError::WrongPassword)?;
        kept_password = Some(password);
    }
    let import = import_sam(&sam, None, name).map_err(|error| match error {
        ImportError::PasswordProtected => AmiError::PasswordRequired,
        ImportError::WrongPassword => AmiError::WrongPassword,
        other => failed(other),
    })?;
    // One plain sentence, and only when something is really there: nearly every SAM has layout sections,
    // so the section names are no sign of frames or pictures.
    let mut notes = Vec::new();
    let has_objects = import
        .document
        .main_flow()
        .paragraphs
        .iter()
        .any(|paragraph| !paragraph.objects.is_empty());
    let has_frames = import.document.frames().any(|frame| {
        !matches!(
            frame.placement,
            amipro_document::Placement::Header { .. } | amipro_document::Placement::Footer { .. }
        )
    });
    if has_objects || has_frames {
        notes.push(
            "This document has frames, tables or pictures (shown as boxes). They are kept when you save. Tap one, or use More > Pictures and tables, to look at it."
                .to_string(),
        );
    }
    let paragraphs = import.document.main_flow().len() as u32;
    Ok(Arc::new(AmiDoc::new(
        import.document,
        FileKind::Sam,
        kept_password,
        OpenReport {
            kind: FileKind::Sam,
            paragraphs,
            notes,
        },
    )))
}

/// A new, empty Ami Pro document (Body Text, Heading 1 and 2, the standard page), saved as SAM.
#[uniffi::export]
pub fn new_document(now_unix_seconds: i64) -> Arc<AmiDoc> {
    let mut document = Document::default();
    *document.info_mut() = amipro_document::DocumentInfo::new(ami_pro_time(now_unix_seconds));
    // Enter at the end of a heading starts Body Text (Ami Pro's "Next style"). The core already does this for any
    // style that has it on; its default headings do not, so a phone-made document turns it on for them.
    let body = document
        .styles()
        .values()
        .find(|style| style.name == "Body Text")
        .map(|style| style.id);
    if let Some(body) = body {
        for style in document.styles_mut().values_mut() {
            if style.name.starts_with("Heading") {
                style.breaks = amipro_document::Breaks(style.breaks.0 | amipro_document::Breaks::NEXT_STYLE);
                style.next = Some(body);
            }
        }
    }
    Arc::new(AmiDoc::new(
        document,
        FileKind::Sam,
        None,
        OpenReport {
            kind: FileKind::Sam,
            paragraphs: 1,
            notes: Vec::new(),
        },
    ))
}

impl AmiDoc {
    fn new(
        document: Document,
        kind: FileKind,
        password: Option<String>,
        report: OpenReport,
    ) -> Self {
        Self {
            state: Mutex::new(State {
                editor: Editor::new(document),
                edited: false,
            }),
            kind,
            password,
            report,
        }
    }

    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

#[uniffi::export]
impl AmiDoc {
    pub fn report(&self) -> OpenReport {
        self.report.clone()
    }

    pub fn kind(&self) -> FileKind {
        self.kind
    }

    pub fn title(&self) -> String {
        self.lock().editor.document().title.clone()
    }

    pub fn paragraph_count(&self) -> u32 {
        self.lock().editor.document().main_flow().len() as u32
    }

    pub fn paragraphs(&self) -> Vec<Paragraph> {
        let state = self.lock();
        let document = state.editor.document();
        (0..document.main_flow().len())
            .filter_map(|index| view(document, index))
            .collect()
    }

    pub fn paragraph(&self, index: u32) -> Option<Paragraph> {
        view(self.lock().editor.document(), index as usize)
    }

    /// Replaces `[start, end)` (UTF-16 offsets in `paragraph`) with `text`.
    /// A `\n` in `text` starts a new paragraph in the same style.
    pub fn replace_text(
        &self,
        paragraph: u32,
        start: u32,
        end: u32,
        text: String,
    ) -> Result<(), AmiError> {
        let mut state = self.lock();
        let (from, to) = {
            let document = state.editor.document();
            let source = document
                .main_flow()
                .paragraph(paragraph as usize)
                .ok_or_else(|| failed("No such paragraph"))?;
            let from = byte_of(&source.text, start).ok_or_else(|| failed("Start is outside the paragraph"))?;
            let to = byte_of(&source.text, end).ok_or_else(|| failed("End is outside the paragraph"))?;
            // Objects (U+FFFC) are shown as a box and cannot be edited here: deleting or overwriting one would
            // silently lose a picture or frame. Inserting beside one is fine.
            if from < to && source.text.get(from..to).is_some_and(|replaced| replaced.contains(OBJECT_CHAR)) {
                return Err(AmiError::ProtectedObject);
            }
            (from, to)
        };
        let at = |byte| Cursor {
            paragraph: paragraph as usize,
            byte,
        };
        state
            .editor
            .replace_document_range(at(from), at(to), text)
            .map_err(failed)?;
        state.edited = true;
        Ok(())
    }

    /// Backspace at the start of `paragraph`: joins it to the one before. `false` when it cannot.
    pub fn join_with_previous(&self, paragraph: u32) -> Result<bool, AmiError> {
        let mut state = self.lock();
        state
            .editor
            .set_cursor(Cursor {
                paragraph: paragraph as usize,
                byte: 0,
            })
            .map_err(failed)?;
        let joined = state.editor.join_with_previous().map_err(failed)?;
        state.edited |= joined;
        Ok(joined)
    }

    /// The styles a paragraph can be given, in the document's order.
    pub fn styles(&self) -> Vec<StyleInfo> {
        self.lock()
            .editor
            .document()
            .styles()
            .values()
            .filter(|style| style.origin != StyleOrigin::Missing)
            .map(|style| StyleInfo {
                id: style.id.0,
                name: style.name.clone(),
            })
            .collect()
    }

    /// The button states for `[start, end)` (UTF-16) in `paragraph`; a caret (`start == end`) reads the character before it.
    pub fn format_state(&self, paragraph: u32, start: u32, end: u32) -> Option<FormatState> {
        let state = self.lock();
        let document = state.editor.document();
        let source = document.main_flow().paragraph(paragraph as usize)?;
        let style = document.styles().get(&source.style)?;
        let from = byte_of(&source.text, start.min(end))?;
        let to = byte_of(&source.text, start.max(end))?;
        // The characters to look at: every character start where the direct format can change.
        let mut points: Vec<usize> = Vec::new();
        if from == to {
            // The character before the caret, skipping objects (their own format is not the text's).
            if let Some((before, _)) = source
                .text
                .get(..from)
                .and_then(|head| head.char_indices().rev().find(|&(_, ch)| ch != OBJECT_CHAR))
            {
                points.push(before);
            }
        } else {
            points.push(from);
            for run in &source.runs {
                for edge in [run.start, run.end] {
                    if edge > from && edge < to && source.text.is_char_boundary(edge) {
                        points.push(edge);
                    }
                }
            }
        }
        points.retain(|&byte| source.text[byte..].chars().next() != Some(OBJECT_CHAR));
        let formats: Vec<&CharacterFormat> = if points.is_empty() {
            vec![&source.end_format]
        } else {
            points.iter().map(|&byte| source.format_at(byte)).collect()
        };
        let all = |attribute: CharacterAttribute| formats.iter().all(|format| attribute.is_on(format, style));
        Some(FormatState {
            bold: all(CharacterAttribute::Bold),
            italic: all(CharacterAttribute::Italic),
            underline: all(CharacterAttribute::Underline),
            strikethrough: all(CharacterAttribute::Strikethrough),
            size_points: formats[0].font_size_points.unwrap_or(style.font_size_points),
            style_id: style.id.0,
            style_name: style.name.clone(),
        })
    }

    /// Switches `attr` on or off over `[start, end)` (UTF-16) in `paragraph`, by Ami Pro's rules for a style.
    pub fn set_attribute(&self, paragraph: u32, start: u32, end: u32, attr: Attr, on: bool) -> Result<(), AmiError> {
        let mut state = self.lock();
        let (from, to) = range_in(&state.editor, paragraph, start, end)?;
        let command = Command::SetCharacterAttribute {
            start: position(&state.editor, paragraph, from),
            end: position(&state.editor, paragraph, to),
            attribute: attr.core(),
            on,
        };
        state.editor.execute(&command).map_err(failed)?;
        state.edited = true;
        Ok(())
    }

    /// Sets the point size over `[start, end)` (UTF-16) in `paragraph`; face and colour are kept.
    pub fn set_size(&self, paragraph: u32, start: u32, end: u32, size_points: f32) -> Result<(), AmiError> {
        let mut state = self.lock();
        let (from, to) = range_in(&state.editor, paragraph, start, end)?;
        let command = Command::SetFontParts {
            start: position(&state.editor, paragraph, from),
            end: position(&state.editor, paragraph, to),
            family: None,
            size: Some(size_points.clamp(4.0, 144.0)),
            colour: None,
        };
        state.editor.execute(&command).map_err(failed)?;
        state.edited = true;
        Ok(())
    }

    /// Gives `paragraph` the style `style_id` (one of [`AmiDoc::styles`]).
    pub fn set_paragraph_style(&self, paragraph: u32, style_id: u64) -> Result<(), AmiError> {
        let mut state = self.lock();
        state
            .editor
            .set_cursor(Cursor {
                paragraph: paragraph as usize,
                byte: 0,
            })
            .map_err(failed)?;
        state.editor.apply_style(StyleId(style_id)).map_err(failed)?;
        state.edited = true;
        Ok(())
    }

    pub fn undo(&self) -> Result<bool, AmiError> {
        self.lock().editor.undo().map_err(failed)
    }

    pub fn redo(&self) -> Result<bool, AmiError> {
        self.lock().editor.redo().map_err(failed)
    }

    pub fn is_edited(&self) -> bool {
        self.lock().edited
    }

    /// What saving as SAM would lose, for a warning before writing.
    pub fn sam_losses(&self, now_unix_seconds: i64) -> Result<Vec<String>, AmiError> {
        let state = self.lock();
        let options = SamOptions::at(ami_pro_time(now_unix_seconds));
        let losses = sam_losses(state.editor.document(), &options).map_err(failed)?;
        Ok(losses.iter().map(|loss| loss.message()).collect())
    }

    /// The document as bytes in the format it was opened in (a protected SAM stays protected).
    pub fn save(&self, now_unix_seconds: i64) -> Result<Vec<u8>, AmiError> {
        match self.kind {
            FileKind::Sam => self.save_sam(now_unix_seconds),
            FileKind::Native => {
                let state = self.lock();
                state
                    .editor
                    .document()
                    .encode_native()
                    .map(String::into_bytes)
                    .map_err(failed)
            }
        }
    }

    pub fn save_sam(&self, now_unix_seconds: i64) -> Result<Vec<u8>, AmiError> {
        let state = self.lock();
        let options = SamOptions::at(ami_pro_time(now_unix_seconds));
        let bytes = sam_document(state.editor.document(), &options);
        Ok(match &self.password {
            Some(password) => protect::protect(&bytes, password),
            None => bytes,
        })
    }
}

fn position(editor: &Editor, paragraph: u32, byte: usize) -> TextPosition {
    TextPosition {
        flow: editor.document().main_flow_id(),
        paragraph: paragraph as usize,
        byte,
    }
}

/// A non-empty UTF-16 range of `paragraph` as byte offsets.
fn range_in(editor: &Editor, paragraph: u32, start: u32, end: u32) -> Result<(usize, usize), AmiError> {
    let source = editor
        .document()
        .main_flow()
        .paragraph(paragraph as usize)
        .ok_or_else(|| failed("No such paragraph"))?;
    let from = byte_of(&source.text, start.min(end)).ok_or_else(|| failed("Start is outside the paragraph"))?;
    let to = byte_of(&source.text, start.max(end)).ok_or_else(|| failed("End is outside the paragraph"))?;
    if from == to {
        return Err(failed("Nothing is selected"));
    }
    Ok((from, to))
}

fn ami_pro_time(unix_seconds: i64) -> u32 {
    amipro_document::WallClock::from_unix(unix_seconds).to_ami_pro()
}

/// The UTF-16 offset of byte `byte` in `text`.
fn utf16_of(text: &str, byte: usize) -> u32 {
    text.get(..byte)
        .map_or_else(|| text.encode_utf16().count(), |head| head.encode_utf16().count()) as u32
}

/// The byte offset of UTF-16 offset `unit` in `text`; `None` when it is past the end or inside a pair.
fn byte_of(text: &str, unit: u32) -> Option<usize> {
    let mut seen = 0u32;
    for (byte, ch) in text.char_indices() {
        if seen == unit {
            return Some(byte);
        }
        seen += ch.len_utf16() as u32;
        if seen > unit {
            return None;
        }
    }
    (seen == unit).then_some(text.len())
}

fn view(document: &Document, index: usize) -> Option<Paragraph> {
    view_in(document, document.main_flow(), index)
}

fn align_of(alignment: Alignment) -> Align {
    match alignment {
        Alignment::Left => Align::Left,
        Alignment::Right => Align::Right,
        Alignment::Centre => Align::Centre,
        Alignment::Justify => Align::Justify,
    }
}

fn view_in(document: &Document, flow: &amipro_document::Flow, index: usize) -> Option<Paragraph> {
    let source = flow.paragraph(index)?;
    let style = document.styles().get(&source.style);
    let attributes = style.map(|style| style.attributes);
    let spans = source
        .runs
        .iter()
        .filter(|run| run.format != CharacterFormat::PLAIN)
        .map(|run| span(&source.text, run.start, run.end, &run.format))
        .collect();
    // The paragraph's own alignment, indents and spacing over its style's.
    let alignment = source.format.alignment.or(style.map(|style| style.alignment)).unwrap_or_default();
    let indents = source.format.indents.or(style.map(|style| style.indents)).unwrap_or_default();
    let line = source.format.line_spacing.or(style.map(|style| style.spacing.line));
    Some(Paragraph {
        text: source.text.clone(),
        style_name: style.map(|style| style.name.clone()).unwrap_or_default(),
        family: style.map(|style| style.font_family.clone()).unwrap_or_default(),
        size_points: style.map_or(12.0, |style| style.font_size_points),
        bold: style.is_some_and(|style| style.bold),
        italic: style.is_some_and(|style| style.italic),
        underline: attributes.is_some_and(|attributes| attributes.underline),
        align: align_of(alignment),
        spans,
        indent_left: indents.all,
        indent_first: indents.first,
        indent_right: indents.right,
        line_spacing_factor: match line {
            None | Some(LineSpacing::Single) => 1.0,
            Some(LineSpacing::OneAndAHalf(_)) => 1.5,
            Some(LineSpacing::Double(_)) => 2.0,
            Some(LineSpacing::Custom(height)) => {
                let size = style.map_or(12.0, |style| style.font_size_points).max(1.0);
                (height as f32 / 20.0 / (size * 1.2)).clamp(0.5, 6.0)
            }
        },
    })
}

fn span(text: &str, start: usize, end: usize, format: &CharacterFormat) -> Span {
    Span {
        start: utf16_of(text, start),
        end: utf16_of(text, end),
        bold: format.bold,
        italic: format.italic,
        underline: format.underline,
        strikethrough: format.strikethrough,
        size_points: format.font_size_points,
        family: format.font_family.clone(),
        colour: format.colour.map(|bgr| {
            ((bgr & 0xFF) << 16) | (bgr & 0xFF00) | ((bgr >> 16) & 0xFF)
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::extras::LineSpacingChoice;

    const SAMPLE: &[u8] = include_bytes!("../tests/fixtures/synthetic-pages-and-frames.sam");

    fn open_sample() -> Arc<AmiDoc> {
        open_document(SAMPLE.to_vec(), "sample".into(), None).expect("sample opens")
    }

    #[test]
    fn sample_opens_with_text() {
        let doc = open_sample();
        assert_eq!(doc.kind(), FileKind::Sam);
        let paragraphs = doc.paragraphs();
        assert!(!paragraphs.is_empty());
        assert!(paragraphs.iter().any(|p| !p.text.trim().is_empty()));
        assert!(paragraphs.iter().all(|p| !p.style_name.is_empty()));
    }

    #[test]
    fn note_only_when_frames_or_objects_exist() {
        assert_eq!(open_sample().report().notes.len(), 1);
        let plain = b"[ver]\r\n\t3\r\n[sty]\r\n\tStandard\r\n[edoc]\r\n>\r\n";
        if let Ok(doc) = open_document(plain.to_vec(), "plain".into(), None) {
            assert!(doc.report().notes.is_empty());
        }
    }

    #[test]
    fn save_without_edits_reads_back_equal() {
        let doc = open_sample();
        let saved = doc.save_sam(1_800_000_000).unwrap();
        let again = open_document(saved, "sample".into(), None).unwrap();
        assert_eq!(doc.paragraphs(), again.paragraphs());
    }

    #[test]
    fn edit_survives_save_and_undo_works() {
        let doc = open_sample();
        let first_text = doc.paragraph(0).unwrap().text;
        doc.replace_text(0, 0, 0, "Hello é😀 ".into()).unwrap();
        assert!(doc.is_edited());
        let edited = doc.paragraph(0).unwrap().text;
        assert!(edited.starts_with("Hello é😀 "));
        let again = open_document(doc.save_sam(1_800_000_000).unwrap(), "x".into(), None).unwrap();
        assert!(again.paragraph(0).unwrap().text.starts_with("Hello é"));
        assert!(doc.undo().unwrap());
        assert_eq!(doc.paragraph(0).unwrap().text, first_text);
    }

    #[test]
    fn enter_splits_and_backspace_joins() {
        let doc = open_sample();
        let before = doc.paragraph_count();
        let text = doc.paragraph(0).unwrap().text;
        let units = text.encode_utf16().count() as u32;
        doc.replace_text(0, units.min(2), units.min(2), "\n".into()).unwrap();
        assert_eq!(doc.paragraph_count(), before + 1);
        assert_eq!(doc.paragraph(0).unwrap().text.encode_utf16().count() as u32, units.min(2));
        assert!(doc.join_with_previous(1).unwrap());
        assert_eq!(doc.paragraph_count(), before);
        assert_eq!(doc.paragraph(0).unwrap().text, text);
        assert!(!doc.join_with_previous(0).unwrap());
    }

    fn reopened(doc: &AmiDoc) -> Arc<AmiDoc> {
        open_document(doc.save_sam(1_800_000_000).unwrap(), "again".into(), None).unwrap()
    }

    /// The paragraph of the sample whose text is "Footer follows." plus an object (index found by text).
    fn plain_index(doc: &AmiDoc) -> u32 {
        doc.paragraphs().iter().position(|p| p.text.starts_with("Footer")).unwrap() as u32
    }

    #[test]
    fn bold_toggles_over_a_range_and_survives_save() {
        let doc = open_sample();
        let i = plain_index(&doc);
        let before = doc.format_state(i, 0, 6).unwrap();
        assert!(!before.bold);
        doc.set_attribute(i, 0, 6, Attr::Bold, true).unwrap();
        assert!(doc.format_state(i, 0, 6).unwrap().bold);
        assert!(!doc.format_state(i, 0, 9).unwrap().bold, "bold only where every character is bold");
        assert!(doc.format_state(i, 3, 3).unwrap().bold, "a caret reads the character before it");
        // The Linux core writes it and reads it back the same.
        let again = reopened(&doc);
        assert!(again.format_state(i, 0, 6).unwrap().bold);
        assert_eq!(again.paragraph(i).unwrap().spans, doc.paragraph(i).unwrap().spans);
        doc.set_attribute(i, 0, 6, Attr::Bold, false).unwrap();
        assert!(!doc.format_state(i, 0, 6).unwrap().bold);
        assert!(doc.paragraph(i).unwrap().spans.iter().all(|s| s.bold != Some(true)));
    }

    #[test]
    fn italic_underline_strike_and_undo() {
        let doc = open_sample();
        let i = plain_index(&doc);
        for attr in [Attr::Italic, Attr::Underline, Attr::Strikethrough] {
            doc.set_attribute(i, 1, 4, attr, true).unwrap();
        }
        let state = doc.format_state(i, 1, 4).unwrap();
        assert!(state.italic && state.underline && state.strikethrough);
        let again = reopened(&doc).format_state(i, 1, 4).unwrap();
        assert!(again.italic && again.underline && again.strikethrough);
        while doc.undo().unwrap() {}
        let state = doc.format_state(i, 1, 4).unwrap();
        assert!(!state.italic && !state.underline && !state.strikethrough);
    }

    #[test]
    fn size_and_style_changes_survive_save() {
        let doc = open_sample();
        let i = plain_index(&doc);
        doc.set_size(i, 0, 6, 20.0).unwrap();
        assert_eq!(doc.format_state(i, 0, 6).unwrap().size_points, 20.0);
        assert_eq!(reopened(&doc).format_state(i, 0, 6).unwrap().size_points, 20.0);
        let styles = doc.styles();
        assert!(styles.len() >= 2, "{styles:?}");
        let other = styles.iter().find(|s| s.id != doc.format_state(i, 0, 0).unwrap().style_id).unwrap();
        doc.set_paragraph_style(i, other.id).unwrap();
        assert_eq!(doc.format_state(i, 0, 0).unwrap().style_name, other.name);
        assert_eq!(reopened(&doc).paragraph(i).unwrap().style_name, other.name);
        assert!(doc.set_attribute(i, 2, 2, Attr::Bold, true).is_err(), "an empty range is refused");
    }

    /// The Linux core's loss check (the same one Save As uses there) reports bold switched off inside a
    /// style that is bold: SAM cannot hold it. Every other phone formatting is written exactly.
    #[test]
    fn only_bold_off_over_a_bold_style_is_reported_as_a_loss() {
        let at = 1_800_000_000;
        let doc = open_sample();
        doc.set_attribute(0, 0, 5, Attr::Italic, true).unwrap();
        doc.set_attribute(0, 0, 5, Attr::Underline, true).unwrap();
        doc.set_attribute(0, 0, 5, Attr::Strikethrough, true).unwrap();
        doc.set_size(0, 0, 5, 18.0).unwrap();
        assert!(doc.sam_losses(at).unwrap().is_empty());
        let heading = doc.styles().into_iter().find(|s| s.name == "Heading").unwrap();
        let i = plain_index(&doc);
        doc.set_paragraph_style(i, heading.id).unwrap();
        assert!(doc.sam_losses(at).unwrap().is_empty());
        doc.set_attribute(i, 0, 3, Attr::Bold, false).unwrap();
        assert_eq!(doc.sam_losses(at).unwrap(), vec!["Character formatting in 1 paragraph".to_string()]);
        doc.set_attribute(i, 0, 3, Attr::Bold, true).unwrap();
        assert!(doc.sam_losses(at).unwrap().is_empty());
    }

    #[test]
    fn a_new_document_saves_as_sam_and_reads_back() {
        let at = 1_800_000_000;
        let doc = new_document(at);
        assert_eq!(doc.kind(), FileKind::Sam);
        assert_eq!(doc.paragraph_count(), 1);
        assert!(!doc.is_edited());
        let names: Vec<String> = doc.styles().into_iter().map(|s| s.name).collect();
        assert_eq!(names, ["Body Text", "Heading 1", "Heading 2"]);
        doc.replace_text(0, 0, 0, "Hello\nWorld é".into()).unwrap();
        doc.set_attribute(0, 0, 5, Attr::Bold, true).unwrap();
        let heading = doc.styles().into_iter().find(|s| s.name == "Heading 1").unwrap();
        doc.set_paragraph_style(1, heading.id).unwrap();
        assert!(doc.sam_losses(at).unwrap().is_empty());
        let again = reopened(&doc);
        assert_eq!(again.paragraphs(), doc.paragraphs());
        assert_eq!(again.paragraph(1).unwrap().style_name, "Heading 1");
        assert!(again.format_state(0, 0, 5).unwrap().bold);
    }

    #[test]
    fn enter_at_the_end_of_a_heading_starts_body_text_in_a_new_document() {
        let at = 1_800_000_000;
        let doc = new_document(at);
        let heading = doc.styles().into_iter().find(|s| s.name == "Heading 1").unwrap();
        doc.set_paragraph_style(0, heading.id).unwrap();
        doc.replace_text(0, 0, 0, "Title".into()).unwrap();
        // At the end: Body Text. In the middle: the heading continues (as in Ami Pro).
        doc.replace_text(0, 5, 5, "\n".into()).unwrap();
        assert_eq!(doc.paragraph(1).unwrap().style_name, "Body Text");
        doc.replace_text(0, 2, 2, "\n".into()).unwrap();
        assert_eq!(doc.paragraph(1).unwrap().style_name, "Heading 1");
        // The setting is written to the file and read back (Linux loss check lists nothing).
        assert!(doc.sam_losses(at).unwrap().is_empty());
        let again = reopened(&doc);
        let end = again.paragraph(0).unwrap().text.encode_utf16().count() as u32;
        again.replace_text(0, end, end, "\n".into()).unwrap();
        assert_eq!(again.paragraph(1).unwrap().style_name, "Body Text", "next style survives save and reopen");
        // An opened document keeps its own styles: the sample's Heading has no next style.
        let sample = open_sample();
        let before = sample.paragraph(0).unwrap().style_name;
        let units = sample.paragraph(0).unwrap().text.encode_utf16().count() as u32;
        assert_eq!(sample.paragraph(0).unwrap().text.chars().last(), Some(OBJECT_CHAR));
        sample.replace_text(0, units, units, "\n".into()).unwrap();
        assert_eq!(sample.paragraph(1).unwrap().style_name, before);
    }

    #[test]
    fn an_empty_new_document_saves_and_reopens() {
        let doc = new_document(1_800_000_000);
        let again = reopened(&doc);
        assert_eq!(again.paragraph_count(), 1);
        assert_eq!(again.paragraph(0).unwrap().text, "");
    }

    #[test]
    fn objects_cannot_be_deleted_or_overwritten_but_text_beside_them_can() {
        let doc = open_sample();
        let i = plain_index(&doc);
        let text = doc.paragraph(i).unwrap().text;
        let units = text.encode_utf16().count() as u32;
        assert!(text.ends_with(OBJECT_CHAR), "the sample's paragraph ends with an object");
        let objects_before = doc.paragraphs().iter().map(|p| p.text.matches(OBJECT_CHAR).count()).sum::<usize>();
        // Backspace over it, overwriting it, and a selection that merely covers it are all refused ...
        for (start, end, with) in [(units - 1, units, ""), (units - 1, units, "x"), (units - 3, units, "abc"), (0, units, "")] {
            assert!(
                matches!(doc.replace_text(i, start, end, with.into()), Err(AmiError::ProtectedObject)),
                "{start}..{end} -> {with:?}"
            );
        }
        assert!(!doc.is_edited(), "a refused edit changes nothing");
        assert_eq!(doc.paragraph(i).unwrap().text, text);
        // ... while typing before it, beside it, deleting other text, and splitting the paragraph are not.
        doc.replace_text(i, units - 1, units - 1, "ab".into()).unwrap();
        doc.replace_text(i, 0, 1, "".into()).unwrap();
        doc.replace_text(i, 2, 2, "\n".into()).unwrap();
        let objects_after = doc.paragraphs().iter().map(|p| p.text.matches(OBJECT_CHAR).count()).sum::<usize>();
        assert_eq!(objects_after, objects_before, "no object was lost");
        // Backspace at a paragraph start (join) keeps the objects of both paragraphs.
        assert!(doc.join_with_previous(i + 1).unwrap());
        let objects_joined = doc.paragraphs().iter().map(|p| p.text.matches(OBJECT_CHAR).count()).sum::<usize>();
        assert_eq!(objects_joined, objects_before, "a join lost an object");
        // Saving and reading back keeps every object.
        let again = reopened(&doc);
        let objects_saved = again.paragraphs().iter().map(|p| p.text.matches(OBJECT_CHAR).count()).sum::<usize>();
        assert_eq!(objects_saved, objects_before);
    }

    /// Opt-in audit over a folder of real SAMs (read only; prints counts, never names or text):
    /// `AMIPRO_REF_DIR=... cargo test --release -p amipro-mobile reference_audit -- --ignored --nocapture`.
    #[test]
    #[ignore = "reads the owner's reference samples; set AMIPRO_REF_DIR"]
    fn reference_audit() {
        let Some(dir) = std::env::var_os("AMIPRO_REF_DIR") else { return };
        let mut files = Vec::new();
        let mut stack = vec![std::path::PathBuf::from(dir)];
        while let Some(path) = stack.pop() {
            for entry in std::fs::read_dir(&path).unwrap().flatten() {
                let p = entry.path();
                if p.is_dir() { stack.push(p) } else if p.extension().is_some_and(|e| e.eq_ignore_ascii_case("sam")) { files.push(p) }
            }
        }
        let (mut opened, mut protected, mut with_objects, mut refused, mut allowed_ok, mut mismatched) = (0, 0, 0, 0, 0, 0);
        for path in &files {
            let bytes = std::fs::read(path).unwrap();
            let doc = match open_document(bytes, "ref".into(), None) {
                Ok(d) => d,
                Err(AmiError::PasswordRequired) => { protected += 1; continue }
                Err(_) => continue,
            };
            opened += 1;
            let before: usize = doc.paragraphs().iter().map(|p| p.text.matches(OBJECT_CHAR).count()).sum();
            if before == 0 { continue }
            with_objects += 1;
            for (i, p) in doc.paragraphs().iter().enumerate() {
                let Some(at) = p.text.find(OBJECT_CHAR) else { continue };
                let u = p.text[..at].encode_utf16().count() as u32;
                // Removing or replacing the object must be refused, and change nothing.
                match doc.replace_text(i as u32, u, u + 1, String::new()) {
                    Err(AmiError::ProtectedObject) => refused += 1,
                    other => { mismatched += 1; eprintln!("not refused: {other:?}") }
                }
                // Typing beside it must work.
                if doc.replace_text(i as u32, u, u, "z".into()).is_ok() { allowed_ok += 1 } else { mismatched += 1 }
                break;
            }
            let after: usize = doc.paragraphs().iter().map(|p| p.text.matches(OBJECT_CHAR).count()).sum();
            if after != before { mismatched += 1 }
            let saved = reopened(&doc);
            let kept: usize = saved.paragraphs().iter().map(|p| p.text.matches(OBJECT_CHAR).count()).sum();
            if kept != before { mismatched += 1 }
        }
        eprintln!("AUDIT files={} opened={opened} password_protected={protected} with_objects={with_objects} refused={refused} typing_beside_ok={allowed_ok} mismatches={mismatched}", files.len());
        assert_eq!(mismatched, 0);
    }

    /// Opt-in audit for the Stack link preview: how many real SAMs hold tables, text frames, pictures or
    /// drawings, and how much of their text lives inside them (read only; counts only, never names or text):
    /// `AMIPRO_REF_DIR=... cargo test --release -p amipro-mobile reference_object_audit -- --ignored --nocapture`.
    #[test]
    #[ignore = "reads the owner's reference samples; set AMIPRO_REF_DIR"]
    fn reference_object_audit() {
        let Some(dir) = std::env::var_os("AMIPRO_REF_DIR") else { return };
        let mut files = Vec::new();
        let mut stack = vec![std::path::PathBuf::from(dir)];
        while let Some(path) = stack.pop() {
            for entry in std::fs::read_dir(&path).unwrap().flatten() {
                let p = entry.path();
                if p.is_dir() { stack.push(p) } else if p.extension().is_some_and(|e| e.eq_ignore_ascii_case("sam")) { files.push(p) }
            }
        }
        let (mut opened, mut protected, mut plain, mut tables, mut frames, mut pictures, mut other) = (0, 0, 0, 0, 0, 0, 0);
        let (mut table_heavy, mut text_in_objects_docs, mut table_unreadable, mut empty_body) = (0, 0, 0, 0);
        let (mut body_total, mut table_total, mut frame_total) = (0usize, 0usize, 0usize);
        for path in &files {
            let bytes = std::fs::read(path).unwrap();
            let doc = match open_document(bytes, "ref".into(), None) {
                Ok(d) => d,
                Err(AmiError::PasswordRequired) => { protected += 1; continue }
                Err(_) => continue,
            };
            opened += 1;
            let body: usize = doc.paragraphs().iter().map(|p| p.text.chars().filter(|c| *c != OBJECT_CHAR && !c.is_whitespace()).count()).sum();
            if body == 0 { empty_body += 1 }
            let (mut t, mut f) = (0usize, 0usize);
            let (mut has_table, mut has_frame, mut has_picture, mut has_other) = (false, false, false, false);
            for o in doc.objects() {
                match o.kind {
                    crate::extras::ObjectKind::Table => {
                        has_table = true;
                        match doc.table_text(o.paragraph, o.offset) {
                            Some(view) => t += view.cells.iter().map(|c| c.chars().filter(|c| !c.is_whitespace()).count()).sum::<usize>(),
                            None => table_unreadable += 1,
                        }
                    }
                    crate::extras::ObjectKind::TextFrame => {
                        has_frame = true;
                        f += doc.frame_text(o.paragraph, o.offset).map(|x| x.chars().filter(|c| !c.is_whitespace()).count()).unwrap_or(0);
                    }
                    crate::extras::ObjectKind::Picture => has_picture = true,
                    crate::extras::ObjectKind::Other => has_other = true,
                    _ => {}
                }
            }
            if !(has_table || has_frame || has_picture || has_other) { plain += 1 }
            tables += has_table as u32; frames += has_frame as u32; pictures += has_picture as u32; other += has_other as u32;
            if t + f > 0 { text_in_objects_docs += 1 }
            if t > body { table_heavy += 1 }
            body_total += body; table_total += t; frame_total += f;
        }
        eprintln!("OBJECT_AUDIT files={} opened={opened} password_protected={protected} plain_text_only={plain} with_table={tables} with_text_frame={frames} with_picture={pictures} with_drawing_or_other={other}", files.len());
        eprintln!("OBJECT_AUDIT docs_with_text_inside_tables_or_frames={text_in_objects_docs} docs_where_table_text_exceeds_body={table_heavy} tables_unreadable={table_unreadable} docs_with_empty_body={empty_body}");
        eprintln!("OBJECT_AUDIT nonspace_chars body={body_total} in_tables={table_total} in_text_frames={frame_total}");
    }

    #[test]
    fn plain_text_includes_frame_and_table_text() {
        let doc = open_sample();
        let text = doc.plain_text();
        assert!(!text.contains(OBJECT_CHAR));
        for o in doc.objects() {
            let inner = match o.kind {
                crate::extras::ObjectKind::TextFrame => doc.frame_text(o.paragraph, o.offset),
                crate::extras::ObjectKind::Table => doc.table_text(o.paragraph, o.offset).map(|t| t.cells.join("\n")),
                _ => None,
            };
            if let Some(inner) = inner { assert!(text.contains(inner.trim())); }
        }
        assert!(doc.objects().iter().any(|o| o.kind == crate::extras::ObjectKind::TextFrame), "fixture should hold a frame");
    }

    #[test]
    fn medicine_list_words_inside_the_table_are_searchable() {
        let doc = open_document(include_bytes!("../tests/fixtures/synthetic-medicine-list.sam").to_vec(), "meds".into(), None).expect("opens");
        let text = doc.plain_text();
        assert!(text.contains("Zorbaprilol 10 mg morning") && text.contains("Quenfamet") && text.contains("Dr Example Clinic"));
        assert!(!doc.paragraphs().iter().any(|p| p.text.contains("Quenfamet")), "the word lives only in the table");
    }

    #[test]
    fn the_format_bar_ignores_object_characters() {
        let doc = open_sample();
        let i = plain_index(&doc);
        let units = doc.paragraph(i).unwrap().text.encode_utf16().count() as u32;
        let after_object = doc.format_state(i, units, units).unwrap();
        let before_object = doc.format_state(i, units - 2, units - 2).unwrap();
        assert_eq!(after_object.size_points, before_object.size_points);
        assert_eq!(after_object.bold, before_object.bold);
    }

    #[test]
    fn utf16_offsets_round_trip() {
        let text = "a😀b";
        assert_eq!(utf16_of(text, 1), 1);
        assert_eq!(utf16_of(text, 5), 3);
        assert_eq!(byte_of(text, 3), Some(5));
        assert_eq!(byte_of(text, 2), None);
        assert_eq!(byte_of(text, 4), Some(6));
        assert_eq!(byte_of(text, 5), None);
    }

    #[test]
    fn native_round_trip() {
        let sam = open_sample();
        let native = {
            let state = sam.lock();
            state.editor.document().encode_native().unwrap().into_bytes()
        };
        let doc = open_document(native, "n".into(), None).unwrap();
        assert_eq!(doc.kind(), FileKind::Native);
        assert_eq!(doc.paragraphs(), sam.paragraphs());
        let saved = doc.save(0).unwrap();
        assert!(saved.starts_with(NATIVE_MAGIC.as_bytes()));
    }

    #[test]
    fn protected_sam_needs_its_password_and_stays_protected() {
        let plain = open_sample().save_sam(1_800_000_000).unwrap();
        let locked = protect::protect(&plain, "secret");
        assert!(matches!(
            open_document(locked.clone(), "p".into(), None),
            Err(AmiError::PasswordRequired)
        ));
        assert!(matches!(
            open_document(locked.clone(), "p".into(), Some("nope".into())),
            Err(AmiError::WrongPassword)
        ));
        let doc = open_document(locked, "p".into(), Some("secret".into())).unwrap();
        assert!(protect::is_protected(&doc.save_sam(1_800_000_000).unwrap()));
    }

    // ---- extras: cross-paragraph edits, layout, find, objects, page setup ----

    fn texts(doc: &AmiDoc) -> Vec<String> {
        doc.paragraphs().into_iter().map(|p| p.text).collect()
    }

    fn lines(doc: &AmiDoc, text: &str) {
        doc.replace_text(0, 0, 0, text.into()).unwrap();
    }

    #[test]
    fn a_range_across_paragraphs_is_replaced_copied_and_formatted() {
        let doc = new_document(1_800_000_000);
        lines(&doc, "alpha\nbravo\ncharlie");
        assert_eq!(doc.text_in_range(0, 2, 2, 3).unwrap(), "pha\nbravo\ncha");
        assert_eq!(doc.text_in_range(2, 3, 0, 2).unwrap(), "pha\nbravo\ncha", "either order");
        // Bold across all three paragraphs, then read back by the bar's rule.
        doc.set_attribute_between(0, 2, 2, 3, Attr::Bold, true).unwrap();
        assert!(doc.format_state_between(0, 2, 2, 3).unwrap().bold);
        assert!(!doc.format_state_between(0, 0, 2, 3).unwrap().bold, "not bold everywhere");
        let again = reopened(&doc);
        assert!(again.format_state_between(0, 2, 2, 3).unwrap().bold);
        // Delete across paragraphs joins them; typing over a cross-paragraph selection replaces it.
        doc.replace_range(0, 2, 2, 3, "XY".into()).unwrap();
        assert_eq!(texts(&doc), ["alXYrlie"]);
        assert!(doc.undo().unwrap());
        assert_eq!(texts(&doc), ["alpha", "bravo", "charlie"]);
        // A newline in the replacement splits.
        doc.replace_range(0, 5, 1, 0, "\n".into()).unwrap();
        assert_eq!(texts(&doc), ["alpha", "bravo", "charlie"]);
        doc.replace_range(0, 5, 1, 5, "!\nzed".into()).unwrap();
        assert_eq!(texts(&doc), ["alpha!", "zed", "charlie"]);
    }

    #[test]
    fn a_range_holding_an_object_is_refused() {
        let doc = open_sample();
        let i = plain_index(&doc);
        let units = doc.paragraph(i).unwrap().text.encode_utf16().count() as u32;
        assert!(matches!(doc.replace_range(0, 0, i, units, String::new()), Err(AmiError::ProtectedObject)));
        assert!(!doc.is_edited());
    }

    #[test]
    fn alignment_indents_and_line_spacing_are_shown_and_survive_save() {
        let doc = new_document(1_800_000_000);
        lines(&doc, "one\ntwo\nthree");
        doc.set_alignment(0, 1, Align::Centre).unwrap();
        eprintln!("after alignment: {:?}", doc.sam_losses(1_800_000_000).unwrap());
        doc.set_indents(1, 2, Some(720), Some(360), Some(240)).unwrap();
        eprintln!("after indents: {:?}", doc.sam_losses(1_800_000_000).unwrap());
        doc.set_line_spacing(2, 2, LineSpacingChoice::Double).unwrap();
        eprintln!("after spacing: {:?}", doc.sam_losses(1_800_000_000).unwrap());
        let check = |d: &AmiDoc| {
            let p = d.paragraphs();
            assert_eq!(p[0].align, Align::Centre);
            assert_eq!(p[1].align, Align::Centre);
            assert_eq!(p[2].align, Align::Left);
            assert_eq!((p[0].indent_left, p[0].indent_first), (0, 0));
            assert_eq!((p[1].indent_left, p[1].indent_first, p[1].indent_right), (720, 360, 240));
            assert_eq!(p[2].line_spacing_factor, 2.0);
            assert_eq!(p[0].line_spacing_factor, 1.0);
        };
        check(&doc);
        eprintln!("losses: {:?}", doc.sam_losses(1_800_000_000).unwrap());
        assert!(doc.sam_losses(1_800_000_000).unwrap().is_empty());
        check(&reopened(&doc));
        doc.set_line_spacing(2, 2, LineSpacingChoice::Single).unwrap();
        assert_eq!(doc.paragraph(2).unwrap().line_spacing_factor, 1.0);
    }

    #[test]
    fn face_and_colour_over_a_range_survive_save() {
        let doc = new_document(1_800_000_000);
        lines(&doc, "one\ntwo");
        doc.set_font_between(0, 1, 1, 2, Some("Courier New".into()), None, Some(0x336699)).unwrap();
        let check = |d: &AmiDoc| {
            for (i, range) in [(0usize, 1u32..3), (1, 0..2)] {
                let p = d.paragraph(i as u32).unwrap();
                let s = p.spans.iter().find(|s| s.start <= range.start && s.end >= range.end).expect("a span");
                assert_eq!(s.family.as_deref(), Some("Courier New"));
                assert_eq!(s.colour, Some(0x336699));
            }
        };
        check(&doc);
        check(&reopened(&doc));
        assert!(doc.fonts().iter().any(|f| f == "Courier New"));
    }

    #[test]
    fn find_and_replace_all() {
        let doc = new_document(1_800_000_000);
        lines(&doc, "The cat and THE dog\nthe end, other");
        let hits = doc.find("the".into(), false, false);
        assert_eq!(hits.len(), 4, "The, THE, the, and the 'the' inside 'other': {hits:?}");
        assert_eq!(doc.find("the".into(), true, false).len(), 2);
        assert_eq!(doc.find("the".into(), false, true).len(), 3, "whole words only");
        assert_eq!(doc.find("é😀".into(), false, false).len(), 0);
        assert_eq!(doc.replace_all("the".into(), "a".into(), false, true).unwrap(), 3);
        assert_eq!(texts(&doc), ["a cat and a dog", "a end, other"]);
        assert!(doc.undo().unwrap(), "one undo step for the whole replace");
        assert_eq!(texts(&doc), ["The cat and THE dog", "the end, other"]);
    }

    #[test]
    fn objects_are_listed_and_previewed_and_can_be_deleted() {
        let doc = open_sample();
        let objects = doc.objects();
        let count: usize = doc.paragraphs().iter().map(|p| p.text.matches(OBJECT_CHAR).count()).sum();
        assert_eq!(objects.len(), count);
        assert!(!objects.is_empty());
        eprintln!("objects: {objects:?}");
        let first = &objects[0];
        let before = doc.paragraph(first.paragraph).unwrap().text;
        doc.delete_object(first.paragraph, first.offset).unwrap();
        eprintln!("after delete: {:?} -> {:?}", before, doc.paragraph(first.paragraph).map(|p| p.text));
        assert!(doc.paragraph(first.paragraph).map_or(true, |p| p.text.matches(OBJECT_CHAR).count() < before.matches(OBJECT_CHAR).count()));
        assert_eq!(doc.objects().len(), count - 1);
        assert!(doc.undo().unwrap());
        assert_eq!(doc.objects().len(), count);
        assert!(doc.delete_object(0, 0).is_err() || doc.paragraph(0).unwrap().text.starts_with(OBJECT_CHAR));
    }

    #[test]
    fn page_setup_gives_paper_and_margins() {
        let setup = new_document(1_800_000_000).page_setup();
        assert_eq!((setup.width_twips, setup.height_twips), (12_240, 15_840));
        assert_eq!((setup.margin_left, setup.margin_top), (1_440, 1_440));
        let sample = open_sample().page_setup();
        assert!(sample.width_twips > 0 && sample.margin_left >= 0);
        eprintln!("sample setup: header {} footer {}", sample.header.len(), sample.footer.len());
    }

    /// Opt-in: lists, for every SAM under AMIPRO_SCAN_DIR, how many pictures it has and how many of them the phone can draw.
    #[test]
    #[ignore = "reads a folder given by AMIPRO_SCAN_DIR"]
    fn picture_scan() {
        let Some(dir) = std::env::var_os("AMIPRO_SCAN_DIR") else { return };
        let mut stack = vec![std::path::PathBuf::from(dir)];
        while let Some(path) = stack.pop() {
            for entry in std::fs::read_dir(&path).unwrap().flatten() {
                let p = entry.path();
                if p.is_dir() { stack.push(p); continue }
                if !p.extension().is_some_and(|e| e.eq_ignore_ascii_case("sam")) { continue }
                let Ok(doc) = open_document(std::fs::read(&p).unwrap(), "x".into(), None) else { continue };
                let pictures: Vec<_> = doc.objects().into_iter().filter(|o| o.kind == extras::ObjectKind::Picture).collect();
                if pictures.is_empty() { continue }
                let drawn = pictures.iter().filter(|o| doc.picture_image(o.paragraph, o.offset).is_some()).count();
                eprintln!("PICTURES {} total={} drawn={}", p.display(), pictures.len(), drawn);
            }
        }
    }
}
