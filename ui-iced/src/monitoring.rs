//! Session Monitoring for Cursor instances
//!
//! Provides real-time metrics and health monitoring for running Cursor sessions:
//! - CPU usage tracking
//! - Memory usage tracking
//! - Thread counts
//! - Open file descriptors
//! - Process health assessment
//! - Historical data for graphs

use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::time::Instant;

/// Maximum number of historical data points to keep for each session
const MAX_HISTORY_POINTS: usize = 60;

/// Metrics for a single Cursor session at a point in time
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionMetrics {
    /// Process ID
    pub pid: u32,
    /// Timestamp when metrics were collected
    pub timestamp: u64,
    /// CPU usage percentage (0-100)
    pub cpu_percent: f32,
    /// Memory usage in bytes
    pub memory_bytes: u64,
    /// Memory usage in human-readable format
    pub memory_human: String,
    /// Resident Set Size (actual physical memory)
    pub rss_bytes: u64,
    /// Virtual memory size
    pub vsize_bytes: u64,
    /// Number of threads
    pub thread_count: u32,
    /// Number of open file descriptors
    pub fd_count: u32,
    /// Process state (R=running, S=sleeping, etc.)
    pub state: char,
    /// Health status derived from metrics
    pub health: HealthStatus,
}

/// Health status indicators
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum HealthStatus {
    /// Everything is normal
    Healthy,
    /// Some metrics are elevated but acceptable
    Warning,
    /// Metrics indicate potential issues
    Critical,
    /// Unable to determine health
    Unknown,
}

impl HealthStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            HealthStatus::Healthy => "Healthy",
            HealthStatus::Warning => "Warning",
            HealthStatus::Critical => "Critical",
            HealthStatus::Unknown => "Unknown",
        }
    }

    pub fn color(&self) -> (f32, f32, f32) {
        match self {
            HealthStatus::Healthy => (0.3, 0.8, 0.4),  // Green
            HealthStatus::Warning => (0.9, 0.7, 0.2),  // Yellow/Orange
            HealthStatus::Critical => (0.9, 0.3, 0.3), // Red
            HealthStatus::Unknown => (0.5, 0.5, 0.5),  // Gray
        }
    }
}

/// Historical metrics for a session
#[derive(Debug, Clone)]
pub struct SessionHistory {
    /// Process ID
    pub pid: u32,
    /// CPU usage history (last N readings)
    pub cpu_history: VecDeque<f32>,
    /// Memory usage history (in MB)
    pub memory_history: VecDeque<f32>,
    /// Thread count history
    pub thread_history: VecDeque<u32>,
    /// File descriptor count history
    pub fd_history: VecDeque<u32>,
    /// Timestamps for each data point
    pub timestamps: VecDeque<u64>,
    /// Latest full metrics
    pub latest: Option<SessionMetrics>,
    /// Last update time
    pub last_update: Instant,
}

impl SessionHistory {
    pub fn new(pid: u32) -> Self {
        Self {
            pid,
            cpu_history: VecDeque::with_capacity(MAX_HISTORY_POINTS),
            memory_history: VecDeque::with_capacity(MAX_HISTORY_POINTS),
            thread_history: VecDeque::with_capacity(MAX_HISTORY_POINTS),
            fd_history: VecDeque::with_capacity(MAX_HISTORY_POINTS),
            timestamps: VecDeque::with_capacity(MAX_HISTORY_POINTS),
            latest: None,
            last_update: Instant::now(),
        }
    }

    /// Add a new metrics reading to history
    pub fn add_metrics(&mut self, metrics: SessionMetrics) {
        // Trim old data if needed
        while self.cpu_history.len() >= MAX_HISTORY_POINTS {
            self.cpu_history.pop_front();
            self.memory_history.pop_front();
            self.thread_history.pop_front();
            self.fd_history.pop_front();
            self.timestamps.pop_front();
        }

        // Add new data
        self.cpu_history.push_back(metrics.cpu_percent);
        self.memory_history
            .push_back(metrics.memory_bytes as f32 / 1024.0 / 1024.0); // Convert to MB
        self.thread_history.push_back(metrics.thread_count);
        self.fd_history.push_back(metrics.fd_count);
        self.timestamps.push_back(metrics.timestamp);
        self.latest = Some(metrics);
        self.last_update = Instant::now();
    }

    /// Get average CPU usage
    pub fn avg_cpu(&self) -> f32 {
        if self.cpu_history.is_empty() {
            0.0
        } else {
            self.cpu_history.iter().sum::<f32>() / self.cpu_history.len() as f32
        }
    }

    /// Get max CPU usage
    pub fn max_cpu(&self) -> f32 {
        self.cpu_history.iter().cloned().fold(0.0, f32::max)
    }

    /// Get average memory usage in MB
    pub fn avg_memory_mb(&self) -> f32 {
        if self.memory_history.is_empty() {
            0.0
        } else {
            self.memory_history.iter().sum::<f32>() / self.memory_history.len() as f32
        }
    }

