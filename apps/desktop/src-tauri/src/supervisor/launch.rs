//! Where the server comes from, and the per-launch port and token.

use std::fmt;
use std::io;
use std::net::{Ipv4Addr, TcpListener};
use std::path::{Path, PathBuf};
use std::time::Duration;

use super::java;

pub const STARTUP_TIMEOUT: Duration = Duration::from_secs(30);
pub const TOKEN_BYTES: usize = 32;

/// Asks the OS for a free port on 127.0.0.1. The port is released before returning, so another
/// process could take it in between; the health check then fails and a retry picks a new one.
pub fn free_port() -> io::Result<u16> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))?;
    Ok(listener.local_addr()?.port())
}

/// 32 random bytes from the OS CSPRNG as 64 lowercase hex characters.
pub fn generate_token() -> io::Result<String> {
    let mut bytes = [0u8; TOKEN_BYTES];
    getrandom::fill(&mut bytes).map_err(|e| io::Error::other(e.to_string()))?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchConfig {
    pub java: PathBuf,
    pub jar: PathBuf,
    /// Off only in tests that exercise the server's parent-PID watchdog on its own.
    pub use_job_object: bool,
    pub startup_timeout: Duration,
}

impl LaunchConfig {
    pub fn new(java: PathBuf, jar: PathBuf) -> Self {
        Self { java, jar, use_job_object: true, startup_timeout: STARTUP_TIMEOUT }
    }
}

#[derive(Clone, PartialEq, Eq)]
pub enum ServerSource {
    /// `ARCTIC_SERVER_URL` is set: use an already running server (e.g. the stub); spawn nothing.
    External { url: String, token: Option<String> },
    Spawn(LaunchConfig),
    /// The server cannot be launched; the reason is shown to the user.
    Unavailable(String),
}

impl fmt::Debug for ServerSource {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ServerSource::External { url, token } => f
                .debug_struct("External")
                .field("url", url)
                .field("token", &token.as_ref().map(|_| "<redacted>"))
                .finish(),
            ServerSource::Spawn(config) => f.debug_tuple("Spawn").field(config).finish(),
            ServerSource::Unavailable(reason) => f.debug_tuple("Unavailable").field(reason).finish(),
        }
    }
}

impl fmt::Display for ServerSource {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ServerSource::External { url, token } => write!(
                f,
                "external server {url} (session token {})",
                if token.is_some() { "set" } else { "not set" }
            ),
            ServerSource::Spawn(c) => write!(f, "spawn {} -jar {}", c.java.display(), c.jar.display()),
            ServerSource::Unavailable(reason) => write!(f, "server unavailable: {reason}"),
        }
    }
}

/// Picks the Spring Boot jar from file names in `build/libs`, ignoring Gradle's `-plain` jar.
pub fn pick_server_jar(names: &[String]) -> Option<String> {
    let mut jars: Vec<&String> = names
        .iter()
        .filter(|n| n.starts_with("local-app-") && n.ends_with(".jar") && !n.ends_with("-plain.jar"))
        .collect();
    jars.sort();
    jars.last().map(|n| n.to_string())
}

/// `server/local-app/build/libs` in this repo; only meaningful for dev builds.
pub fn dev_jar_dir() -> PathBuf {
    let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    for _ in 0..3 {
        dir.pop();
    }
    dir.join("server").join("local-app").join("build").join("libs")
}

fn find_jar_in(dir: &Path) -> Option<PathBuf> {
    let names: Vec<String> = std::fs::read_dir(dir)
        .ok()?
        .filter_map(Result::ok)
        .filter_map(|e| e.file_name().into_string().ok())
        .collect();
    pick_server_jar(&names).map(|name| dir.join(name))
}

/// Inputs to [`resolve`], separated from the process environment for testing.
pub struct Discovery<'a> {
    pub env: &'a dyn Fn(&str) -> Option<String>,
    pub java_roots: Vec<PathBuf>,
    pub dev_jar_dir: Option<PathBuf>,
}

impl ServerSource {
    pub fn from_env() -> Self {
        let env = |key: &str| std::env::var(key).ok();
        resolve(&Discovery {
            env: &env,
            java_roots: java::default_roots(std::env::var_os("USERPROFILE").map(PathBuf::from).as_deref()),
            dev_jar_dir: cfg!(debug_assertions).then(dev_jar_dir),
        })
    }
}

