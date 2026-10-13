//! Starts, watches, and stops the local Arctic server, and hands out an API client while it is ready.

pub mod java;
pub mod job;
pub mod launch;
pub mod logs;
pub mod state;

use std::process::Stdio;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use tokio::io::{AsyncBufReadExt, AsyncRead, BufReader};
use tokio::sync::oneshot;
use tokio::time::Instant;

use crate::api::{ApiClient, ApiError, ErrorKind};
pub use job::JobObject;
pub use launch::{LaunchConfig, ServerSource};
use logs::LogBuffer;
pub use state::{Event, ServerState, ServerStatus};

pub const HEALTH_POLL_INTERVAL: Duration = Duration::from_millis(250);
pub const HEALTH_REQUEST_TIMEOUT: Duration = Duration::from_secs(2);
pub const STOP_TIMEOUT: Duration = Duration::from_secs(3);

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub type Listener = Box<dyn Fn(&ServerStatus) + Send + Sync>;

/// Cheap to clone; all clones share one server.
#[derive(Clone)]
pub struct Supervisor {
    inner: Arc<Inner>,
}

struct Inner {
    source: ServerSource,
    listener: Listener,
    shared: Mutex<Shared>,
    logs: Mutex<LogBuffer>,
    /// Kept for the app's lifetime; closing it kills the server (see `shutdown`).
    job: Mutex<Option<JobObject>>,
}

struct Shared {
    status: ServerStatus,
    /// Bumped on every start and stop so tasks of an older run cannot change the state.
    generation: u64,
    client: Option<Arc<ApiClient>>,
    child: Option<ChildHandle>,
}

struct ChildHandle {
    pid: Option<u32>,
    port: u16,
    kill: oneshot::Sender<()>,
    done: oneshot::Receiver<()>,
}

enum Wait {
    Ready(String),
    Failed(String),
    /// The run was stopped or failed elsewhere (child exited, stop requested).
    Superseded,
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

impl Supervisor {
    pub fn new(source: ServerSource, listener: Listener) -> Self {
        let mut logs = LogBuffer::default();
        let job = match &source {
            ServerSource::Spawn(config) if config.use_job_object => match JobObject::kill_on_close() {
                Ok(job) => Some(job),
                Err(err) => {
                    logs.push(&format!("[arctic] could not create a job object: {err}"), None);
                    None
                }
            },
            _ => None,
        };
        Self {
            inner: Arc::new(Inner {
                source,
                listener,
                shared: Mutex::new(Shared {
                    status: ServerStatus { seq: 0, state: ServerState::Stopped },
                    generation: 0,
                    client: None,
                    child: None,
                }),
                logs: Mutex::new(logs),
                job: Mutex::new(job),
            }),
        }
    }

    pub fn source(&self) -> &ServerSource {
        &self.inner.source
    }

    pub fn status(&self) -> ServerStatus {
        lock(&self.inner.shared).status.clone()
    }

    pub fn logs(&self) -> Vec<String> {
        lock(&self.inner.logs).snapshot()
    }

    /// Port and PID of the spawned server, if one is running.
    pub fn child_info(&self) -> Option<(u16, Option<u32>)> {
        lock(&self.inner.shared).child.as_ref().map(|c| (c.port, c.pid))
    }

    /// The API client for the current run, or `server_not_ready`.
    pub fn client(&self) -> Result<Arc<ApiClient>, ApiError> {
        let shared = lock(&self.inner.shared);
        match (&shared.status.state, &shared.client) {
            (ServerState::Ready { .. }, Some(client)) => Ok(client.clone()),
            (state, _) => Err(ApiError::new(
                ErrorKind::ServerNotReady,
                match state {
                    ServerState::Starting => "The Arctic server is still starting.".to_string(),
                    ServerState::Failed { reason } => format!("The Arctic server is not running: {reason}"),
                    _ => "The Arctic server is not running.".to_string(),
                },
            )),
        }
    }

    /// Starts the server and resolves once it is ready or has failed. A no-op unless stopped or failed.
    pub async fn start(&self) {
        let Some(generation) = self.inner.begin_start() else { return };
        match self.inner.source.clone() {
            ServerSource::Unavailable(reason) => {
                self.inner.log(&format!("[arctic] {reason}"));
                self.inner.fail(generation, reason).await;
            }
            ServerSource::External { url, token } => self.start_external(generation, &url, token).await,
            ServerSource::Spawn(config) => self.start_child(generation, &config).await,
        }
    }

    /// Kills the server if it runs and waits up to 3 s for it to exit.
    pub async fn stop(&self) {
        let child = self.inner.apply(None, Event::Stop, true);
        if let Some(child) = child {
            self.inner.log("[arctic] stopping server");
            kill_and_wait(child).await;
        }
    }