    /// Get max memory usage in MB
    pub fn max_memory_mb(&self) -> f32 {
        self.memory_history.iter().cloned().fold(0.0, f32::max)
    }
}

/// Session monitor that collects metrics from running processes
pub struct SessionMonitor {
    /// Historical data per session (by PID)
    histories: std::collections::HashMap<u32, SessionHistory>,
    /// Previous CPU tick values for calculating delta (by PID)
    prev_cpu_ticks: std::collections::HashMap<u32, (u64, u64)>, // (process ticks, total ticks)
    /// System total CPU ticks
    system_total_ticks: u64,
}

impl Default for SessionMonitor {
    fn default() -> Self {
        Self::new()
    }
}

impl SessionMonitor {
    pub fn new() -> Self {
        Self {
            histories: std::collections::HashMap::new(),
            prev_cpu_ticks: std::collections::HashMap::new(),
            system_total_ticks: 0,
        }
    }

    /// Collect metrics for a specific PID
    pub fn collect_metrics(&mut self, pid: u32) -> Option<SessionMetrics> {
        // Read /proc/[pid]/stat for process stats
        let stat = self.read_proc_stat(pid)?;

        // Read /proc/[pid]/statm for memory info
        let (vsize, rss) = self.read_proc_statm(pid)?;

        // Count open file descriptors from /proc/[pid]/fd
        let fd_count = self.count_file_descriptors(pid);

        // Calculate CPU percentage
        let cpu_percent = self.calculate_cpu_percent(pid, stat.utime + stat.stime);

        // Derive health status
        let health = self.assess_health(cpu_percent, rss, stat.num_threads, fd_count);

        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);

        let metrics = SessionMetrics {
            pid,
            timestamp,
            cpu_percent,
            memory_bytes: rss,
            memory_human: format_bytes(rss),
            rss_bytes: rss,
            vsize_bytes: vsize,
            thread_count: stat.num_threads,
            fd_count,
            state: stat.state,
            health,
        };

        // Update history
        self.histories
            .entry(pid)
            .or_insert_with(|| SessionHistory::new(pid))
            .add_metrics(metrics.clone());

        Some(metrics)
    }

    /// Collect metrics for all known sessions
    pub fn collect_all_metrics(&mut self, pids: &[u32]) -> Vec<SessionMetrics> {
        // Update system total CPU ticks
        self.update_system_ticks();

        pids.iter()
            .filter_map(|&pid| self.collect_metrics(pid))
            .collect()
    }

    /// Get history for a specific session
    pub fn get_history(&self, pid: u32) -> Option<&SessionHistory> {
        self.histories.get(&pid)
    }

    /// Get all histories
    pub fn get_all_histories(&self) -> &std::collections::HashMap<u32, SessionHistory> {
        &self.histories
    }

    /// Clean up stale sessions
    pub fn cleanup_stale(&mut self, active_pids: &[u32]) {
        self.histories.retain(|pid, _| active_pids.contains(pid));
        self.prev_cpu_ticks
            .retain(|pid, _| active_pids.contains(pid));
    }

    // Private helper methods

    fn read_proc_stat(&self, pid: u32) -> Option<ProcStat> {
        let path = format!("/proc/{}/stat", pid);
        let content = std::fs::read_to_string(&path).ok()?;

        // Parse the stat file - format is: pid (comm) state ppid pgrp session tty_nr tpgid flags
        // We need to handle the comm field which can contain spaces and parentheses
        let _start_comm = content.find('(')?;
        let end_comm = content.rfind(')')?;

        let after_comm = &content[end_comm + 2..]; // Skip ") "
        let fields: Vec<&str> = after_comm.split_whitespace().collect();

        if fields.len() < 20 {
            return None;
        }

        Some(ProcStat {
            state: fields[0].chars().next().unwrap_or('?'),
            utime: fields[11].parse().unwrap_or(0),
            stime: fields[12].parse().unwrap_or(0),
            num_threads: fields[17].parse().unwrap_or(0),
        })
    }

    fn read_proc_statm(&self, pid: u32) -> Option<(u64, u64)> {
        let path = format!("/proc/{}/statm", pid);
        let content = std::fs::read_to_string(&path).ok()?;
        let fields: Vec<&str> = content.split_whitespace().collect();

        if fields.len() < 2 {
            return None;
        }

        let page_size = 4096u64; // Standard page size on most Linux systems
        let vsize = fields[0].parse::<u64>().ok()? * page_size;
        let rss = fields[1].parse::<u64>().ok()? * page_size;

        Some((vsize, rss))
    }

    fn count_file_descriptors(&self, pid: u32) -> u32 {
        let path = format!("/proc/{}/fd", pid);
        std::fs::read_dir(&path)
            .map(|entries| entries.count() as u32)
            .unwrap_or(0)
    }

    fn update_system_ticks(&mut self) {
        if let Ok(content) = std::fs::read_to_string("/proc/stat") {
            if let Some(cpu_line) = content.lines().next() {
                let fields: Vec<&str> = cpu_line.split_whitespace().collect();
                if fields.len() > 7 && fields[0] == "cpu" {
                    let total: u64 = fields[1..=7]
                        .iter()
                        .filter_map(|s| s.parse::<u64>().ok())
                        .sum();
                    self.system_total_ticks = total;
                }
            }
        }
    }

    fn calculate_cpu_percent(&mut self, pid: u32, current_ticks: u64) -> f32 {
        let current_total = self.system_total_ticks;

        if let Some(&(prev_proc, prev_total)) = self.prev_cpu_ticks.get(&pid) {
            let proc_delta = current_ticks.saturating_sub(prev_proc);
            let total_delta = current_total.saturating_sub(prev_total);

            if total_delta > 0 {
                let percent = (proc_delta as f64 / total_delta as f64 * 100.0) as f32;
                self.prev_cpu_ticks
                    .insert(pid, (current_ticks, current_total));
                return percent.clamp(0.0, 100.0);
            }
        }

        self.prev_cpu_ticks
            .insert(pid, (current_ticks, current_total));
        0.0 // First reading, can't calculate delta
    }

    fn assess_health(&self, cpu: f32, memory: u64, threads: u32, fds: u32) -> HealthStatus {
        let memory_gb = memory as f64 / 1024.0 / 1024.0 / 1024.0;

        // Critical thresholds
        if cpu > 90.0 || memory_gb > 8.0 || fds > 10000 {
            return HealthStatus::Critical;
        }

        // Warning thresholds
        if cpu > 50.0 || memory_gb > 4.0 || threads > 100 || fds > 5000 {
            return HealthStatus::Warning;
        }

        HealthStatus::Healthy
    }
}

