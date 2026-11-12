//! Deskling for the desktop. The companion, its care and the tray menu all live in the window's page
//! (web/app.js); this side only sets up the plugins, keeps the app out of the Dock and the taskbar, stops the
//! window from being closed (it hides instead; Quit is in the tray menu), and sends the page a "tick" every
//! 15 seconds, which arrives even while the window is hidden and its own timers are slowed down.
use std::{thread, time::Duration};
use tauri::{Emitter, WindowEvent};

/// An error from the page, written where it can be seen.
#[tauri::command]
fn log(msg: String) {
    eprintln!("deskling: {msg}");
}

#[tauri::command]
fn quit(app: tauri::AppHandle) {
    app.exit(0);
}

pub fn run() {
    tauri::Builder::default()
        // a second launch (or a deskling:// link opened while running) goes to this one instead
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            let _ = app.emit("second-instance", ());
        }))
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_store::Builder::default().build())
        .invoke_handler(tauri::generate_handler![log, quit])
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .setup(|app| {
            #[cfg(target_os = "macos")]
            app.set_activation_policy(tauri::ActivationPolicy::Accessory);
            // Windows and Linux learn the deskling:// links at run time (macOS from the bundle)
            #[cfg(any(windows, target_os = "linux"))]
            {
                use tauri_plugin_deep_link::DeepLinkExt;
                let _ = app.deep_link().register_all();
            }
            let handle = app.handle().clone();
            thread::spawn(move || loop {
                thread::sleep(Duration::from_secs(15));
                let _ = handle.emit("tick", ());
            });
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("Deskling couldn't start");
}
