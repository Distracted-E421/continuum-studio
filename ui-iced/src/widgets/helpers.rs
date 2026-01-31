//! Widget helper utilities

use iced::widget::{container, text, Space};
use iced::{Alignment, Element, Length};

/// Create a section header with title
pub fn section_header<'a, Message: 'a>(title: &'a str) -> Element<'a, Message> {
    text(title)
        .size(18)
        .into()
}

/// Create a card container with padding and background
pub fn card<'a, Message: 'a>(
    content: impl Into<Element<'a, Message>>,
) -> Element<'a, Message> {
    container(content)
        .padding(16)
        .into()
}

/// Create a labeled value row
pub fn labeled_value<'a, Message: 'a>(
    label: &'a str,
    value: &'a str,
) -> Element<'a, Message> {
    iced::widget::row![
        text(label).size(12),
        Space::new().width(Length::Fill),
        text(value).size(12),
    ]
    .spacing(8)
    .align_y(Alignment::Center)
    .into()
}

/// Create a vertical spacer
pub fn vspace<'a, Message: 'a>(height: f32) -> Element<'a, Message> {
    Space::new().height(height).into()
}

/// Create a horizontal spacer
pub fn hspace<'a, Message: 'a>(width: f32) -> Element<'a, Message> {
    Space::new().width(width).into()
}
