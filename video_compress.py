# video_compress.py
import subprocess
import argparse
import os
import sys
import shutil
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

SUPPORTED_INPUT = ('.mp4', '.mkv', '.avi', '.mov', '.wmv', '.flv', '.webm', '.m4v', '.3gp', '.ogv')
VIDEO_CODECS = {
    'h264': 'libx264',
    'hevc': 'libx265',
    'vp9': 'libvpx-vp9',
    'av1': 'libaom-av1'
}
AUDIO_CODECS = ['aac', 'mp3', 'opus', 'ac3']
PRESETS = ['ultrafast', 'superfast', 'veryfast', 'faster', 'fast', 'medium', 'slow', 'slower', 'veryslow']

def check_ffmpeg():
    if shutil.which("ffmpeg") is None:
        print("Ошибка: ffmpeg не найден. Установите FFmpeg и добавьте в PATH.", file=sys.stderr)
        sys.exit(1)

def build_ffmpeg_cmd(input_file, output_file, video_codec, audio_codec, crf, video_bitrate, audio_bitrate,
                     size, fps, preset, two_pass, normalize, subtitles):
    cmd = ["ffmpeg", "-i", input_file, "-y"]
    
    # Видеокодек
    if video_codec in VIDEO_CODECS:
        cmd.extend(["-c:v", VIDEO_CODECS[video_codec]])
    else:
        cmd.extend(["-c:v", "libx264"])
    
    # Аудиокодек
    if audio_codec in AUDIO_CODECS:
        cmd.extend(["-c:a", audio_codec])
    else:
        cmd.extend(["-c:a", "aac"])
    
    # CRF или битрейт
    if crf is not None:
        cmd.extend(["-crf", str(crf)])
    elif video_bitrate:
        cmd.extend(["-b:v", video_bitrate])
    
    # Битрейт аудио
    if audio_bitrate:
        cmd.extend(["-b:a", audio_bitrate])
    
    # Разрешение
    if size:
        cmd.extend(["-vf", f"scale={size}:flags=lanczos"])
    
    # Частота кадров
    if fps:
        cmd.extend(["-r", str(fps)])
    
    # Пресет
    if preset and preset in PRESETS:
        cmd.extend(["-preset", preset])
    
    # Нормализация громкости
    if normalize:
        cmd.extend(["-af", "loudnorm=I=-16:LRA=11:TP=-1.5"])
    
    # Субтитры
    if subtitles:
        cmd.extend(["-vf", f"subtitles={subtitles}"])
    
    cmd.append(output_file)
    return cmd

def convert_file(input_path, output_path, video_codec, audio_codec, crf, video_bitrate, audio_bitrate,
                 size, fps, preset, two_pass, normalize, subtitles):
    # Двухпроходное кодирование
    if two_pass and video_bitrate:
        # Первый проход
        pass1_cmd = build_ffmpeg_cmd(str(input_path), "/dev/null", video_codec, audio_codec, crf, video_bitrate,
                                     audio_bitrate, size, fps, preset, False, normalize, subtitles)
        pass1_cmd.extend(["-pass", "1", "-f", "null"])
        print(f"  Проход 1/2: {' '.join(pass1_cmd)}")
        subprocess.run(pass1_cmd, check=True, capture_output=True)
        # Второй проход
        pass2_cmd = build_ffmpeg_cmd(str(input_path), str(output_path), video_codec, audio_codec, crf, video_bitrate,
                                     audio_bitrate, size, fps, preset, False, normalize, subtitles)
        pass2_cmd.extend(["-pass", "2"])
        print(f"  Проход 2/2: {' '.join(pass2_cmd)}")
        try:
            subprocess.run(pass2_cmd, check=True, capture_output=True)
            return True
        except subprocess.CalledProcessError as e:
            print(f"  Ошибка: {e.stderr.decode()}", file=sys.stderr)
            return False
    else:
        cmd = build_ffmpeg_cmd(str(input_path), str(output_path), video_codec, audio_codec, crf, video_bitrate,
                               audio_bitrate, size, fps, preset, False, normalize, subtitles)
        try:
            subprocess.run(cmd, check=True, capture_output=True)
            return True
        except subprocess.CalledProcessError as e:
            print(f"  Ошибка: {e.stderr.decode()}", file=sys.stderr)
            return False

