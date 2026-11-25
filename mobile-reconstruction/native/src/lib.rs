#![recursion_limit = "256"]
use brush_process::{config::ProcessArgs, message::ProcessMessage, process::process_stream};
use brush_vfs::DataSource;
use jni::{JNIEnv, objects::{JClass, JString}, sys::{jint, jstring}};
use serde_json::{Value, json};
use std::{fs, path::Path, time::Instant};
use tokio_stream::StreamExt;

fn record(folder: &Path, value: &Value) {
    if let Ok(bytes) = serde_json::to_vec_pretty(value) {
        let pending = folder.join("progress.pending");
        if fs::write(&pending, bytes).is_ok() {
            let _ = fs::rename(pending, folder.join("progress.json"));
        }
    }
    log::info!("PROBE {}", value);
}

fn args_refine_interval(config: &Value) -> u32 { config["refine_every"].as_u64().unwrap_or(100).max(25) as u32 }

async fn train(input: String, output: &Path, steps: u32) -> anyhow::Result<Value> {
    let started = Instant::now();
    let config: Value = fs::read(output.join("probe-config.json")).ok()
        .and_then(|bytes|serde_json::from_slice(&bytes).ok()).unwrap_or_else(||json!({}));
    let steps = config["total_steps"].as_u64().map(|n| n.clamp(20, 30000) as u32).unwrap_or(steps);
    let disable_refinement = config["disable_refinement"].as_bool().unwrap_or(true);
    let legacy_subset = config["legacy_subset"].as_bool().unwrap_or(false);
    let budget_seconds = config["budget_seconds"].as_f64().map(|n|n.clamp(30.0, 600.0));
    record(output, &json!({"phase":"initializing_gpu","steps":steps}));
    // Same compute initialization used by the official headless Brush CLI.
    let device = brush_render::burn_init_setup().await;
    record(output, &json!({"phase":"loading_posed_images","steps":steps}));
    let mut args = ProcessArgs::default();
    args.train_config.total_steps = steps;
    args.train_config.max_splats = if steps <= 20 { 5000 } else if steps >= 1200 { 25000 } else { 20000 };
    args.train_config.growth_stop_iter = if steps <= 20 { 0 } else if steps >= 1200 { 800 } else { 200 };
    if let Some(n) = config["max_splats"].as_u64() { args.train_config.max_splats = n.clamp(5000, 120000) as u32; }
    if let Some(n) = config["growth_stop_iter"].as_u64() { args.train_config.growth_stop_iter = n.min(steps as u64) as u32; }
    if let Some(n) = config["growth_grad_threshold"].as_f64() {
        anyhow::ensure!(n.is_finite() && n > 0.0, "Invalid growth threshold");
        args.train_config.growth_grad_threshold = n.clamp(1e-6, 1e-3) as f32;
    }
    if let Some(n) = config["growth_select_fraction"].as_f64() {
        anyhow::ensure!(n.is_finite(), "Invalid growth fraction");
        args.train_config.growth_select_fraction = n.clamp(0.01, 0.3) as f32;
    }
    args.train_config.refine_every = if disable_refinement { steps + 1 } else { config["refine_every"].as_u64().unwrap_or(100).max(25) as u32 };
    if config["l1_only"].as_bool().unwrap_or(false) { args.train_config.ssim_weight = 0.0; }
    args.model_config.sh_degree = 2;
    args.load_config.max_resolution = config["resolution"].as_u64().unwrap_or(if steps <= 20 {256} else {512}).clamp(256,1024) as u32;
    // Brush applies max_frames BEFORE subsampling. Keep the whole orbit unless
    // explicitly reproducing the original diagnostic subset.
    args.load_config.max_frames = if legacy_subset { Some(if steps <= 20 { 12 } else { 32 }) } else { None };
    args.load_config.subsample_frames = Some(config["subsample_frames"].as_u64().unwrap_or(if steps <= 20 {8} else {3}).clamp(1,8) as u32);
    args.load_config.subsample_points = Some(if steps <= 20 { 8 } else if steps >= 1200 { 1 } else { 2 });
    if steps > 20 {
        args.load_config.eval_split_every = Some(8);
        args.process_config.eval_save_to_disk = true;
    }
    args.process_config.export_path = output.to_string_lossy().into_owned();
    args.process_config.export_name = "generated_{iter}.ply".to_owned();
    args.process_config.export_every = config["export_every"].as_u64().map(|n|n.clamp(100, 5000) as u32)
        .unwrap_or(if steps >= 1200 { 100 } else if steps > 20 { 50 } else { steps });
    args.process_config.eval_every = steps;
    args.rerun_config.rerun_enabled = false;
    let effective_config = json!({"total_steps":steps,"max_splats":args.train_config.max_splats,
        "growth_stop_iter":args.train_config.growth_stop_iter,"refine_every":args.train_config.refine_every,
        "growth_grad_threshold":args.train_config.growth_grad_threshold,
        "growth_select_fraction":args.train_config.growth_select_fraction,
        "resolution":args.load_config.max_resolution,"disable_refinement":disable_refinement});
    let (sender, receiver) = tokio::sync::oneshot::channel();
    sender.send(args).map_err(|_| anyhow::anyhow!("Failed to supply training configuration"))?;
    let stream = process_stream(DataSource::Path(input), receiver, device);
    let mut stream = std::pin::pin!(stream);
    let mut last_iteration = 0;
    let mut count = 0;
    let mut first_loss = None;
    let mut last_loss = None;
    let mut views = 0;
    let mut budget_stopped = false;
    let mut budget_model = None;
    while let Some(message) = stream.next().await {
        anyhow::ensure!(!output.join("CANCEL").exists(), "Cancelled by user");
        match message? {
            ProcessMessage::Dataset { dataset } => {
                views = dataset.train.views.len();
                record(output, &json!({"phase":"dataset_loaded","views":dataset.train.views.len(),"steps":steps}));
            }
            ProcessMessage::TrainStep { iter, splats, stats, total_elapsed } => {
                last_iteration = iter;
                count = splats.num_splats();
                let loss: f32 = stats.loss.into_scalar_async().await;
                if !loss.is_finite() {
                    let prefix = if loss.is_finite() { "after_refinement" } else { "invalid" };
                    if let Ok(data) = brush_serde::splat_to_ply((*splats).clone()).await {
                        let _ = fs::write(output.join(format!("{prefix}_{iter:03}.ply")),data);
                    }
                }
                if !loss.is_finite() { anyhow::bail!("Non-finite training loss at iteration {iter}"); }
                if first_loss.is_none() { first_loss = Some(loss); }
                last_loss = Some(loss);
                record(output, &json!({"phase":"training","iteration":iter,"steps":steps,
                    "gaussians":count,"loss":loss,"training_seconds":total_elapsed.as_secs_f64(),
                    "wall_seconds":started.elapsed().as_secs_f64()}));
                let time_to_save = output.join("STOP_AND_SAVE").exists() || budget_seconds
                    .map(|budget|started.elapsed().as_secs_f64() >= budget - (budget*0.1).min(60.0)).unwrap_or(false);
                if time_to_save && iter < steps {
                    record(output, &json!({"phase":"exporting","iteration":iter,"steps":steps,"reason":"time_budget"}));
                    let filename=format!("generated_{iter}.ply");
                    fs::write(output.join(&filename),brush_serde::splat_to_ply(*splats).await?)?;
                    budget_model=Some(filename);budget_stopped=true;break;
                }
            }
            ProcessMessage::Warning { error } => {
                return Err(anyhow::anyhow!("Training/export warning: {error}"));
            }
            _ => {}
        }
    }
    if last_iteration != steps && !budget_stopped { anyhow::bail!("Training ended before requested iteration"); }
    let filename = budget_model.unwrap_or_else(||format!("generated_{steps}.ply"));
    let model = output.join(&filename);
    let bytes = fs::metadata(&model)?.len();
    if bytes < 1024 { anyhow::bail!("Generated model is missing or too small"); }
    let result = json!({"phase":"completed","requested_steps":steps,"completed_steps":last_iteration,
        "gaussians":count,"first_reported_loss":first_loss,"last_reported_loss":last_loss,
        "wall_seconds":started.elapsed().as_secs_f64(),
        "model":filename,"model_bytes":bytes,"backend":"Brush v0.3.0 / Vulkan / phone GPU",
        "training_views":views,"disable_refinement":disable_refinement,"legacy_subset":legacy_subset,
        "stopped_for_time_budget":budget_stopped,"generation_budget_seconds":budget_seconds,"effective_config":effective_config,
        "quality_state":if budget_stopped {"time_limited"}else{"all_steps_completed"},
        "optimizer_epsilon":1e-8,
        "scope":"Phone GPU Gaussian training; pose provenance is recorded by the input adapter"});
    record(output, &result);
    fs::write(output.join("result.json"),serde_json::to_vec_pretty(&result)?)?;
    Ok(result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_furniture_reconstructionprobe_MainActivity_runNative(
    mut env: JNIEnv, _: JClass, input: JString, output: JString, steps: jint,
) -> jstring {
    android_logger::init_once(android_logger::Config::default().with_tag("FurnitureProbe").with_max_level(log::LevelFilter::Info));
    std::panic::set_hook(Box::new(|info| log::error!("RUST_PANIC {info}")));
    let input: String = match env.get_string(&input) { Ok(s) => s.into(), Err(_) => return std::ptr::null_mut() };
    let output: String = match env.get_string(&output) { Ok(s) => s.into(), Err(_) => return std::ptr::null_mut() };
    let folder = Path::new(&output);
    let result = std::panic::catch_unwind(|| -> anyhow::Result<Value> {
        if steps != 20 && steps != 300 && steps != 1200 { anyhow::bail!("Unsupported probe size"); }
        fs::create_dir_all(folder)?;
        tokio::runtime::Builder::new_multi_thread().worker_threads(4).enable_all().build()?
            .block_on(train(input, folder, steps as u32))
    });
    let value = match result {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => json!({"phase":if folder.join("CANCEL").exists(){"cancelled"}else{"failed"},"error":format!("{error:#}")}),
        Err(payload) => {
            let message = payload.downcast_ref::<String>().cloned()
                .or_else(||payload.downcast_ref::<&str>().map(|s|s.to_string()))
                .unwrap_or_else(||"Native compute panic".to_owned());
            json!({"phase":"failed","error":message})
        }
    };
    record(folder, &value);
    let _ = fs::write(folder.join("result.json"),value.to_string());
    env.new_string(value.to_string()).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
