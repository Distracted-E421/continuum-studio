//! Performance profiling utilities for Continuum Studio
//!
//! Provides lightweight timing spans that log to the existing log infrastructure.
//! Enable with RUST_LOG=continuum_studio_iced::profiling=debug

use std::time::Instant;

/// A simple profiling span that logs duration on drop
pub struct ProfileSpan {
    name: &'static str,
    start: Instant,
    threshold_ms: u64,
}

impl ProfileSpan {
    /// Create a new profile span
    /// Only logs if duration exceeds threshold_ms
    pub fn new(name: &'static str, threshold_ms: u64) -> Self {
        Self {
            name,
            start: Instant::now(),
            threshold_ms,
        }
    }

    /// Create a span with default 16ms threshold (one frame at 60 FPS)
    pub fn frame(name: &'static str) -> Self {
        Self::new(name, 16)
    }

    /// Create a span with 8ms threshold (one frame at 120 FPS)
    pub fn fast(name: &'static str) -> Self {
        Self::new(name, 8)
    }

    /// Create a span that always logs (0ms threshold)
    pub fn always(name: &'static str) -> Self {
        Self::new(name, 0)
    }
}

impl Drop for ProfileSpan {
    fn drop(&mut self) {
        let elapsed = self.start.elapsed();
        let ms = elapsed.as_secs_f64() * 1000.0;

        if ms >= self.threshold_ms as f64 {
            if ms >= 100.0 {
                log::warn!("[PERF] {} took {:.2}ms (SLOW)", self.name, ms);
            } else if ms >= 16.0 {
                log::info!("[PERF] {} took {:.2}ms", self.name, ms);
            } else {
                log::debug!("[PERF] {} took {:.2}ms", self.name, ms);
            }
        }
    }
}

/// Macro for convenient span creation
#[macro_export]
macro_rules! profile_span {
    ($name:expr) => {
        let _span = $crate::profiling::ProfileSpan::frame($name);
    };
    ($name:expr, $threshold:expr) => {
        let _span = $crate::profiling::ProfileSpan::new($name, $threshold);
    };
}

/// Macro for always-logging span (useful for debugging)
#[macro_export]
macro_rules! profile_always {
    ($name:expr) => {
        let _span = $crate::profiling::ProfileSpan::always($name);
    };
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread::sleep;
    use std::time::Duration;

    #[test]
    fn test_span_creation() {
        let span = ProfileSpan::new("test", 100);
        assert_eq!(span.name, "test");
        assert_eq!(span.threshold_ms, 100);
    }

    #[test]
    fn test_frame_span() {
        let span = ProfileSpan::frame("frame_test");
        assert_eq!(span.threshold_ms, 16);
    }

    #[test]
    fn test_fast_span() {
        let span = ProfileSpan::fast("fast_test");
        assert_eq!(span.threshold_ms, 8);
    }

    #[test]
    fn test_span_timing() {
        let span = ProfileSpan::always("timing_test");
        sleep(Duration::from_millis(5));
        let elapsed = span.start.elapsed();
        assert!(elapsed >= Duration::from_millis(4));
    }
}
