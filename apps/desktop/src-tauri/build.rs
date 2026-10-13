fn main() {
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(
        tauri_build::AppManifest::new().commands(&[
            "get_server_state",
            "get_server_logs",
            "restart_server",
            "get_health",
            "get_setup_status",
            "put_polarion",
            "put_model",
        ]),
    ))
    .expect("failed to run tauri-build");
}
