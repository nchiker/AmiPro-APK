//! Phone-facing wrapper over the Ami Pro for Linux core.
//!
//! The core (`amipro-document`, `amipro-formats`, `amipro-editing`) reads and
//! writes the files and keeps whatever it cannot model. This crate only
//! translates: bytes in, paragraphs and character spans out (as UTF-16
//! offsets, which is what Kotlin strings use), edits in, bytes out.

use std::sync::{Arc, Mutex, MutexGuard};

use amipro_document::{Alignment, CharacterFormat, Document, NATIVE_MAGIC, StyleId, StyleOrigin};
use amipro_editing::{CharacterAttribute, Command, Cursor, Editor, TextPosition};
use amipro_formats::{ImportError, SamOptions, import_sam, protect, sam_document, sam_losses};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum AmiError {
    #[error("This document is protected with a password")]
    PasswordRequired,
    #[error("That password does not open this document")]
    WrongPassword,
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
            "This document has frames, tables or pictures. They are kept when you save, but are not shown here."
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
            (
                byte_of(&source.text, start).ok_or_else(|| failed("Start is outside the paragraph"))?,
                byte_of(&source.text, end).ok_or_else(|| failed("End is outside the paragraph"))?,
            )
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
            if let Some((before, _)) = source.text.get(..from).and_then(|head| head.char_indices().next_back()) {
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
    let source = document.main_flow().paragraph(index)?;
    let style = document.styles().get(&source.style);
    let attributes = style.map(|style| style.attributes);
    let spans = source
        .runs
        .iter()
        .filter(|run| run.format != CharacterFormat::PLAIN)
        .map(|run| span(&source.text, run.start, run.end, &run.format))
        .collect();
    Some(Paragraph {
        text: source.text.clone(),
        style_name: style.map(|style| style.name.clone()).unwrap_or_default(),
        family: style.map(|style| style.font_family.clone()).unwrap_or_default(),
        size_points: style.map_or(12.0, |style| style.font_size_points),
        bold: style.is_some_and(|style| style.bold),
        italic: style.is_some_and(|style| style.italic),
        underline: attributes.is_some_and(|attributes| attributes.underline),
        align: match style.map(|style| style.alignment).unwrap_or_default() {
            Alignment::Left => Align::Left,
            Alignment::Right => Align::Right,
            Alignment::Centre => Align::Centre,
            Alignment::Justify => Align::Justify,
        },
        spans,
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
}
