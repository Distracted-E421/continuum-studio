//! Registry and hosted canvas enum for multi-tab SCP UI.

use std::collections::HashMap;

use iced::widget::{button, column, container, scrollable, text};
use iced::{Element, Length, Size, Subscription, Task};
use serde_json::{json, Value};
use uuid::Uuid;

use crate::scp::{CanvasType, DecisionNode};

use super::activity_stream::{ActivityStreamCanvas, ActivityStreamMessage};
use super::decision_tree::{DecisionTreeCanvas, DecisionTreeMsg};
use super::dendrix_topology::{DendrixTopologyCanvas, DendrixTopologyMessage};
use super::thinking_vis::{ThinkingVisCanvas, ThinkingVisMessage};
use super::{Canvas, CanvasError};

#[derive(Debug, Clone)]
pub enum StubCanvasMsg {
    Ping,
}

#[derive(Debug, Clone)]
pub struct StubCanvas {
    id: String,
    canvas_type: CanvasType,
    title: String,
    params: Value,
    stub_ticks: u32,
}

impl StubCanvas {
    pub fn new(id: String, canvas_type: CanvasType, title: String, params: Value) -> Self {
        Self {
            id,
            canvas_type,
            title,
            params,
            stub_ticks: 0,
        }
    }
}

impl Canvas for StubCanvas {
    type Message = StubCanvasMsg;

    fn id(&self) -> &str {
        &self.id
    }

    fn canvas_type(&self) -> CanvasType {
        self.canvas_type
    }

    fn title(&self) -> String {
        self.title.clone()
    }

    fn update(&mut self, message: StubCanvasMsg) -> Task<StubCanvasMsg> {
        if matches!(message, StubCanvasMsg::Ping) {
            self.stub_ticks = self.stub_ticks.wrapping_add(1);
        }
        Task::none()
    }

    fn view<'a>(&'a self) -> Element<'a, StubCanvasMsg> {
        let header = text(format!("{} — {:?}", self.title, self.canvas_type)).size(18);
        let meta = text(format!("id={} · ticks={}", self.id, self.stub_ticks)).size(12);
        let body = text(format!(
            "{}",
            serde_json::to_string_pretty(&self.params).unwrap_or_default()
        ))
        .size(11);
        let ping = button("Stub ping")
            .on_press(StubCanvasMsg::Ping)
            .padding([8, 12]);

        column![
            header,
            meta,
            scrollable(container(body).padding(8)).height(Length::FillPortion(2)),
            ping,
        ]
        .spacing(8)
        .padding(12)
        .into()
    }

    fn serialize_state(&self) -> Value {
        json!({
            "title": self.title,
            "params": self.params,
            "stub_ticks": self.stub_ticks,
        })
    }

    fn deserialize_state(&mut self, data: &Value) -> Result<(), CanvasError> {
        if let Some(t) = data.get("title").and_then(|v| v.as_str()) {
            self.title = t.to_string();
        }
        if let Some(p) = data.get("params") {
            self.params = p.clone();
        }
        if let Some(n) = data.get("stub_ticks").and_then(|v| v.as_u64()) {
            self.stub_ticks = n as u32;
        }
        Ok(())
    }
}

#[derive(Debug, Clone)]
pub enum HostedCanvas {
    Activity(ActivityStreamCanvas),
    Thinking(ThinkingVisCanvas),
    Decision(DecisionTreeCanvas),
    Topology(DendrixTopologyCanvas),
    Stub(StubCanvas),
}

#[derive(Debug, Clone)]
pub enum CanvasPaneMessage {
    Activity(ActivityStreamMessage),
    Thinking(ThinkingVisMessage),
    Decision(DecisionTreeMsg),
    Topology(DendrixTopologyMessage),
    Stub(StubCanvasMsg),
}

impl HostedCanvas {
    pub fn id(&self) -> &str {
        match self {
            HostedCanvas::Activity(c) => c.id(),
            HostedCanvas::Thinking(c) => c.id(),
            HostedCanvas::Decision(c) => c.id(),
            HostedCanvas::Topology(c) => c.id(),
            HostedCanvas::Stub(c) => c.id(),
        }
    }

    pub fn canvas_type(&self) -> CanvasType {
        match self {
            HostedCanvas::Activity(c) => c.canvas_type(),
            HostedCanvas::Thinking(c) => c.canvas_type(),
            HostedCanvas::Decision(c) => c.canvas_type(),
            HostedCanvas::Topology(c) => c.canvas_type(),
            HostedCanvas::Stub(c) => c.canvas_type(),
        }
    }

    pub fn title(&self) -> String {
        match self {
            HostedCanvas::Activity(c) => c.title(),
            HostedCanvas::Thinking(c) => c.title(),
            HostedCanvas::Decision(c) => c.title(),
            HostedCanvas::Topology(c) => c.title(),
            HostedCanvas::Stub(c) => c.title(),
        }
    }

