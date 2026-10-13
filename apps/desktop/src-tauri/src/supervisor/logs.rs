//! Bounded buffer of the server's recent stdout/stderr lines.

use std::collections::VecDeque;

pub const LOG_CAPACITY: usize = 300;
pub const MAX_LINE_CHARS: usize = 2000;
pub const REDACTED: &str = "<redacted>";

/// Replaces every occurrence of `secret` in `line`. An empty secret redacts nothing.
pub fn redact(line: &str, secret: Option<&str>) -> String {
    match secret {
        Some(secret) if !secret.is_empty() => line.replace(secret, REDACTED),
        _ => line.to_string(),
    }
}

#[derive(Debug)]
pub struct LogBuffer {
    lines: VecDeque<String>,
    capacity: usize,
}

impl Default for LogBuffer {
    fn default() -> Self {
        Self::with_capacity(LOG_CAPACITY)
    }
}

impl LogBuffer {
    pub fn with_capacity(capacity: usize) -> Self {
        Self { lines: VecDeque::with_capacity(capacity), capacity }
    }

    /// Stores one line, redacted first and then truncated, dropping the oldest line when full.
    pub fn push(&mut self, line: &str, secret: Option<&str>) {
        if self.capacity == 0 {
            return;
        }
        let mut line = redact(line.trim_end_matches(['\r', '\n']), secret);
        if let Some((cut, _)) = line.char_indices().nth(MAX_LINE_CHARS) {
            line.truncate(cut);
            line.push('…');
        }
        if self.lines.len() == self.capacity {
            self.lines.pop_front();
        }
        self.lines.push_back(line);
    }

    pub fn snapshot(&self) -> Vec<String> {
        self.lines.iter().cloned().collect()
    }

    pub fn len(&self) -> usize {
        self.lines.len()
    }

    pub fn is_empty(&self) -> bool {
        self.lines.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_only_the_newest_lines() {
        let mut logs = LogBuffer::with_capacity(3);
        for i in 0..5 {
            logs.push(&format!("line {i}"), None);
        }
        assert_eq!(logs.snapshot(), vec!["line 2", "line 3", "line 4"]);
    }

    #[test]
    fn default_capacity_is_300() {
        let mut logs = LogBuffer::default();
        for i in 0..350 {
            logs.push(&i.to_string(), None);
        }
        assert_eq!(logs.len(), 300);
        assert_eq!(logs.snapshot().first().map(String::as_str), Some("50"));
    }

    #[test]
    fn redacts_every_occurrence_of_the_token() {
        let mut logs = LogBuffer::default();
        logs.push("token=abc123 again abc123\r\n", Some("abc123"));
        assert_eq!(logs.snapshot(), vec!["token=<redacted> again <redacted>"]);
    }

    #[test]
    fn empty_or_missing_secret_redacts_nothing() {
        assert_eq!(redact("abc", Some("")), "abc");
        assert_eq!(redact("abc", None), "abc");
    }

    #[test]
    fn redacts_before_truncating() {
        let secret = "s".repeat(40);
        let line = format!("{}{secret}", "x".repeat(MAX_LINE_CHARS - 5));
        let mut logs = LogBuffer::default();
        logs.push(&line, Some(&secret));
        let stored = &logs.snapshot()[0];
        assert!(!stored.contains("ssssssssss"));
        assert!(stored.ends_with('…'));
    }

    #[test]
    fn zero_capacity_stores_nothing() {
        let mut logs = LogBuffer::with_capacity(0);
        logs.push("x", None);
        assert!(logs.is_empty());
    }
}