pub fn resolve(d: &Discovery<'_>) -> ServerSource {
    let var = |key: &str| (d.env)(key).map(|v| v.trim().to_string()).filter(|v| !v.is_empty());

    if let Some(url) = var("ARCTIC_SERVER_URL") {
        return ServerSource::External { url, token: (d.env)("ARCTIC_SERVE_TOKEN").filter(|t| !t.is_empty()) };
    }

    let java = match var("ARCTIC_JAVA") {
        Some(path) => {
            let path = PathBuf::from(path);
            if !path.is_file() {
                return ServerSource::Unavailable(format!("ARCTIC_JAVA points to {}, which does not exist.", path.display()));
            }
            path
        }
        None => match java::discover_java25(&d.java_roots) {
            Some(path) => path,
            None => {
                return ServerSource::Unavailable(
                    "Java 25 was not found. Set ARCTIC_JAVA to the path of a Java 25 java.exe.".into(),
                )
            }
        },
    };

    let jar = match var("ARCTIC_SERVER_JAR") {
        Some(path) => PathBuf::from(path),
        None => match d.dev_jar_dir.as_deref().and_then(find_jar_in) {
            Some(path) => path,
            None => {
                return ServerSource::Unavailable(
                    "The Arctic server jar was not found. Build it with `gradlew :local-app:bootJar` or set ARCTIC_SERVER_JAR."
                        .into(),
                )
            }
        },
    };
    if !jar.is_file() {
        return ServerSource::Unavailable(format!("The server jar {} does not exist.", jar.display()));
    }

    ServerSource::Spawn(LaunchConfig::new(java, jar))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[test]
    fn free_port_is_bindable() {
        let port = free_port().unwrap();
        assert_ne!(port, 0);
        TcpListener::bind((Ipv4Addr::LOCALHOST, port)).expect("port should be free again");
    }

    #[test]
    fn tokens_are_64_hex_chars_and_distinct() {
        let a = generate_token().unwrap();
        let b = generate_token().unwrap();
        assert_eq!(a.len(), TOKEN_BYTES * 2);
        assert!(a.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)));
        assert_ne!(a, b);
    }

    #[test]
    fn picks_boot_jar_not_plain_jar() {
        let names = ["local-app-0.1.0-SNAPSHOT-plain.jar", "local-app-0.1.0-SNAPSHOT.jar", "other.jar"]
            .map(String::from);
        assert_eq!(pick_server_jar(&names).as_deref(), Some("local-app-0.1.0-SNAPSHOT.jar"));
        assert_eq!(pick_server_jar(&["local-app-1-plain.jar".to_string()]), None);
    }

    fn resolve_with(vars: &[(&str, &str)], dev_jar_dir: Option<PathBuf>) -> ServerSource {
        let vars: HashMap<String, String> = vars.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        let env = move |key: &str| vars.get(key).cloned();
        resolve(&Discovery { env: &env, java_roots: Vec::new(), dev_jar_dir })
    }

    #[test]
    fn server_url_means_external() {
        let source = resolve_with(&[("ARCTIC_SERVER_URL", "http://127.0.0.1:47100"), ("ARCTIC_SERVE_TOKEN", "t")], None);
        assert_eq!(source, ServerSource::External { url: "http://127.0.0.1:47100".into(), token: Some("t".into()) });
        assert!(!format!("{source:?}").contains("\"t\""));
    }

    #[test]
    fn missing_java_is_unavailable() {
        let ServerSource::Unavailable(reason) = resolve_with(&[], None) else { panic!("expected unavailable") };
        assert!(reason.contains("Java 25"));
    }

    #[test]
    fn explicit_paths_must_exist() {
        let exe = std::env::current_exe().unwrap();
        let exe = exe.to_str().unwrap();
        let source = resolve_with(&[("ARCTIC_JAVA", exe), ("ARCTIC_SERVER_JAR", "Z:\\nope\\x.jar")], None);
        assert!(matches!(source, ServerSource::Unavailable(r) if r.contains("x.jar")));

        let source = resolve_with(&[("ARCTIC_JAVA", exe), ("ARCTIC_SERVER_JAR", exe)], None);
        assert_eq!(source, ServerSource::Spawn(LaunchConfig::new(exe.into(), exe.into())));
    }
}
