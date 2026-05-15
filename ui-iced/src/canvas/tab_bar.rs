//! Canvas tab chrome (in-window tabs).

use crate::canvas::CanvasRegistry;
use iced::widget::{button, row, scrollable, text};
use iced::{Element, Length};

#[derive(Debug, Clone)]
pub enum CanvasChromeMessage {
    SelectTab(String),
    CloseTab(String),
}

pub fn canvas_tab_bar<'a>(
    tabs: &'a [String],
    active: Option<&str>,
    registry: &'a CanvasRegistry,
) -> Element<'a, CanvasChromeMessage> {
    let mut tab_row = row![].spacing(4).padding([4, 0]);

    for id in tabs {
        let _is_active = active == Some(id.as_str());
        let title = registry
            .get(id.as_str())
            .map(|c| c.title())
            .unwrap_or_else(|| id.clone());

        let label = if title.len() > 28 {
            format!("{}…", &title[..27])
        } else {
            title
        };

        let sel_id = id.clone();
        let tab_btn = button(text(format!("{label}")))
            .padding([6, 10])
            .on_press(CanvasChromeMessage::SelectTab(sel_id.clone()));

        let close_id = id.clone();
        let close_btn = button(text("✕"))
            .padding([6, 8])
            .on_press(CanvasChromeMessage::CloseTab(close_id));

        tab_row = tab_row.push(row![tab_btn, close_btn].spacing(2));
    }

    scrollable(tab_row)
        .direction(scrollable::Direction::Horizontal(
            scrollable::Scrollbar::default(),
        ))
        .width(Length::Fill)
        .into()
}
