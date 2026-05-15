//! D-Bus surface for opening SCP canvases via URI (`OpenCanvas`).

use iced::futures::SinkExt;
use tokio::sync::mpsc;

pub const CANVAS_DBUS_WELL_KNOWN: &str = "sh.datapunk.ContinuumStudio";
pub const CANVAS_DBUS_PATH: &str = "/sh/datapunk/ContinuumStudio";
pub const CANVAS_DBUS_INTERFACE: &str = "sh.datapunk.ContinuumStudio1";

pub struct ContinuumCanvasIpc {
    uri_tx: mpsc::Sender<String>,
}

#[zbus::interface(name = "sh.datapunk.ContinuumStudio1")]
impl ContinuumCanvasIpc {
    /// Opens or focuses a canvas from a `synapsix://canvas/...` URI.
    #[zbus(name = "OpenCanvas")]
    async fn open_canvas(&self, uri: String) -> bool {
        self.uri_tx.send(uri).await.is_ok()
    }
}

pub fn canvas_dbus_subscription() -> iced::Subscription<String> {
    iced::Subscription::run(|| {
        iced::stream::channel(
            16,
            |mut output: iced::futures::channel::mpsc::Sender<String>| async move {
                let (uri_tx, mut uri_rx) = mpsc::channel::<String>(32);
                let ipc = ContinuumCanvasIpc { uri_tx };

                match zbus::connection::Builder::session()
                    .and_then(|b| b.name(CANVAS_DBUS_WELL_KNOWN))
                    .and_then(|b| b.serve_at(CANVAS_DBUS_PATH, ipc))
                {
                    Ok(builder) => match builder.build().await {
                        Ok(_conn) => {
                            log::info!(
                                "Canvas D-Bus: {} at {} ({})",
                                CANVAS_DBUS_WELL_KNOWN,
                                CANVAS_DBUS_PATH,
                                CANVAS_DBUS_INTERFACE
                            );
                            while let Some(uri) = uri_rx.recv().await {
                                let _ = output.send(uri).await;
                            }
                        }
                        Err(e) => log::warn!("Canvas D-Bus connection failed: {}", e),
                    },
                    Err(e) => log::warn!("Canvas D-Bus builder failed: {}", e),
                }
            },
        )
    })
}
