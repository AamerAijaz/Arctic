//! Server lifecycle as seen by the UI.

use serde::Serialize;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "state", rename_all = "snake_case")]
pub enum ServerState {
    Stopped,
    Starting,
    Ready { version: String },
    Failed { reason: String },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    Start,
    Ready { version: String },
    Fail { reason: String },
    Stop,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InvalidTransition {
    pub from: ServerState,
    pub event: Event,
}

impl ServerState {
    pub fn is_ready(&self) -> bool {
        matches!(self, ServerState::Ready { .. })
    }

    /// Stopped/Failed -> Starting -> Ready; Starting/Ready -> Failed; anything -> Stopped.
    pub fn next(&self, event: Event) -> Result<ServerState, InvalidTransition> {
        use ServerState as S;
        match (self, event) {
            (S::Stopped | S::Failed { .. }, Event::Start) => Ok(S::Starting),
            (S::Starting, Event::Ready { version }) => Ok(S::Ready { version }),
            (S::Starting | S::Ready { .. }, Event::Fail { reason }) => Ok(S::Failed { reason }),
            (_, Event::Stop) => Ok(S::Stopped),
            (from, event) => Err(InvalidTransition { from: from.clone(), event }),
        }
    }
}

/// What `get_server_state` returns and `server-state` carries. `seq` increases with every
/// transition so the UI can drop an older snapshot that arrives after a newer event.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ServerStatus {
    pub seq: u64,
    #[serde(flatten)]
    pub state: ServerState,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ready() -> ServerState {
        ServerState::Ready { version: "1".into() }
    }

    fn failed() -> ServerState {
        ServerState::Failed { reason: "r".into() }
    }

    fn fail() -> Event {
        Event::Fail { reason: "r".into() }
    }

    #[test]
    fn happy_path() {
        let s = ServerState::Stopped.next(Event::Start).unwrap();
        assert_eq!(s, ServerState::Starting);
        let s = s.next(Event::Ready { version: "1".into() }).unwrap();
        assert_eq!(s, ready());
        assert_eq!(s.next(Event::Stop).unwrap(), ServerState::Stopped);
    }

    #[test]
    fn can_retry_after_failure() {
        assert_eq!(ServerState::Starting.next(fail()).unwrap(), failed());
        assert_eq!(failed().next(Event::Start).unwrap(), ServerState::Starting);
    }

    #[test]
    fn ready_server_can_crash() {
        assert_eq!(ready().next(fail()).unwrap(), failed());
    }

    #[test]
    fn stop_is_always_allowed() {
        for s in [ServerState::Stopped, ServerState::Starting, ready(), failed()] {
            assert_eq!(s.next(Event::Stop).unwrap(), ServerState::Stopped);
        }
    }

    #[test]
    fn rejects_invalid_transitions() {
        assert!(ServerState::Starting.next(Event::Start).is_err());
        assert!(ready().next(Event::Start).is_err());
        assert!(ServerState::Stopped.next(Event::Ready { version: "1".into() }).is_err());
        assert!(failed().next(Event::Ready { version: "1".into() }).is_err());
        assert!(ServerState::Stopped.next(fail()).is_err());
        assert!(failed().next(fail()).is_err());
    }

    #[test]
    fn serializes_tagged_with_seq() {
        let json = serde_json::to_value(ServerStatus { seq: 3, state: failed() }).unwrap();
        assert_eq!(json, serde_json::json!({"seq": 3, "state": "failed", "reason": "r"}));
        let json = serde_json::to_value(ServerStatus { seq: 0, state: ServerState::Stopped }).unwrap();
        assert_eq!(json, serde_json::json!({"seq": 0, "state": "stopped"}));
    }
}
