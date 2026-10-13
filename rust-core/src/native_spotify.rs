//! Native, Spotify-Premium-only audio engine. This is an optional Android
//! librespot backend (like Spotifast's decoder, not a WebView) and deliberately
//! does NOT expose Spotify content to the Audius or YouTube providers.
//! Spotify login requires a user-authorized OAuth token with streaming scope.
//! Important: integration is experimental until tested on a Premium device.

use std::path::PathBuf;
use std::time::Duration;
use std::sync::{Arc, Mutex, OnceLock, mpsc};
use std::thread;

use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JClass, JObject, JString, JValue},
    sys::{jboolean, jint},
};
use librespot_core::{Session, SessionConfig, SpotifyUri, authentication::Credentials};
use librespot_playback::{
    audio_backend::{Sink, SinkError, SinkResult},
    config::{Bitrate, PlayerConfig},
    convert::Converter,
    decoder::AudioPacket,
    mixer::NoOpVolume,
    player::{Player, PlayerEvent},
};

enum EngineCommand {
    Load(String),
    Pause,
    Resume,
    Seek(u32),
    Stop,
    Shutdown,
}

static COMMANDS: OnceLock<Mutex<Option<mpsc::Sender<EngineCommand>>>> = OnceLock::new();

fn commands() -> &'static Mutex<Option<mpsc::Sender<EngineCommand>>> {
    COMMANDS.get_or_init(|| Mutex::new(None))
}

struct AndroidEmitter {
    vm: Arc<JavaVM>,
    output: GlobalRef,
}

impl Clone for AndroidEmitter {
    fn clone(&self) -> Self {
        Self {
            vm: self.vm.clone(),
            output: self.output.clone(),
        }
    }
}

impl AndroidEmitter {
    fn simple(&self, name: &str) -> Result<(), String> {
        let mut env = self.vm.attach_current_thread().map_err(|e| e.to_string())?;
        env.call_method(self.output.as_obj(), name, "()V", &[])
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    fn status(&self, status: &str) {
        if let Ok(mut env) = self.vm.attach_current_thread() {
            if let Ok(text) = env.new_string(status) {
                let obj: JObject = text.into();
                let _ = env.call_method(
                    self.output.as_obj(),
                    "onStatus",
                    "(Ljava/lang/String;)V",
                    &[JValue::Object(&obj)],
                );
            }
        }
    }

    fn event(&self, code: i32, position: i32) {
        if let Ok(mut env) = self.vm.attach_current_thread() {
            let _ = env.call_method(
                self.output.as_obj(), "onEngineEvent", "(II)V",
                &[JValue::Int(code), JValue::Int(position)],
            );
        }
    }

    fn write_pcm(&self, bytes: &[u8]) -> Result<(), String> {
        let mut env = self.vm.attach_current_thread().map_err(|e| e.to_string())?;
        let java_bytes = env.byte_array_from_slice(bytes).map_err(|e| e.to_string())?;
        let object: JObject = java_bytes.into();
        env.call_method(
            self.output.as_obj(),
            "onPcm",
            "([B)V",
            &[JValue::Object(&object)],
        ).map_err(|e| e.to_string())?;
        Ok(())
    }
}

struct AndroidPcmSink {
    emitter: AndroidEmitter,
}

impl Sink for AndroidPcmSink {
    fn start(&mut self) -> SinkResult<()> {
        self.emitter.simple("onStart")
            .map_err(SinkError::StateChange)
    }

    fn stop(&mut self) -> SinkResult<()> {
        self.emitter.simple("onStop")
            .map_err(SinkError::StateChange)
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        match packet {
            AudioPacket::Samples(samples) => {
                let pcm = converter.f64_to_s16(&samples);
                let mut bytes = Vec::with_capacity(pcm.len() * 2);
                for sample in pcm {
                    bytes.extend_from_slice(&sample.to_le_bytes());
                }
                self.emitter.write_pcm(&bytes).map_err(SinkError::OnWrite)
            }
            AudioPacket::Raw(_) => Err(SinkError::InvalidParams(
                "Resonance native sink requires decoded PCM, not passthrough".to_owned()
            )),
        }
    }
}

/// Starts non-blocking Spotify Premium authentication on a dedicated Rust thread.
/// Returns JNI_TRUE only when the thread was launched; onStatus reports login
/// and decoder errors. The command channel can be replaced by a new login.
#[no_mangle]
pub extern "system" fn Java_com_resonance_player_SpotifyNativeBridge_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    token: JString,
    client_id: JString,
    temp_path: JString,
    output: JObject,
) -> jboolean {
    let token: String = match env.get_string(&token) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let client_id: String = match env.get_string(&client_id) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let temp_path: String = match env.get_string(&temp_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    if token.len() < 20 || client_id.is_empty() || temp_path.is_empty() {
        return 0;
    }
    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return 0,
    };
    let output = match env.new_global_ref(output) {
        Ok(o) => o,
        Err(_) => return 0,
    };
    let emitter = AndroidEmitter { vm, output };
    let (sender, receiver) = mpsc::channel();

    // Terminate prior engine before replacing the channel. The worker drops
    // its session/runtime when it reads Shutdown.
    if let Ok(mut guard) = commands().lock() {
        if let Some(old) = guard.replace(sender) {
            let _ = old.send(EngineCommand::Shutdown);
        }
    } else {
        return 0;
    }
    match thread::Builder::new()
        .name("resonance-librespot".to_owned())
        .spawn(move || run_engine(token, client_id, temp_path, emitter, receiver)) {
        Ok(_) => 1,
        Err(_) => {
            if let Ok(mut guard) = commands().lock() { *guard = None; }
            0
        }
    }
}

