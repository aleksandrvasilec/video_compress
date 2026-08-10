// video_compress.rs
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;
use std::sync::mpsc;
use std::thread;
use clap::{Parser, Arg};

#[derive(Parser)]
#[command(name = "video_compress")]
struct Args {
    #[arg(help = "Входной файл или папка")]
    source: String,
    #[arg(short = 'o', long)]
    output: Option<String>,
    #[arg(short = 'c', long, default_value = "h264")]
    codec: String,
    #[arg(long, default_value = "aac")]
    audio_codec: String,
    #[arg(long)]
    crf: Option<u8>,
    #[arg(long)]
    video_bitrate: Option<String>,
    #[arg(long)]
    audio_bitrate: Option<String>,
    #[arg(long)]
    size: Option<String>,
    #[arg(long)]
    fps: Option<u32>,
    #[arg(long, default_value = "medium")]
    preset: String,
    #[arg(long)]
    two_pass: bool,
    #[arg(long)]
    normalize: bool,
    #[arg(long)]
    subtitles: Option<String>,
    #[arg(long)]
    recursive: bool,
    #[arg(long, default_value_t = 4)]
    threads: usize,
    #[arg(long)]
    overwrite: bool,
    #[arg(long)]
    formats: bool,
}

const SUPPORTED_INPUT: &[&str] = &[".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp", ".ogv"];
const VIDEO_CODECS: &[(&str, &str)] = &[("h264", "libx264"), ("hevc", "libx265"), ("vp9", "libvpx-vp9"), ("av1", "libaom-av1")];
const AUDIO_CODECS: &[&str] = &["aac", "mp3", "opus", "ac3"];
const PRESETS: &[&str] = &["ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow"];

fn check_ffmpeg() {
    if Command::new("ffmpeg").arg("-version").output().is_err() {
        eprintln!("Ошибка: ffmpeg не найден. Установите FFmpeg и добавьте в PATH.");
        std::process::exit(1);
    }
}

fn get_video_codec(codec: &str) -> &str {
    for (k, v) in VIDEO_CODECS {
        if *k == codec {
            return v;
        }
    }
    "libx264"
}

fn build_ffmpeg_cmd(input: &str, output: &str, codec: &str, audio_codec: &str,
                    crf: Option<u8>, video_bitrate: Option<&str>, audio_bitrate: Option<&str>,
                    size: Option<&str>, fps: Option<u32>, preset: &str,
                    normalize: bool, subtitles: Option<&str>, pass: Option<u8>) -> Vec<String> {
    let mut cmd = vec!["ffmpeg".to_string(), "-i".to_string(), input.to_string(), "-y".to_string()];
    cmd.push("-c:v".to_string());
    cmd.push(get_video_codec(codec).to_string());
    cmd.push("-c:a".to_string());
    cmd.push(audio_codec.to_string());
    if let Some(c) = crf {
        cmd.push("-crf".to_string());
        cmd.push(c.to_string());
    } else if let Some(vb) = video_bitrate {
        cmd.push("-b:v".to_string());
        cmd.push(vb.to_string());
    }
    if let Some(ab) = audio_bitrate {
        cmd.push("-b:a".to_string());
        cmd.push(ab.to_string());
    }
    if let Some(sz) = size {
        cmd.push("-vf".to_string());
        cmd.push(format!("scale={}:flags=lanczos", sz));
    }
    if let Some(f) = fps {
        cmd.push("-r".to_string());
        cmd.push(f.to_string());
    }
    if PRESETS.contains(&preset) {
        cmd.push("-preset".to_string());
        cmd.push(preset.to_string());
    }
    if normalize {
        cmd.push("-af".to_string());
        cmd.push("loudnorm=I=-16:LRA=11:TP=-1.5".to_string());
    }
    if let Some(sub) = subtitles {
        cmd.push("-vf".to_string());
        cmd.push(format!("subtitles={}", sub));
    }
    if let Some(p) = pass {
        cmd.push("-pass".to_string());
        cmd.push(p.to_string());
        if p == 1 {
            cmd.push("-f".to_string());
            cmd.push("null".to_string());
            cmd.push("/dev/null".to_string());
        }
    }
    cmd.push(output.to_string());
    cmd
}

fn convert_file(input: &str, output: &str, codec: &str, audio_codec: &str,
                crf: Option<u8>, video_bitrate: Option<&str>, audio_bitrate: Option<&str>,
                size: Option<&str>, fps: Option<u32>, preset: &str,
                two_pass: bool, normalize: bool, subtitles: Option<&str>) -> bool {
    if two_pass && video_bitrate.is_some() {
        let pass1_cmd = build_ffmpeg_cmd(input, "/dev/null", codec, audio_codec, crf,
                                         video_bitrate, audio_bitrate, size, fps, preset,
                                         normalize, subtitles, Some(1));
        if Command::new(&pass1_cmd[0]).args(&pass1_cmd[1..]).status().is_err() {
            return false;
        }
        let pass2_cmd = build_ffmpeg_cmd(input, output, codec, audio_codec, crf,
                                         video_bitrate, audio_bitrate, size, fps, preset,
                                         normalize, subtitles, Some(2));
        Command::new(&pass2_cmd[0]).args(&pass2_cmd[1..]).status().is_ok()
    } else {
        let cmd = build_ffmpeg_cmd(input, output, codec, audio_codec, crf,
                                   video_bitrate, audio_bitrate, size, fps, preset,
                                   normalize, subtitles, None);
        Command::new(&cmd[0]).args(&cmd[1..]).status().is_ok()
    }
}