    pub async fn restart(&self) {
        self.stop().await;
        self.start().await;
    }

    /// Stops the server and closes the job handle. Call once, when the app exits.
    pub async fn shutdown(&self) {
        self.stop().await;
        drop(lock(&self.inner.job).take());
    }

    async fn start_external(&self, generation: u64, url: &str, token: Option<String>) {
        let client = match ApiClient::new(url, token) {
            Ok(client) => Arc::new(client),
            Err(err) => return self.inner.fail(generation, err.message).await,
        };
        self.inner.log(&format!("[arctic] using the server at {} (ARCTIC_SERVER_URL)", client.base_url()));
        self.finish_start(generation, client, launch::STARTUP_TIMEOUT).await;
    }

    async fn start_child(&self, generation: u64, config: &LaunchConfig) {
        let (port, token) = match launch::free_port().and_then(|p| Ok((p, launch::generate_token()?))) {
            Ok(pair) => pair,
            Err(err) => return self.inner.fail(generation, format!("Could not prepare the server launch: {err}")).await,
        };

        let mut command = tokio::process::Command::new(&config.java);
        command
            .arg("--enable-native-access=ALL-UNNAMED")
            .arg("-jar")
            .arg(&config.jar)
            .args(["serve", "--port", &port.to_string(), "--parent-pid", &std::process::id().to_string()])
            .env("ARCTIC_SERVE_TOKEN", &token)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        #[cfg(windows)]
        command.creation_flags(CREATE_NO_WINDOW);

        self.inner.log(&format!(
            "[arctic] starting {} -jar {} serve --port {port}",
            config.java.display(),
            config.jar.display()
        ));
        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(err) => {
                let reason = format!("Could not start {}: {err}", config.java.display());
                self.inner.log(&format!("[arctic] {reason}"));
                return self.inner.fail(generation, reason).await;
            }
        };

        #[cfg(windows)]
        if let (Some(job), Some(handle)) = (lock(&self.inner.job).as_ref(), child.raw_handle()) {
            if let Err(err) = job.assign(handle) {
                self.inner.log(&format!("[arctic] could not add the server to the job object: {err}"));
            }
        }

        for stream in [child.stdout.take().map(boxed), child.stderr.take().map(boxed)].into_iter().flatten() {
            tokio::spawn(pump_lines(self.inner.clone(), stream, token.clone()));
        }

        let (kill_tx, kill_rx) = oneshot::channel();
        let (done_tx, done_rx) = oneshot::channel();
        let handle = ChildHandle { pid: child.id(), port, kill: kill_tx, done: done_rx };
        tokio::spawn(watch_child(self.inner.clone(), generation, child, kill_rx, done_tx));

        if let Some(stale) = self.inner.attach_child(generation, handle) {
            // Stopped while spawning: this child belongs to nobody.
            return kill_and_wait(stale).await;
        }

        let client = match ApiClient::new(&format!("http://127.0.0.1:{port}"), Some(token)) {
            Ok(client) => Arc::new(client),
            Err(err) => return self.inner.fail(generation, err.message).await,
        };
        self.finish_start(generation, client, config.startup_timeout).await;
    }

    async fn finish_start(&self, generation: u64, client: Arc<ApiClient>, timeout: Duration) {
        match self.wait_until_healthy(generation, &client, timeout).await {
            Wait::Ready(version) => {
                self.inner.log(&format!("[arctic] server ready (version {version})"));
                self.inner.ready(generation, client, version);
            }
            Wait::Failed(reason) => {
                self.inner.log(&format!("[arctic] {reason}"));
                self.inner.fail(generation, reason).await;
            }
            Wait::Superseded => {}
        }
    }

    async fn wait_until_healthy(&self, generation: u64, client: &ApiClient, timeout: Duration) -> Wait {
        let deadline = Instant::now() + timeout;
        loop {
            if !self.inner.is_starting(generation) {
                return Wait::Superseded;
            }
            match tokio::time::timeout(HEALTH_REQUEST_TIMEOUT, client.get_health()).await {
                Ok(Ok(health)) => return Wait::Ready(health.version),
                Ok(Err(err)) if err.kind == ErrorKind::Unauthorized => return Wait::Failed(err.message),
                _ => {}
            }
            if Instant::now() >= deadline {
                return Wait::Failed(format!("The server did not become ready within {} s.", timeout.as_secs()));
            }
            tokio::time::sleep(HEALTH_POLL_INTERVAL).await;
        }
    }
}

impl Inner {
    fn emit(&self, status: Option<ServerStatus>) {
        if let Some(status) = status {
            (self.listener)(&status);
        }
    }

    fn log(&self, line: &str) {
        lock(&self.logs).push(line, None);
    }

