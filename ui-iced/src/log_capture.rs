//! In-app log capture for debugging
//!
//! Captures log messages to an in-memory buffer that can be
//! displayed in the UI and copied to clipboard.

use log::{Level, LevelFilter, Log, Metadata, Record};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

/// Maximum number of log entries to keep
const MAX_LOG_ENTRIES: usize = 1000;

/// A single log entry
#[derive(Debug, Clone)]
pub struct LogEntry {
    pub timestamp: SystemTime,
    pub level: Level,
    pub target: String,
    pub message: String,
}

impl LogEntry {
    /// Format as a single line for display
    pub fn format(&self) -> String {
        let elapsed = self
            .timestamp
            .duration_since(SystemTime::UNIX_EPOCH)
            .unwrap_or_default();
        let secs = elapsed.as_secs();
        let hours = (secs / 3600) % 24;
        let mins = (secs / 60) % 60;
        let secs = secs % 60;

        format!(
            "[{:02}:{:02}:{:02}] {:5} {} - {}",
            hours, mins, secs, self.level, self.target, self.message
        )
    }

    /// Get color for this log level
    pub fn level_color(&self) -> iced::Color {
        match self.level {
            Level::Error => iced::Color::from_rgb(0.9, 0.3, 0.3),
            Level::Warn => iced::Color::from_rgb(0.9, 0.7, 0.3),
            Level::Info => iced::Color::from_rgb(0.4, 0.7, 0.4),
            Level::Debug => iced::Color::from_rgb(0.5, 0.5, 0.5),
            Level::Trace => iced::Color::from_rgb(0.4, 0.4, 0.4),
        }
    }
}

/// Shared log buffer
#[derive(Clone)]
pub struct LogBuffer {
    entries: Arc<Mutex<VecDeque<LogEntry>>>,
}

impl LogBuffer {
    /// Create a new empty buffer
    pub fn new() -> Self {
        Self {
            entries: Arc::new(Mutex::new(VecDeque::with_capacity(MAX_LOG_ENTRIES))),
        }
    }

    /// Add a log entry
    pub fn push(&self, entry: LogEntry) {
        let mut entries = self.entries.lock().unwrap();
        if entries.len() >= MAX_LOG_ENTRIES {
            entries.pop_front();
        }
        entries.push_back(entry);
    }

    /// Get all entries
    pub fn entries(&self) -> Vec<LogEntry> {
        self.entries.lock().unwrap().iter().cloned().collect()
    }

    /// Get entries filtered by level
    pub fn entries_filtered(&self, min_level: Level) -> Vec<LogEntry> {
        self.entries
            .lock()
            .unwrap()
            .iter()
            .filter(|e| e.level <= min_level)
            .cloned()
            .collect()
    }

    /// Clear all entries
    pub fn clear(&self) {
        self.entries.lock().unwrap().clear();
    }

    /// Get entry count
    pub fn len(&self) -> usize {
        self.entries.lock().unwrap().len()
    }

    /// Check if buffer is empty
    pub fn is_empty(&self) -> bool {
        self.entries.lock().unwrap().is_empty()
    }

    /// Format all entries as a string for copying
    pub fn format_all(&self) -> String {
        self.entries
            .lock()
            .unwrap()
            .iter()
            .map(|e| e.format())
            .collect::<Vec<_>>()
            .join("\n")
    }
}

impl Default for LogBuffer {
    fn default() -> Self {
        Self::new()
    }
}

/// Custom logger that captures to both stderr and our buffer
pub struct CaptureLogger {
    buffer: LogBuffer,
    level: LevelFilter,
}

impl CaptureLogger {
    pub fn new(buffer: LogBuffer, level: LevelFilter) -> Self {
        Self { buffer, level }
    }
}

impl Log for CaptureLogger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.level() <= self.level
    }

    fn log(&self, record: &Record) {
        if self.enabled(record.metadata()) {
            // Capture to buffer
            self.buffer.push(LogEntry {
                timestamp: SystemTime::now(),
                level: record.level(),
                target: record.target().to_string(),
                message: format!("{}", record.args()),
            });

            // Also print to stderr for terminal users
            eprintln!(
                "[{}] {:5} {} - {}",
                chrono::Local::now().format("%H:%M:%S"),
                record.level(),
                record.target(),
                record.args()
            );
        }
    }

    fn flush(&self) {}
}