    pub fn view(&self) -> Element<'_, CanvasPaneMessage> {
        match self {
            HostedCanvas::Activity(c) => c.view().map(CanvasPaneMessage::Activity),
            HostedCanvas::Thinking(c) => c.view().map(CanvasPaneMessage::Thinking),
            HostedCanvas::Decision(c) => c.view().map(CanvasPaneMessage::Decision),
            HostedCanvas::Topology(c) => c.view().map(CanvasPaneMessage::Topology),
            HostedCanvas::Stub(c) => c.view().map(CanvasPaneMessage::Stub),
        }
    }

    pub fn update(&mut self, msg: CanvasPaneMessage) -> Task<CanvasPaneMessage> {
        match (self, msg) {
            (HostedCanvas::Activity(c), CanvasPaneMessage::Activity(m)) => {
                c.update(m).map(CanvasPaneMessage::Activity)
            }
            (HostedCanvas::Thinking(c), CanvasPaneMessage::Thinking(m)) => {
                c.update(m).map(CanvasPaneMessage::Thinking)
            }
            (HostedCanvas::Decision(c), CanvasPaneMessage::Decision(m)) => {
                c.update(m).map(CanvasPaneMessage::Decision)
            }
            (HostedCanvas::Topology(c), CanvasPaneMessage::Topology(m)) => {
                c.update(m).map(CanvasPaneMessage::Topology)
            }
            (HostedCanvas::Stub(c), CanvasPaneMessage::Stub(m)) => {
                c.update(m).map(CanvasPaneMessage::Stub)
            }
            _ => Task::none(),
        }
    }

    pub fn subscription(&self) -> Subscription<CanvasPaneMessage> {
        match self {
            HostedCanvas::Activity(c) => c.subscription().map(CanvasPaneMessage::Activity),
            HostedCanvas::Thinking(c) => c.subscription().map(CanvasPaneMessage::Thinking),
            HostedCanvas::Decision(c) => c.subscription().map(CanvasPaneMessage::Decision),
            HostedCanvas::Topology(c) => c.subscription().map(CanvasPaneMessage::Topology),
            HostedCanvas::Stub(c) => c.subscription().map(CanvasPaneMessage::Stub),
        }
    }

    /// Full JSON blob stored in SQLite (`kind` + payload).
    pub fn persist_blob(&self) -> Value {
        match self {
            HostedCanvas::Activity(c) => json!({
                "kind": "activity_stream",
                "state": c.serialize_state(),
            }),
            HostedCanvas::Thinking(c) => json!({
                "kind": "thinking_vis",
                "state": c.persist_snapshot(),
            }),
            HostedCanvas::Decision(c) => json!({
                "kind": "decision_tree",
                "state": c.serialize_state(),
            }),
            HostedCanvas::Topology(c) => json!({
                "kind": "topology",
                "state": c.serialize_state(),
            }),
            HostedCanvas::Stub(c) => json!({
                "kind": "stub",
                "canvas_type": canvas_type_tag(c.canvas_type()),
                "state": c.serialize_state(),
            }),
        }
    }

    pub fn from_persist_blob(
        id: String,
        canvas_type: CanvasType,
        blob: Value,
    ) -> Result<Self, CanvasError> {
        let kind = blob.get("kind").and_then(|k| k.as_str()).unwrap_or("stub");
        match kind {
            "activity_stream" => {
                let st = blob.get("state").cloned().unwrap_or(Value::Null);
                let session = st.get("session_id").and_then(|v| v.as_str()).map(String::from);
                let task = st.get("task_id").and_then(|v| v.as_str()).map(String::from);
                let mut c = ActivityStreamCanvas::new(id, session, task);
                c.deserialize_state(&st)?;
                Ok(HostedCanvas::Activity(c))
            }
            "thinking_vis" => {
                let st = blob.get("state").cloned().unwrap_or(Value::Null);
                let mut c = ThinkingVisCanvas::new(id);
                c.apply_persist_snapshot(&st)
                    .map_err(|e| CanvasError::Deserialize(e))?;
                Ok(HostedCanvas::Thinking(c))
            }
            "decision_tree" => {
                let st = blob.get("state").cloned().unwrap_or(Value::Null);
                let mut c = DecisionTreeCanvas::new(id, None);
                c.deserialize_state(&st)?;
                Ok(HostedCanvas::Decision(c))
            }
            "topology" => {
                let st = blob.get("state").cloned().unwrap_or(Value::Null);
                let session = st.get("session_id").and_then(|v| v.as_str()).map(String::from);
                let mut c = DendrixTopologyCanvas::new(id, session);
                c.deserialize_state(&st)?;
                Ok(HostedCanvas::Topology(c))
            }
            _ => {
                let st = blob.get("state").cloned().unwrap_or(blob.clone());
                let title = st
                    .get("title")
                    .and_then(|v| v.as_str())
                    .map(String::from)
                    .unwrap_or_else(|| default_title(canvas_type));
                let params = st.get("params").cloned().unwrap_or(json!({}));
                let mut stub = StubCanvas::new(id, canvas_type, title, params);
                stub.deserialize_state(&st)?;
                Ok(HostedCanvas::Stub(stub))
            }
        }
    }
}