    /// Applies `event` if `generation` (when given) is still current. Returns the detached child,
    /// which the caller must kill. `bump` starts a new generation.
    fn apply(&self, generation: Option<u64>, event: Event, bump: bool) -> Option<ChildHandle> {
        let (status, child) = {
            let mut shared = lock(&self.shared);
            if generation.is_some_and(|g| g != shared.generation) {
                return None;
            }
            let Ok(next) = shared.status.state.next(event) else { return None };
            if bump {
                shared.generation += 1;
            }
            if !next.is_ready() {
                shared.client = None;
            }
            let child = if matches!(next, ServerState::Stopped | ServerState::Failed { .. }) {
                shared.child.take()
            } else {
                None
            };
            let changed = next != shared.status.state;
            if changed {
                shared.status = ServerStatus { seq: shared.status.seq + 1, state: next };
            }
            (changed.then(|| shared.status.clone()), child)
        };
        self.emit(status);
        child
    }

    fn begin_start(&self) -> Option<u64> {
        let (status, generation) = {
            let mut shared = lock(&self.shared);
            let next = shared.status.state.next(Event::Start).ok()?;
            shared.generation += 1;
            shared.status = ServerStatus { seq: shared.status.seq + 1, state: next };
            (shared.status.clone(), shared.generation)
        };
        self.emit(Some(status));
        Some(generation)
    }

    fn is_starting(&self, generation: u64) -> bool {
        let shared = lock(&self.shared);
        shared.generation == generation && shared.status.state == ServerState::Starting
    }

    /// Stores the child for this run, or hands it back if the run is no longer current.
    fn attach_child(&self, generation: u64, child: ChildHandle) -> Option<ChildHandle> {
        let mut shared = lock(&self.shared);
        if shared.generation == generation && shared.status.state == ServerState::Starting {
            shared.child = Some(child);
            None
        } else {
            Some(child)
        }
    }

    fn ready(&self, generation: u64, client: Arc<ApiClient>, version: String) {
        let status = {
            let mut shared = lock(&self.shared);
            if shared.generation != generation {
                return;
            }
            let Ok(next) = shared.status.state.next(Event::Ready { version }) else { return };
            shared.client = Some(client);
            shared.status = ServerStatus { seq: shared.status.seq + 1, state: next };
            shared.status.clone()
        };
        self.emit(Some(status));
    }

    async fn fail(&self, generation: u64, reason: String) {
        if let Some(child) = self.apply(Some(generation), Event::Fail { reason }, false) {
            kill_and_wait(child).await;
        }
    }

    fn child_exited(&self, generation: u64, code: Option<i32>) {
        let was_ready = lock(&self.shared).status.state.is_ready();
        let code = code.map_or_else(|| "unknown".to_string(), |c| c.to_string());
        let reason = if was_ready {
            format!("The server stopped unexpectedly (exit code {code}).")
        } else {
            format!("The server exited with code {code} before it was ready.")
        };
        self.log(&format!("[arctic] {reason}"));
        // The child is gone already; dropping its handle is enough.
        drop(self.apply(Some(generation), Event::Fail { reason }, false));
    }
}

fn boxed<R: AsyncRead + Send + Unpin + 'static>(stream: R) -> Box<dyn AsyncRead + Send + Unpin> {
    Box::new(stream)
}

async fn pump_lines(inner: Arc<Inner>, stream: Box<dyn AsyncRead + Send + Unpin>, secret: String) {
    let mut reader = BufReader::new(stream);
    let mut buf = Vec::new();
    loop {
        buf.clear();
        match reader.read_until(b'\n', &mut buf).await {
            Ok(0) | Err(_) => break,
            Ok(_) => lock(&inner.logs).push(&String::from_utf8_lossy(&buf), Some(&secret)),
        }
    }
}

/// Owns the child: reports an unexpected exit, or kills it when asked.
async fn watch_child(
    inner: Arc<Inner>,
    generation: u64,
    mut child: tokio::process::Child,
    kill: oneshot::Receiver<()>,
    done: oneshot::Sender<()>,
) {
    tokio::select! {
        status = child.wait() => {
            inner.child_exited(generation, status.ok().and_then(|s| s.code()));
        }
        _ = kill => {
            let _ = child.start_kill();
            match tokio::time::timeout(STOP_TIMEOUT, child.wait()).await {
                Ok(Ok(status)) => inner.log(&format!(
                    "[arctic] server stopped (exit code {})",
                    status.code().map_or_else(|| "unknown".into(), |c| c.to_string())
                )),
                _ => inner.log("[arctic] server did not exit within 3 s after kill"),
            }
        }
    }
    let _ = done.send(());
}