fn find_video_files(root: &str, recursive: bool) -> Vec<PathBuf> {
    let mut files = Vec::new();
    let path = Path::new(root);
    if path.is_file() {
        if let Some(ext) = path.extension() {
            if SUPPORTED_INPUT.contains(&ext.to_str().unwrap_or("").to_lowercase().as_str()) {
                files.push(path.to_path_buf());
            }
        }
        return files;
    }
    if !path.is_dir() {
        return files;
    }
    if recursive {
        for entry in walkdir::WalkDir::new(path) {
            if let Ok(entry) = entry {
                if entry.file_type().is_file() {
                    if let Some(ext) = entry.path().extension() {
                        let ext_lower = ext.to_str().unwrap_or("").to_lowercase();
                        if SUPPORTED_INPUT.contains(&ext_lower.as_str()) {
                            files.push(entry.path().to_path_buf());
                        }
                    }
                }
            }
        }
    } else {
        if let Ok(entries) = fs::read_dir(path) {
            for entry in entries.flatten() {
                let p = entry.path();
                if p.is_file() {
                    if let Some(ext) = p.extension() {
                        let ext_lower = ext.to_str().unwrap_or("").to_lowercase();
                        if SUPPORTED_INPUT.contains(&ext_lower.as_str()) {
                            files.push(p);
                        }
                    }
                }
            }
        }
    }
    files
}

fn main() {
    let args = Args::parse();

    if args.formats {
        println!("Входные форматы: {}", SUPPORTED_INPUT.join(", "));
        println!("Видеокодеки: {}", VIDEO_CODECS.iter().map(|(k,_)| *k).collect::<Vec<_>>().join(", "));
        println!("Аудиокодеки: {}", AUDIO_CODECS.join(", "));
        println!("Пресеты: {}", PRESETS.join(", "));
        return;
    }

    check_ffmpeg();

    if args.crf.is_some() && args.video_bitrate.is_some() {
        eprintln!("Ошибка: укажите либо --crf, либо --video-bitrate, но не оба.");
        std::process::exit(1);
    }

    let files = find_video_files(&args.source, args.recursive);
    if files.is_empty() {
        println!("Не найдено видеофайлов в {}", args.source);
        std::process::exit(1);
    }

    let out_dir = match args.output {
        Some(ref o) => {
            if Path::new(o).is_dir() {
                o.clone()
            } else {
                Path::new(o).parent().unwrap_or(Path::new(".")).to_str().unwrap().to_string()
            }
        }
        None => "./compressed".to_string(),
    };
    fs::create_dir_all(&out_dir).unwrap();
    let total = files.len();
    println!("Найдено {} видеофайлов.", total);
    let (tx, rx) = mpsc::channel();
    let mut handles = vec![];

    let args = Arc::new(args);

    for chunk in files.chunks((total + args.threads - 1) / args.threads) {
        let chunk = chunk.to_vec();
        let tx = tx.clone();
        let out_dir = out_dir.clone();
        let args = args.clone();
        handles.push(thread::spawn(move || {
            for (i, input_file) in chunk.iter().enumerate() {
                let rel = input_file.strip_prefix(&args.source).unwrap_or(input_file);
                let out_path = Path::new(&out_dir).join(rel.with_extension("mp4"));
                if out_path.exists() && !args.overwrite {
                    tx.send((i+1, format!("{} уже существует, пропуск.", out_path.display()))).unwrap();
                    continue;
                }
                if let Some(parent) = out_path.parent() {
                    fs::create_dir_all(parent).unwrap();
                }
                let ok = convert_file(
                    input_file.to_str().unwrap(),
                    out_path.to_str().unwrap(),
                    &args.codec,
                    &args.audio_codec,
                    args.crf,
                    args.video_bitrate.as_deref(),
                    args.audio_bitrate.as_deref(),
                    args.size.as_deref(),
                    args.fps,
                    &args.preset,
                    args.two_pass,
                    args.normalize,
                    args.subtitles.as_deref(),
                );
                if ok {
                    tx.send((i+1, format!("Конвертация {} -> {}", input_file.display(), out_path.display()))).unwrap();
                } else {
                    tx.send((i+1, format!("Ошибка при конвертации {}", input_file.display()))).unwrap();
                }
            }
        }));
    }
    drop(tx);
    let mut count = 0;
    let mut success = 0;
    for (idx, msg) in rx {
        count += 1;
        println!("[{}/{}] {}", count, total, msg);
        if !msg.contains("ошибка") && !msg.contains("пропуск") {
            success += 1;
        }
    }
    for h in handles {
        h.join().unwrap();
    }
    println!("Готово! Успешно: {}, Всего: {}", success, total);
}
