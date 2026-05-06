//! Synapsix Canvas Protocol — SCP canvases, persistence, and Continuum tabs.

pub mod activity_stream;
pub mod canvas_ws;
pub mod dbus;
pub mod decision_tree;
pub mod deep_link;
pub mod dendrix_client;
pub mod dendrix_topology;
pub mod persistence;
pub mod scp_activity_ws;
pub mod tab_bar;
pub mod thinking_vis;
pub mod workspace;

pub use activity_stream::{
    view_activity_stream, ActivityFilters, ActivityLayoutError, ActivityStreamCanvas,
    ActivityStreamMessage, GroupBy, ViewMode,
};
pub use dbus::{
    canvas_dbus_subscription, CANVAS_DBUS_INTERFACE, CANVAS_DBUS_PATH, CANVAS_DBUS_WELL_KNOWN,
};
pub use decision_tree::{
    DecisionGraphMsg, DecisionTreeCanvas, DecisionTreeMsg, DisclosureLevel, TreeLayout, ZOOM_MAX,
    ZOOM_MIN,
};
pub use deep_link::{handle_deep_link, DeepLink, DeepLinkError};
pub use dendrix_client::{
    DendrixClient, DendrixClientError, DendrixGraphWire, DendrixNodeWire, DendrixNotification,
    DendrixRpcConn, DEFAULT_DENDRIX_HARNESS_WS,
};
pub use dendrix_topology::{DendrixTopologyCanvas, DendrixTopologyMessage};
pub use persistence::{CanvasPersistence, CanvasPersistenceError, CanvasSession};
pub use canvas_ws::{
    canvas_ws_worker, spawn_canvas_websocket, CanvasType as CanvasWsType, CanvasWsCommand,
    CanvasWsEvent, CanvasWsHandle, DEFAULT_SCP_CANVAS_WS_URL,
};
pub use scp_activity_ws::{
    scp_activity_ws_worker, spawn_scp_activity_websocket, ScpActivityWsEvent,
    DEFAULT_SCP_ACTIVITY_WS_URL,
};
pub use tab_bar::{canvas_tab_bar, CanvasChromeMessage};
pub use thinking_vis::{
    view_thinking_vis, ThinkingParser, ThinkingPreference, ThinkingViewMode, ThinkingVisCanvas,
    ThinkingVisMessage,
};
pub use workspace::{CanvasPaneMessage, CanvasRegistry, HostedCanvas, StubCanvas, StubCanvasMsg};

use crate::scp::CanvasType;
use iced::{Element, Subscription, Task};
use serde_json::Value;
use thiserror::Error;

#[derive(Debug, Clone, Error)]
pub enum CanvasError {
    #[error("{0}")]
    Deserialize(String),
}

/// SCP canvas embedded in Continuum (not iced painting [`canvas::Canvas`](iced::widget::canvas::Canvas)).
pub trait Canvas {
    type Message: Clone + std::fmt::Debug + Send + 'static;

    fn id(&self) -> &str;
    fn canvas_type(&self) -> CanvasType;
    fn title(&self) -> String;

    fn update(&mut self, message: Self::Message) -> Task<Self::Message>;
    fn view<'a>(&'a self) -> Element<'a, Self::Message>;

    fn subscription(&self) -> Subscription<Self::Message> {
        Subscription::none()
    }

    fn serialize_state(&self) -> Value;
    fn deserialize_state(&mut self, data: &Value) -> Result<(), CanvasError>;
}