def find_video_files(root, recursive):
    files = []
    root_path = Path(root)
    if root_path.is_file() and root_path.suffix.lower() in SUPPORTED_INPUT:
        return [root_path]
    if not root_path.is_dir():
        return []
    pattern = "**/*" if recursive else "*"
    for p in root_path.glob(pattern):
        if p.is_file() and p.suffix.lower() in SUPPORTED_INPUT:
            files.append(p)
    return files

def main():
    parser = argparse.ArgumentParser(description='Конвертер видео (сжатие)')
    parser.add_argument('source', help='Входной файл или папка')
    parser.add_argument('--output', '-o', help='Выходной файл или папка')
    parser.add_argument('--codec', choices=VIDEO_CODECS.keys(), default='h264', help='Видеокодек')
    parser.add_argument('--audio-codec', choices=AUDIO_CODECS, default='aac', help='Аудиокодек')
    parser.add_argument('--crf', type=int, help='CRF (0-51, меньше = лучше качество)')
    parser.add_argument('--video-bitrate', help='Битрейт видео (например, 1000k)')
    parser.add_argument('--audio-bitrate', help='Битрейт аудио (например, 128k)')
    parser.add_argument('--size', help='Разрешение (ШxВ)')
    parser.add_argument('--fps', type=int, help='Частота кадров')
    parser.add_argument('--preset', choices=PRESETS, default='medium', help='Пресет скорости')
    parser.add_argument('--two-pass', action='store_true', help='Двухпроходное кодирование')
    parser.add_argument('--normalize', action='store_true', help='Нормализация громкости')
    parser.add_argument('--subtitles', help='Файл субтитров для встраивания')
    parser.add_argument('--recursive', action='store_true', help='Рекурсивный обход папок')
    parser.add_argument('--threads', type=int, default=4, help='Количество потоков')
    parser.add_argument('--overwrite', action='store_true', help='Перезаписывать файлы')
    parser.add_argument('--formats', action='store_true', help='Показать поддерживаемые форматы')
    args = parser.parse_args()

    if args.formats:
        print("Входные форматы:", ', '.join(SUPPORTED_INPUT))
        print("Видеокодеки:", ', '.join(VIDEO_CODECS.keys()))
        print("Аудиокодеки:", ', '.join(AUDIO_CODECS))
        print("Пресеты:", ', '.join(PRESETS))
        return

    check_ffmpeg()

    if args.crf is not None and args.video_bitrate:
        print("Ошибка: укажите либо --crf, либо --video-bitrate, но не оба.", file=sys.stderr)
        sys.exit(1)

    files = find_video_files(args.source, args.recursive)
    if not files:
        print(f"Не найдено видеофайлов в {args.source}")
        sys.exit(1)

    output_dir = Path(args.output) if args.output else Path("./compressed")
    output_dir.mkdir(parents=True, exist_ok=True)
    total = len(files)
    print(f"Найдено {total} видеофайлов.")
    success = 0

    def worker(input_file):
        nonlocal success
        if args.output and Path(args.output).suffix:
            if len(files) > 1:
                return (False, "Указан один выходной файл, но найдено несколько входных.")
            out_file = Path(args.output)
        else:
            rel = input_file.relative_to(args.source) if Path(args.source).is_dir() else input_file.name
            out_file = output_dir / rel.with_suffix('.mp4')
        if out_file.exists() and not args.overwrite:
            return (False, f"{out_file} уже существует, пропуск.")
        out_file.parent.mkdir(parents=True, exist_ok=True)
        if convert_file(input_file, out_file, args.codec, args.audio_codec, args.crf, args.video_bitrate,
                        args.audio_bitrate, args.size, args.fps, args.preset, args.two_pass,
                        args.normalize, args.subtitles):
            return (True, f"Конвертация {input_file} -> {out_file}")
        return (False, f"Ошибка при конвертации {input_file}")

    with ThreadPoolExecutor(max_workers=args.threads) as executor:
        futures = {executor.submit(worker, f): f for f in files}
        for i, future in enumerate(as_completed(futures), 1):
            ok, msg = future.result()
            print(f"[{i}/{total}] {msg}")
            if ok:
                success += 1

    print(f"Готово! Успешно: {success}, Всего: {total}")

if __name__ == '__main__':
    main()
