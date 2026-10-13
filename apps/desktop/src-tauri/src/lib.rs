pub mod api;
mod commands;
pub mod supervisor;

use tauri::{Emitter, Manager, RunEvent};

use supervisor::{ServerSource, Supervisor};

pub const SERVER_STATE_EVENT: &str = "server-state";

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let source = ServerSource::from_env();
    eprintln!("arctic: {source}");

    let app = tauri::Builder::default()
        // Must be the first plugin: a second launch exits here, before setup() could start a server.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .setup(move |app| {
            let handle = app.handle().clone();
            let supervisor = Supervisor::new(
                source,
                Box::new(move |status| {
                    let _ = handle.emit(SERVER_STATE_EVENT, status);
                }),
            );
            app.manage(supervisor.clone());
            tauri::async_runtime::spawn(async move { supervisor.start().await });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_server_state,
            commands::get_server_logs,
            commands::restart_server,
            commands::get_health,
            commands::get_setup_status,
            commands::put_polarion,
            commands::put_model,
        ])
        .build(tauri::generate_context!())
        .expect("error while building Arctic");

    app.run(|app, event| {
        if let RunEvent::Exit = event {
            if let Some(supervisor) = app.try_state::<Supervisor>() {
                let supervisor = supervisor.inner().clone();
                tauri::async_runtime::block_on(supervisor.shutdown());
            }
        }
    });
}