/// Initialize the capture logger
pub fn init_logger(level: LevelFilter) -> LogBuffer {
    let buffer = LogBuffer::new();
    let logger = CaptureLogger::new(buffer.clone(), level);

    log::set_boxed_logger(Box::new(logger))
        .map(|()| log::set_max_level(level))
        .expect("Failed to set logger");

    buffer
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_log_buffer_new() {
        let buffer = LogBuffer::new();
        assert!(buffer.is_empty());
        assert_eq!(buffer.len(), 0);
    }

    #[test]
    fn test_log_buffer_push_and_entries() {
        let buffer = LogBuffer::new();

        buffer.push(LogEntry {
            timestamp: SystemTime::now(),
            level: Level::Info,
            target: "test".to_string(),
            message: "Hello".to_string(),
        });

        assert_eq!(buffer.len(), 1);
        assert!(!buffer.is_empty());

        let entries = buffer.entries();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].message, "Hello");
    }

    #[test]
    fn test_log_buffer_clear() {
        let buffer = LogBuffer::new();

        buffer.push(LogEntry {
            timestamp: SystemTime::now(),
            level: Level::Info,
            target: "test".to_string(),
            message: "Hello".to_string(),
        });

        assert_eq!(buffer.len(), 1);
        buffer.clear();
        assert!(buffer.is_empty());
    }

    #[test]
    fn test_log_buffer_max_entries() {
        let buffer = LogBuffer::new();

        for i in 0..MAX_LOG_ENTRIES + 100 {
            buffer.push(LogEntry {
                timestamp: SystemTime::now(),
                level: Level::Info,
                target: "test".to_string(),
                message: format!("Message {}", i),
            });
        }

        assert_eq!(buffer.len(), MAX_LOG_ENTRIES);
        let entries = buffer.entries();
        assert_eq!(entries[0].message, "Message 100");
    }

    #[test]
    fn test_log_buffer_level_filter() {
        let buffer = LogBuffer::new();

        buffer.push(LogEntry {
            timestamp: SystemTime::now(),
            level: Level::Error,
            target: "test".to_string(),
            message: "Error".to_string(),
        });
        buffer.push(LogEntry {
            timestamp: SystemTime::now(),
            level: Level::Warn,
            target: "test".to_string(),
            message: "Warning".to_string(),
        });
        buffer.push(LogEntry {
            timestamp: SystemTime::now(),
            level: Level::Info,
            target: "test".to_string(),
            message: "Info".to_string(),
        });
        buffer.push(LogEntry {
            timestamp: SystemTime::now(),
            level: Level::Debug,
            target: "test".to_string(),
            message: "Debug".to_string(),
        });

        let filtered = buffer.entries_filtered(Level::Warn);
        assert_eq!(filtered.len(), 2);

        let filtered = buffer.entries_filtered(Level::Info);
        assert_eq!(filtered.len(), 3);
    }

    #[test]
    fn test_log_entry_format() {
        let entry = LogEntry {
            timestamp: SystemTime::UNIX_EPOCH,
            level: Level::Info,
            target: "mymodule".to_string(),
            message: "Test message".to_string(),
        };

        let formatted = entry.format();
        assert!(formatted.contains("INFO"));
        assert!(formatted.contains("mymodule"));
        assert!(formatted.contains("Test message"));
    }

    #[test]
    fn test_format_all() {
        let buffer = LogBuffer::new();

        buffer.push(LogEntry {
            timestamp: SystemTime::UNIX_EPOCH,
            level: Level::Info,
            target: "test".to_string(),
            message: "First".to_string(),
        });
        buffer.push(LogEntry {
            timestamp: SystemTime::UNIX_EPOCH,
            level: Level::Error,
            target: "test".to_string(),
            message: "Second".to_string(),
        });

        let all = buffer.format_all();
        assert!(all.contains("First"));
        assert!(all.contains("Second"));
        assert!(all.contains("\n"));
    }
}
