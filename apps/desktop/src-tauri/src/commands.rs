use tauri::State;

use crate::api::{ApiError, Health, ModelSettings, PolarionProbe, PolarionSettings, SetupStatus};
use crate::supervisor::{ServerStatus, Supervisor};

#[tauri::command]
pub fn get_server_state(supervisor: State<'_, Supervisor>) -> ServerStatus {
    supervisor.status()
}

#[tauri::command]
pub fn get_server_logs(supervisor: State<'_, Supervisor>) -> Vec<String> {
    supervisor.logs()
}

/// Returns immediately; progress arrives as `server-state` events.
#[tauri::command]
pub fn restart_server(supervisor: State<'_, Supervisor>) {
    let supervisor = supervisor.inner().clone();
    tauri::async_runtime::spawn(async move { supervisor.restart().await });
}

#[tauri::command]
pub async fn get_health(supervisor: State<'_, Supervisor>) -> Result<Health, ApiError> {
    supervisor.client()?.get_health().await
}

#[tauri::command]
pub async fn get_setup_status(supervisor: State<'_, Supervisor>) -> Result<SetupStatus, ApiError> {
    supervisor.client()?.get_setup_status().await
}

#[tauri::command]
pub async fn put_polarion(
    supervisor: State<'_, Supervisor>,
    url: String,
    token: String,
) -> Result<PolarionProbe, ApiError> {
    supervisor.client()?.put_polarion(&PolarionSettings { url, token }).await
}

#[tauri::command(rename_all = "camelCase")]
pub async fn put_model(
    supervisor: State<'_, Supervisor>,
    base_url: String,
    model: String,
    api_key: String,
) -> Result<(), ApiError> {
    supervisor
        .client()?
        .put_model(&ModelSettings { base_url, model, api_key })
        .await
}