fn run_engine(
    access_token: String,
    client_id: String,
    temp_path: String,
    emitter: AndroidEmitter,
    receiver: mpsc::Receiver<EngineCommand>,
) {
    emitter.status("Spotify Premium bağlantısı kuruluyor...");
    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .worker_threads(2)
        .build() {
        Ok(rt) => rt,
        Err(e) => {
            emitter.status(&format!("Rust ses motoru başlatılamadı: {e}"));
            return;
        }
    };

    let mut config = SessionConfig::default();
    config.client_id = client_id;
    config.tmp_dir = PathBuf::from(temp_path);

    let session_result = runtime.block_on(async move {
        let session = Session::new(config, None);
        session.connect(Credentials::with_access_token(access_token), false).await?;
        Ok::<Session, librespot_core::Error>(session)
    });
    let session = match session_result {
        Ok(session) => session,
        Err(err) => {
            emitter.status(&format!("Spotify yetkilendirme başarısız: {err}"));
            return;
        }
    };

    let audio_emitter = emitter.clone();
    let playback_config = PlayerConfig {
        bitrate: Bitrate::Bitrate320,
        position_update_interval: Some(Duration::from_secs(1)),
        ..PlayerConfig::default()
    };
    let player = {
        let _guard = runtime.enter();
        Player::new(
            playback_config,
            session,
            Box::new(NoOpVolume),
            move || Box::new(AndroidPcmSink { emitter: audio_emitter }),
        )
    };
    let mut player_events = player.get_player_event_channel();
    let event_emitter = emitter.clone();
    runtime.spawn(async move {
        while let Some(event) = player_events.recv().await {
            match event {
                PlayerEvent::Playing { position_ms, .. } => {
                    event_emitter.event(2, position_ms as i32);
                    event_emitter.status("Spotify'dan ses çalıyor.");
                }
                PlayerEvent::Paused { position_ms, .. } => {
                    event_emitter.event(3, position_ms as i32);
                    event_emitter.status("Spotify parçası duraklatıldı.");
                }
                PlayerEvent::PositionChanged { position_ms, .. } =>
                    event_emitter.event(7, position_ms as i32),
                PlayerEvent::Unavailable { .. } => {
                    event_emitter.event(5, 0);
                    event_emitter.status("Spotify bu kaydı açamadı. Premium erişimini kontrol et.");
                }
                PlayerEvent::EndOfTrack { .. } => {
                    event_emitter.event(4, 0);
                    event_emitter.status("Spotify parçası tamamlandı.");
                }
                PlayerEvent::SessionDisconnected { .. } => {
                    event_emitter.event(6, 0);
                    event_emitter.status("Spotify bağlantısı kesildi.");
                }
                _ => {}

            }
        }
    });
    emitter.event(1, 0);
    emitter.status("Spotify Premium bağlantısı hazır. Parçayı çalabilirsin.");

    for command in receiver {
        match command {
            EngineCommand::Load(uri) => {
                match SpotifyUri::from_uri(&uri) {
                    Ok(song @ SpotifyUri::Track { .. }) => {
                        player.load(song, true, 0);
                        emitter.status("Spotify üzerinden şarkı yükleniyor...");
                    }
                    _ => emitter.status("Geçersiz Spotify parça kimliği."),
                }
            }
            EngineCommand::Pause => player.pause(),
            EngineCommand::Resume => player.play(),
            EngineCommand::Seek(ms) => player.seek(ms),
            EngineCommand::Stop => player.stop(),
            EngineCommand::Shutdown => {
                player.stop();
                break;
            }
        }
    }
    drop(player);
    emitter.status("Spotify Premium oynatıcısı kapatıldı.");
    runtime.shutdown_background();
}

#[no_mangle]
pub extern "system" fn Java_com_resonance_player_SpotifyNativeBridge_nativeCommand(
    _env: JNIEnv,
    _class: JClass,
    command: jint,
    spotify_uri: JString,
    position_ms: jint,
) -> jboolean {
    let parsed = match command {
        0 => EngineCommand::Pause,
        1 => EngineCommand::Resume,
        2 => EngineCommand::Stop,
        3 => EngineCommand::Seek(position_ms.max(0) as u32),
        4 => {
            let mut env = _env;
            let uri: String = match env.get_string(&spotify_uri) {
                Ok(s) => s.into(),
                Err(_) => return 0,
            };
            if !uri.starts_with("spotify:track:") || uri.len() > 120 {
                return 0;
            }
            EngineCommand::Load(uri)
        }
        _ => return 0,
    };
    if let Ok(guard) = commands().lock() {
        if let Some(sender) = guard.as_ref() {
            return sender.send(parsed).is_ok() as jboolean;
        }
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_com_resonance_player_SpotifyNativeBridge_nativeStop(
    _env: JNIEnv, _class: JClass,
) {
    if let Ok(mut guard) = commands().lock() {
        if let Some(sender) = guard.take() {
            let _ = sender.send(EngineCommand::Shutdown);
        }
    }
}