pub struct CanvasRegistry {
    canvases: HashMap<String, HostedCanvas>,
}

impl Default for CanvasRegistry {
    fn default() -> Self {
        Self::new()
    }
}

impl CanvasRegistry {
    pub fn new() -> Self {
        Self {
            canvases: HashMap::new(),
        }
    }

    pub fn register(&mut self, canvas: HostedCanvas) {
        self.canvases.insert(canvas.id().to_string(), canvas);
    }

    pub fn get(&self, id: &str) -> Option<&HostedCanvas> {
        self.canvases.get(id)
    }

    pub fn get_mut(&mut self, id: &str) -> Option<&mut HostedCanvas> {
        self.canvases.get_mut(id)
    }

    pub fn remove(&mut self, id: &str) -> Option<HostedCanvas> {
        self.canvases.remove(id)
    }

    pub fn list_pairs(&self) -> Vec<(&str, CanvasType)> {
        self.canvases
            .values()
            .map(|c| (c.id(), c.canvas_type()))
            .collect()
    }

    pub fn create(canvas_type: CanvasType, params: Value) -> Result<HostedCanvas, CanvasError> {
        let id = Uuid::new_v4().to_string();
        Ok(match canvas_type {
            CanvasType::ActivityStream => {
                let session = params.get("session").and_then(|v| v.as_str()).map(String::from);
                let task = params.get("task").and_then(|v| v.as_str()).map(String::from);
                HostedCanvas::Activity(ActivityStreamCanvas::new(id, session, task))
            }
            CanvasType::ThinkingVis => {
                let mut c = ThinkingVisCanvas::new(id);
                if let Some(s) = params.get("session").and_then(|v| v.as_str()) {
                    c.session_id = Some(s.to_string());
                }
                if let Some(a) = params.get("agent").and_then(|v| v.as_str()) {
                    c.agent_id = Some(a.to_string());
                }
                if let Some(t) = params.get("title").and_then(|v| v.as_str()) {
                    let _ = t;
                }
                HostedCanvas::Thinking(c)
            }
            CanvasType::DecisionTree => {
                let mut c = DecisionTreeCanvas::new(
                    id,
                    params
                        .get("session")
                        .and_then(|v| v.as_str())
                        .map(String::from),
                );
                if let Some(tree) = params.get("tree") {
                    if let Ok(root) = serde_json::from_value::<DecisionNode>(tree.clone()) {
                        let w = params
                            .get("viewport_w")
                            .and_then(|v| v.as_f64())
                            .unwrap_or(960.0) as f32;
                        let h = params
                            .get("viewport_h")
                            .and_then(|v| v.as_f64())
                            .unwrap_or(560.0) as f32;
                        c.load_tree(root, Size::new(w, h));
                    }
                }
                HostedCanvas::Decision(c)
            }
            CanvasType::Topology => {
                let session = params.get("session").and_then(|v| v.as_str()).map(String::from);
                HostedCanvas::Topology(DendrixTopologyCanvas::new(id, session))
            }
            CanvasType::StatCard => {
                let title = params
                    .get("title")
                    .and_then(|v| v.as_str())
                    .map(String::from)
                    .unwrap_or_else(|| default_title(canvas_type));
                HostedCanvas::Stub(StubCanvas::new(id, canvas_type, title, params))
            }
        })
    }
}

fn default_title(canvas_type: CanvasType) -> String {
    match canvas_type {
        CanvasType::StatCard => "Stat card".into(),
        CanvasType::ActivityStream => "Activity stream".into(),
        CanvasType::DecisionTree => "Decision tree".into(),
        CanvasType::ThinkingVis => "Thinking visualization".into(),
        CanvasType::Topology => "Dendrix topology".into(),
    }
}

fn canvas_type_tag(t: CanvasType) -> &'static str {
    match t {
        CanvasType::StatCard => "stat_card",
        CanvasType::ActivityStream => "activity_stream",
        CanvasType::DecisionTree => "decision_tree",
        CanvasType::ThinkingVis => "thinking_vis",
        CanvasType::Topology => "topology",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_stub_round_trip() {
        let c = CanvasRegistry::create(
            CanvasType::ActivityStream,
            json!({"session": "abc", "title": "Hi"}),
        )
        .unwrap();
        let blob = c.persist_blob();
        let id = c.id().to_string();
        let back =
            HostedCanvas::from_persist_blob(id.clone(), CanvasType::ActivityStream, blob).unwrap();
        assert_eq!(back.id(), id);
    }
}
