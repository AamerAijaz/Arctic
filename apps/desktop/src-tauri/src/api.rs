//! Client for the local Arctic server API (server/local-app/api/openapi.yaml).
//!
//! The bearer token lives only here; it is never serialized, logged, or put in an error.

use std::fmt;
use std::time::Duration;

use reqwest::{Method, StatusCode};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use url::Url;

pub const DEFAULT_SERVER_URL: &str = "http://127.0.0.1:47100";

// ---- Schemas -------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum HealthStatus {
    Ready,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Health {
    pub status: HealthStatus,
    pub version: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ProbeResult {
    Ok,
    NotConfigured,
    RejectedToken,
    RestDisabled,
    NetworkError,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PolarionProbe {
    pub result: ProbeResult,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupStatus {
    pub polarion_configured: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub polarion_url: Option<String>,
    pub polarion: PolarionProbe,
    pub model_configured: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model_base_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
}

/// Write-only: deliberately no `Debug` so the PAT cannot end up in a log line.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PolarionSettings {
    pub url: String,
    pub token: String,
}

/// Write-only: deliberately no `Debug` so the API key cannot end up in a log line.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelSettings {
    pub base_url: String,
    pub model: String,
    pub api_key: String,
}

/// The `Error` schema, as returned by the server for 400/401.
#[derive(Debug, Deserialize)]
struct ServerError {
    #[allow(dead_code)]
    kind: String,
    message: String,
}

// ---- Errors returned to the UI -------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorKind {
    ServerUnreachable,
    Unauthorized,
    BadRequest,
    ServerError,
    InvalidResponse,
    Config,
    ServerNotReady,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ApiError {
    pub kind: ErrorKind,
    pub message: String,
}

impl ApiError {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        Self { kind, message: message.into() }
    }
}

impl fmt::Display for ApiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:?}: {}", self.kind, self.message)
    }
}

impl std::error::Error for ApiError {}

/// Maps a non-success HTTP status to a UI error. `body` is the raw response body.
pub fn error_from_status(status: StatusCode, body: &str) -> ApiError {
    let server_message = serde_json::from_str::<ServerError>(body).ok().map(|e| e.message);
    match status {
        StatusCode::UNAUTHORIZED => ApiError::new(
            ErrorKind::Unauthorized,
            "The Arctic server rejected the session token (check ARCTIC_SERVE_TOKEN).",
        ),
        StatusCode::BAD_REQUEST => ApiError::new(
            ErrorKind::BadRequest,
            server_message.unwrap_or_else(|| "The Arctic server rejected the input.".into()),
        ),
        other => ApiError::new(
            ErrorKind::ServerError,
            format!("The Arctic server returned HTTP {}.", other.as_u16()),
        ),
    }
}

/// Maps a transport-level failure. Only the server origin is reported, never headers or paths.
pub fn error_from_transport(err: &reqwest::Error, base: &Url) -> ApiError {
    let origin = base.origin().ascii_serialization();
    if err.is_decode() {
        ApiError::new(ErrorKind::InvalidResponse, format!("Unexpected response from the Arctic server at {origin}."))
    } else if err.is_timeout() {
        ApiError::new(ErrorKind::ServerUnreachable, format!("Timed out talking to the Arctic server at {origin}."))
    } else {
        ApiError::new(ErrorKind::ServerUnreachable, format!("Could not connect to the Arctic server at {origin}."))
    }
}

// ---- Client --------------------------------------------------------------

/// Parses a base URL and makes sure later joins append to its path rather than replace it.
pub fn parse_base_url(raw: &str) -> Result<Url, ApiError> {
    let mut url = Url::parse(raw.trim())
        .map_err(|_| ApiError::new(ErrorKind::Config, "ARCTIC_SERVER_URL is not a valid URL."))?;
    if url.scheme() != "http" && url.scheme() != "https" {
        return Err(ApiError::new(ErrorKind::Config, "ARCTIC_SERVER_URL must use http or https."));
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err(ApiError::new(ErrorKind::Config, "ARCTIC_SERVER_URL must not contain credentials."));
    }
    url.set_query(None);
    url.set_fragment(None);
    if !url.path().ends_with('/') {
        let path = format!("{}/", url.path());
        url.set_path(&path);
    }
    Ok(url)
}