/// Parsed /proc/[pid]/stat data
struct ProcStat {
    state: char,
    utime: u64, // User time
    stime: u64, // System time
    num_threads: u32,
}

/// Format bytes into human-readable string
fn format_bytes(bytes: u64) -> String {
    const KB: u64 = 1024;
    const MB: u64 = KB * 1024;
    const GB: u64 = MB * 1024;

    if bytes >= GB {
        format!("{:.1} GB", bytes as f64 / GB as f64)
    } else if bytes >= MB {
        format!("{:.1} MB", bytes as f64 / MB as f64)
    } else if bytes >= KB {
        format!("{:.1} KB", bytes as f64 / KB as f64)
    } else {
        format!("{} B", bytes)
    }
}

/// Dashboard data for displaying session metrics
#[derive(Debug, Clone)]
pub struct DashboardData {
    /// Total sessions being monitored
    pub total_sessions: usize,
    /// Sessions by health status
    pub healthy_count: usize,
    pub warning_count: usize,
    pub critical_count: usize,
    /// Aggregate metrics
    pub total_cpu: f32,
    pub total_memory_mb: f32,
    pub total_threads: u32,
    pub total_fds: u32,
}

impl DashboardData {
    pub fn from_metrics(metrics: &[SessionMetrics]) -> Self {
        let healthy_count = metrics
            .iter()
            .filter(|m| m.health == HealthStatus::Healthy)
            .count();
        let warning_count = metrics
            .iter()
            .filter(|m| m.health == HealthStatus::Warning)
            .count();
        let critical_count = metrics
            .iter()
            .filter(|m| m.health == HealthStatus::Critical)
            .count();

        let total_cpu: f32 = metrics.iter().map(|m| m.cpu_percent).sum();
        let total_memory_mb: f32 = metrics
            .iter()
            .map(|m| m.memory_bytes as f32 / 1024.0 / 1024.0)
            .sum();
        let total_threads: u32 = metrics.iter().map(|m| m.thread_count).sum();
        let total_fds: u32 = metrics.iter().map(|m| m.fd_count).sum();

        Self {
            total_sessions: metrics.len(),
            healthy_count,
            warning_count,
            critical_count,
            total_cpu,
            total_memory_mb,
            total_threads,
            total_fds,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_format_bytes() {
        assert_eq!(format_bytes(500), "500 B");
        assert_eq!(format_bytes(1024), "1.0 KB");
        assert_eq!(format_bytes(1024 * 1024), "1.0 MB");
        assert_eq!(format_bytes(1024 * 1024 * 1024), "1.0 GB");
        assert_eq!(format_bytes(1536 * 1024 * 1024), "1.5 GB");
    }

    #[test]
    fn test_health_assessment() {
        let monitor = SessionMonitor::new();

        // Healthy
        assert_eq!(
            monitor.assess_health(10.0, 500 * 1024 * 1024, 20, 100),
            HealthStatus::Healthy
        );

        // Warning
        assert_eq!(
            monitor.assess_health(60.0, 500 * 1024 * 1024, 20, 100),
            HealthStatus::Warning
        );

        // Critical
        assert_eq!(
            monitor.assess_health(95.0, 500 * 1024 * 1024, 20, 100),
            HealthStatus::Critical
        );
    }
}