async fn kill_and_wait(child: ChildHandle) {
    let _ = child.kill.send(());
    let _ = tokio::time::timeout(STOP_TIMEOUT + Duration::from_millis(500), child.done).await;
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn recording(source: ServerSource) -> (Supervisor, Arc<Mutex<Vec<ServerStatus>>>) {
        let seen = Arc::new(Mutex::new(Vec::new()));
        let sink = seen.clone();
        let sup = Supervisor::new(source, Box::new(move |s| sink.lock().unwrap().push(s.clone())));
        (sup, seen)
    }

    #[tokio::test]
    async fn unavailable_source_fails_with_reason() {
        let (sup, seen) = recording(ServerSource::Unavailable("no java".into()));
        sup.start().await;
        let states: Vec<_> = seen.lock().unwrap().iter().map(|s| s.state.clone()).collect();
        assert_eq!(states, vec![ServerState::Starting, ServerState::Failed { reason: "no java".into() }]);
        assert_eq!(sup.client().unwrap_err().kind, ErrorKind::ServerNotReady);
        assert!(sup.logs().iter().any(|l| l.contains("no java")));
    }

    #[tokio::test]
    async fn seq_increases_on_every_transition() {
        let (sup, seen) = recording(ServerSource::Unavailable("x".into()));
        sup.start().await;
        sup.restart().await;
        let seqs: Vec<u64> = seen.lock().unwrap().iter().map(|s| s.seq).collect();
        assert_eq!(seqs, vec![1, 2, 3, 4, 5]);
        assert_eq!(sup.status().seq, 5);
    }

    #[tokio::test]
    async fn external_server_becomes_ready_and_hands_out_client() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let hits = Arc::new(AtomicUsize::new(0));
        let counter = hits.clone();
        tokio::spawn(async move {
            use tokio::io::{AsyncReadExt, AsyncWriteExt};
            loop {
                let (mut socket, _) = listener.accept().await.unwrap();
                counter.fetch_add(1, Ordering::SeqCst);
                let mut buf = [0u8; 4096];
                let _ = socket.read(&mut buf).await;
                let body = r#"{"status":"ready","version":"stub"}"#;
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = socket.write_all(response.as_bytes()).await;
            }
        });

        let (sup, _) = recording(ServerSource::External { url, token: Some("t".into()) });
        assert_eq!(sup.client().unwrap_err().kind, ErrorKind::ServerNotReady);
        sup.start().await;
        assert_eq!(sup.status().state, ServerState::Ready { version: "stub".into() });
        assert_eq!(sup.client().unwrap().get_health().await.unwrap().version, "stub");
        assert!(sup.child_info().is_none());

        sup.stop().await;
        assert_eq!(sup.status().state, ServerState::Stopped);
        assert!(sup.client().is_err());
        assert!(hits.load(Ordering::SeqCst) >= 2);
    }

    #[tokio::test]
    async fn start_retries_after_failure_but_not_while_starting() {
        let (sup, seen) = recording(ServerSource::Unavailable("x".into()));
        sup.start().await;
        sup.start().await;
        assert_eq!(seen.lock().unwrap().len(), 4);

        let (sup, seen) = recording(ServerSource::Unavailable("x".into()));
        sup.inner.begin_start().unwrap();
        assert!(sup.inner.begin_start().is_none());
        sup.start().await;
        assert_eq!(seen.lock().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn spawn_failure_is_reported() {
        let config = LaunchConfig::new("Z:\\does\\not\\exist\\java.exe".into(), "x.jar".into());
        let (sup, _) = recording(ServerSource::Spawn(config));
        sup.start().await;
        let ServerState::Failed { reason } = sup.status().state else { panic!("expected failed") };
        assert!(reason.contains("Could not start"));
    }

    #[cfg(windows)]
    #[tokio::test]
    async fn early_exit_reports_exit_code_and_keeps_logs() {
        // `cmd /C exit 3` stands in for a server that dies during startup.
        let mut config = LaunchConfig::new("cmd.exe".into(), "unused".into());
        config.startup_timeout = Duration::from_secs(10);
        let (sup, _) = recording(ServerSource::Spawn(config));
        sup.start().await;
        let ServerState::Failed { reason } = sup.status().state else { panic!("expected failed") };
        assert!(reason.contains("exited with code"), "{reason}");
        assert!(sup.logs().iter().any(|l| l.contains("[arctic] starting")));
    }

    #[tokio::test]
    async fn health_timeout_fails() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        drop(listener);
        let (sup, _) = recording(ServerSource::External { url: format!("http://127.0.0.1:{port}"), token: None });
        let client = Arc::new(ApiClient::new(&format!("http://127.0.0.1:{port}"), None).unwrap());
        let generation = sup.inner.begin_start().unwrap();
        sup.finish_start(generation, client, Duration::from_millis(600)).await;
        let ServerState::Failed { reason } = sup.status().state else { panic!("expected failed") };
        assert!(reason.contains("did not become ready"), "{reason}");
    }
}