pub fn join_endpoint(base: &Url, path: &str) -> Url {
    base.join(path.trim_start_matches('/'))
        .expect("endpoint paths are static and valid")
}

pub struct ApiClient {
    http: reqwest::Client,
    base: Url,
    token: Option<String>,
}

impl fmt::Debug for ApiClient {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ApiClient")
            .field("base", &self.base.as_str())
            .field("token", &self.token.as_ref().map(|_| "<redacted>"))
            .finish()
    }
}

impl ApiClient {
    pub fn new(base_url: &str, token: Option<String>) -> Result<Self, ApiError> {
        let base = parse_base_url(base_url)?;
        let http = reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(3))
            .timeout(Duration::from_secs(60))
            .build()
            .map_err(|_| ApiError::new(ErrorKind::Config, "Could not initialize the HTTP client."))?;
        let token = token.filter(|t| !t.is_empty());
        Ok(Self { http, base, token })
    }

    pub fn base_url(&self) -> &Url {
        &self.base
    }

    pub fn has_token(&self) -> bool {
        self.token.is_some()
    }

    fn request(&self, method: Method, path: &str) -> reqwest::RequestBuilder {
        let builder = self.http.request(method, join_endpoint(&self.base, path));
        match &self.token {
            Some(token) => builder.bearer_auth(token),
            None => builder,
        }
    }

    async fn send(&self, builder: reqwest::RequestBuilder) -> Result<reqwest::Response, ApiError> {
        let response = builder.send().await.map_err(|e| error_from_transport(&e, &self.base))?;
        let status = response.status();
        if status.is_success() {
            return Ok(response);
        }
        let body = response.text().await.unwrap_or_default();
        Err(error_from_status(status, &body))
    }

    async fn json<T: DeserializeOwned>(&self, builder: reqwest::RequestBuilder) -> Result<T, ApiError> {
        self.send(builder)
            .await?
            .json::<T>()
            .await
            .map_err(|e| error_from_transport(&e, &self.base))
    }

    pub async fn get_health(&self) -> Result<Health, ApiError> {
        self.json(self.request(Method::GET, "health")).await
    }

    pub async fn get_setup_status(&self) -> Result<SetupStatus, ApiError> {
        self.json(self.request(Method::GET, "setup/status")).await
    }

    pub async fn put_polarion(&self, settings: &PolarionSettings) -> Result<PolarionProbe, ApiError> {
        self.json(self.request(Method::PUT, "setup/polarion").json(settings)).await
    }

    pub async fn put_model(&self, settings: &ModelSettings) -> Result<(), ApiError> {
        self.send(self.request(Method::PUT, "setup/model").json(settings)).await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    #[test]
    fn joins_onto_root() {
        let base = parse_base_url("http://127.0.0.1:47100").unwrap();
        assert_eq!(join_endpoint(&base, "health").as_str(), "http://127.0.0.1:47100/health");
        assert_eq!(join_endpoint(&base, "/setup/status").as_str(), "http://127.0.0.1:47100/setup/status");
    }

    #[test]
    fn joins_onto_base_path_with_or_without_trailing_slash() {
        for raw in ["http://localhost:1/arctic", "http://localhost:1/arctic/"] {
            let base = parse_base_url(raw).unwrap();
            assert_eq!(join_endpoint(&base, "setup/model").as_str(), "http://localhost:1/arctic/setup/model");
        }
    }

    #[test]
    fn rejects_bad_base_urls() {
        assert_eq!(parse_base_url("not a url").unwrap_err().kind, ErrorKind::Config);
        assert_eq!(parse_base_url("ftp://host/").unwrap_err().kind, ErrorKind::Config);
        assert_eq!(parse_base_url("http://user:pw@host/").unwrap_err().kind, ErrorKind::Config);
    }

    #[test]
    fn maps_401_to_unauthorized() {
        let err = error_from_status(StatusCode::UNAUTHORIZED, r#"{"kind":"unauthorized","message":"x"}"#);
        assert_eq!(err.kind, ErrorKind::Unauthorized);
    }

    #[test]
    fn maps_400_to_bad_request_with_server_message() {
        let err = error_from_status(StatusCode::BAD_REQUEST, r#"{"kind":"bad_request","message":"url is required"}"#);
        assert_eq!(err, ApiError::new(ErrorKind::BadRequest, "url is required"));
        assert_eq!(error_from_status(StatusCode::BAD_REQUEST, "garbage").kind, ErrorKind::BadRequest);
    }

    #[test]
    fn maps_other_statuses_to_server_error() {
        let err = error_from_status(StatusCode::INTERNAL_SERVER_ERROR, "");
        assert_eq!(err.kind, ErrorKind::ServerError);
        assert!(err.message.contains("500"));
    }

    #[test]
    fn error_serializes_as_kind_and_message() {
        let json = serde_json::to_value(ApiError::new(ErrorKind::ServerUnreachable, "m")).unwrap();
        assert_eq!(json, serde_json::json!({"kind": "server_unreachable", "message": "m"}));
    }

    #[test]
    fn schemas_round_trip_camel_case() {
        let status: SetupStatus = serde_json::from_str(
            r#"{"polarionConfigured":true,"polarionUrl":"https://p/polarion/rest/v1",
                "polarion":{"result":"rejected_token","message":"no"},
                "modelConfigured":false}"#,
        )
        .unwrap();
        assert_eq!(status.polarion.result, ProbeResult::RejectedToken);
        assert_eq!(status.model_base_url, None);

        let body = serde_json::to_value(ModelSettings {
            base_url: "b".into(),
            model: "m".into(),
            api_key: "k".into(),
        })
        .unwrap();
        assert_eq!(body, serde_json::json!({"baseUrl": "b", "model": "m", "apiKey": "k"}));
    }

    #[test]
    fn debug_redacts_token() {
        let client = ApiClient::new(DEFAULT_SERVER_URL, Some("super-secret".into())).unwrap();
        assert!(!format!("{client:?}").contains("super-secret"));
    }

    #[tokio::test]
    async fn connection_refused_is_server_unreachable() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        drop(listener);

        let client = ApiClient::new(&format!("http://127.0.0.1:{port}"), Some("secret-token".into())).unwrap();
        let err = client.get_health().await.unwrap_err();
        assert_eq!(err.kind, ErrorKind::ServerUnreachable);
        assert!(!err.message.contains("secret-token"));
    }

    /// Serves exactly one canned JSON response and returns the raw request it received.
    async fn one_shot(status_line: &str, body: &str) -> (String, tokio::task::JoinHandle<String>) {
        let response = format!(
            "HTTP/1.1 {status_line}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        );
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let handle = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buf = vec![0u8; 8192];
            let n = socket.read(&mut buf).await.unwrap();
            socket.write_all(response.as_bytes()).await.unwrap();
            socket.shutdown().await.ok();
            String::from_utf8_lossy(&buf[..n]).into_owned()
        });
        (base, handle)
    }

    #[tokio::test]
    async fn sends_bearer_and_maps_401() {
        let (base, server) = one_shot(
            "401 Unauthorized",
            r#"{"kind":"unauthorized","message":"Missing or wrong bearer token."}"#,
        )
        .await;
        let client = ApiClient::new(&base, Some("tok-123".into())).unwrap();

        let err = client.get_health().await.unwrap_err();
        assert_eq!(err.kind, ErrorKind::Unauthorized);
        assert!(!err.message.contains("tok-123"));

        let request = server.await.unwrap().to_ascii_lowercase();
        assert!(request.starts_with("get /health "));
        assert!(request.contains("authorization: bearer tok-123"));
    }

    #[tokio::test]
    async fn parses_health() {
        let (base, _server) = one_shot("200 OK", r#"{"status":"ready","version":"1.2.3"}"#).await;
        let client = ApiClient::new(&base, Some("t".into())).unwrap();
        let health = client.get_health().await.unwrap();
        assert_eq!(health, Health { status: HealthStatus::Ready, version: "1.2.3".into() });
    }
}
