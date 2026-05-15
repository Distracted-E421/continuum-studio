//! SQLite persistence for SCP canvas sessions.

use crate::canvas::workspace::HostedCanvas;
use crate::scp::CanvasType;
use chrono::{DateTime, Utc};
use rusqlite::{params, Connection};
use std::path::{Path, PathBuf};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum CanvasPersistenceError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("sqlite: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("canvas: {0}")]
    Canvas(#[from] crate::canvas::CanvasError),
    #[error("unknown canvas_type column: {0}")]
    UnknownCanvasType(String),
}

#[derive(Debug, Clone)]
pub struct CanvasSession {
    pub id: String,
    pub canvas_type: CanvasType,
    pub title: String,
    pub session_id: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

pub struct CanvasPersistence {
    db_path: PathBuf,
}

fn canvas_type_as_str(t: CanvasType) -> &'static str {
    match t {
        CanvasType::StatCard => "stat_card",
        CanvasType::ActivityStream => "activity_stream",
        CanvasType::DecisionTree => "decision_tree",
        CanvasType::ThinkingVis => "thinking_vis",
        CanvasType::Topology => "topology",
    }
}

fn canvas_type_from_str(s: &str) -> Result<CanvasType, CanvasPersistenceError> {
    Ok(match s {
        "stat_card" => CanvasType::StatCard,
        "activity_stream" => CanvasType::ActivityStream,
        "decision_tree" => CanvasType::DecisionTree,
        "thinking_vis" => CanvasType::ThinkingVis,
        "topology" => CanvasType::Topology,
        other => return Err(CanvasPersistenceError::UnknownCanvasType(other.to_string())),
    })
}

impl CanvasPersistence {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, CanvasPersistenceError> {
        let db_path = path.as_ref().to_path_buf();
        if let Some(dir) = db_path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        let slf = Self { db_path };
        slf.init_schema()?;
        Ok(slf)
    }

    pub fn open_default() -> Result<Self, CanvasPersistenceError> {
        let dir = dirs::data_local_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("continuum-studio");
        Self::open(dir.join("scp_canvas_sessions.sqlite"))
    }

    fn conn(&self) -> Result<Connection, rusqlite::Error> {
        Connection::open(&self.db_path)
    }

    fn init_schema(&self) -> Result<(), CanvasPersistenceError> {
        let conn = self.conn()?;
        conn.execute_batch(
            r"
            CREATE TABLE IF NOT EXISTS scp_canvas_sessions (
              id TEXT PRIMARY KEY,
              canvas_type TEXT NOT NULL,
              title TEXT NOT NULL,
              session_id TEXT,
              state_json TEXT NOT NULL,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_scp_canvas_updated ON scp_canvas_sessions(updated_at);
        ",
        )?;
        Ok(())
    }

    pub fn save(&self, canvas: &HostedCanvas) -> Result<(), CanvasPersistenceError> {
        let conn = self.conn()?;
        let state = canvas.persist_blob();
        let title = canvas.title();
        let session_id = extract_session_id(&state);
        let canvas_type = canvas_type_as_str(canvas.canvas_type());
        let now = Utc::now().to_rfc3339();

        let exists: bool = conn
            .query_row(
                "SELECT 1 FROM scp_canvas_sessions WHERE id = ?1",
                params![canvas.id()],
                |_| Ok(true),
            )
            .unwrap_or(false);

        let state_s = serde_json::to_string(&state).unwrap_or_else(|_| "{}".into());

        if exists {
            conn.execute(
                r"UPDATE scp_canvas_sessions SET canvas_type = ?1, title = ?2, session_id = ?3,
                   state_json = ?4, updated_at = ?5 WHERE id = ?6",
                params![canvas_type, title, session_id, state_s, now, canvas.id(),],
            )?;
        } else {
            conn.execute(
                r"INSERT INTO scp_canvas_sessions
                  (id, canvas_type, title, session_id, state_json, created_at, updated_at)
                  VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![
                    canvas.id(),
                    canvas_type,
                    title,
                    session_id,
                    state_s,
                    now,
                    now,
                ],
            )?;
        }
        Ok(())
    }

    pub fn load(&self, canvas_id: &str) -> Result<HostedCanvas, CanvasPersistenceError> {
        let conn = self.conn()?;
        let (ty, state_raw): (String, String) = conn.query_row(
            "SELECT canvas_type, state_json FROM scp_canvas_sessions WHERE id = ?1",
            params![canvas_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        let canvas_type = canvas_type_from_str(&ty)?;
        let blob: serde_json::Value =
            serde_json::from_str(&state_raw).unwrap_or_else(|_| serde_json::json!({}));
        Ok(HostedCanvas::from_persist_blob(
            canvas_id.to_string(),
            canvas_type,
            blob,
        )?)
    }

    pub fn list_sessions(&self) -> Result<Vec<CanvasSession>, CanvasPersistenceError> {
        let conn = self.conn()?;
        let mut stmt = conn.prepare(
            "SELECT id, canvas_type, title, session_id, created_at, updated_at
             FROM scp_canvas_sessions ORDER BY updated_at DESC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, Option<String>>(3)?,
                row.get::<_, String>(4)?,
                row.get::<_, String>(5)?,
            ))
        })?;

        let mut out = Vec::new();
        for r in rows {
            let (id, canvas_type_s, title, session_id, created_at_s, updated_at_s) = r?;
            let canvas_type = canvas_type_from_str(&canvas_type_s)?;
            let created_at = DateTime::parse_from_rfc3339(&created_at_s)
                .map(|d| d.with_timezone(&Utc))
                .unwrap_or_else(|_| Utc::now());
            let updated_at = DateTime::parse_from_rfc3339(&updated_at_s)
                .map(|d| d.with_timezone(&Utc))
                .unwrap_or_else(|_| Utc::now());
            out.push(CanvasSession {
                id,
                canvas_type,
                title,
                session_id,
                created_at,
                updated_at,
            });
        }
        Ok(out)
    }

    pub fn delete(&self, canvas_id: &str) -> Result<(), CanvasPersistenceError> {
        let conn = self.conn()?;
        conn.execute(
            "DELETE FROM scp_canvas_sessions WHERE id = ?1",
            params![canvas_id],
        )?;
        Ok(())
    }

    pub fn db_path(&self) -> &Path {
        &self.db_path
    }
}

fn extract_session_id(state: &serde_json::Value) -> Option<String> {
    state
        .get("state")
        .and_then(|s| s.get("session_id"))
        .and_then(|v| v.as_str())
        .map(String::from)
        .or_else(|| {
            state
                .pointer("/state/params/session")
                .and_then(|v| v.as_str())
                .map(String::from)
        })
}
