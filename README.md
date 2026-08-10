🗜️ Конвертер видео (сжатие) — уменьшайте размер без потери качества
Версия: 1.0.0 | Лицензия: MIT | Статус: ✅ Активная разработка

https://img.shields.io/github/repo-size/yourusername/video-compressor https://img.shields.io/github/last-commit/yourusername/video-compressor https://img.shields.io/github/languages/count/yourusername/video-compressor

🎬 Описание
Конвертер видео (сжатие) — это мощная консольная утилита для эффективного сжатия видеофайлов с использованием современных кодеков и алгоритмов. Программа позволяет значительно уменьшить размер видео при минимальной потере качества, что идеально для архивации, публикации в интернете и экономии места на диске.

Возможности:

✅ Сжатие видео с настройкой битрейта (CBR/VBR)

✅ Использование CRF (Constant Rate Factor) для оптимального качества

✅ Выбор кодеков: H.264 (libx264), H.265 (HEVC), VP9, AV1

✅ Изменение разрешения (пропорциональное масштабирование)

✅ Настройка частоты кадров (fps)

✅ Пресеты скорости кодирования (ultrafast … veryslow)

✅ Двухпроходное кодирование для максимального качества

✅ Аудио-сжатие с выбором битрейта и кодека

✅ Пакетная обработка (рекурсивно по папкам)

✅ Прогресс-бар и подробные логи

✅ Многопоточность (опционально)

✅ Кроссплатформенность (Linux, macOS, Windows)

Проект содержит 8 полноценных реализаций на разных языках программирования. Все версии используют FFmpeg — самый мощный и универсальный инструмент для работы с видео и аудио.

✨ Возможности
Функция	Описание
Сжатие видео	Уменьшение размера файла с контролем качества
CRF	0–51 (0 — без потерь, 23 — по умолчанию, 51 — худшее качество)
Битрейт	Настраиваемый (например, 1000k, 2M)
Видеокодеки	H.264, H.265 (HEVC), VP9, AV1
Аудиокодеки	AAC, MP3, Opus, AC-3
Разрешение	Изменение размера (например, 1280x720)
Частота кадров	Настраиваемая (например, 30, 60)
Пресеты	ultrafast, superfast, veryfast, faster, fast, medium, slow, slower, veryslow
Двухпроходное кодирование	Оптимальное качество при заданном битрейте
Нормализация	Выравнивание громкости (EBU R128)
Пакетная обработка	Рекурсивная конвертация всех файлов в папке
Перезапись	Перезапись существующих файлов
Многопоточность	Параллельная обработка
Кроссплатформенность	Работает на всех основных ОС
📦 Установка и запуск
Общие требования
Для работы всех реализаций необходим установленный FFmpeg:

bash
# Ubuntu/Debian
sudo apt install ffmpeg

# macOS
brew install ffmpeg

# Windows
# Скачайте с https://ffmpeg.org/download.html и добавьте в PATH
Запуск на разных языках
Язык	Файл	Зависимости	Команда запуска
Python	video_compress.py	нет (использует subprocess)	python3 video_compress.py input.mkv --output compressed.mp4
Node.js	video_compress.js	yargs	npm install yargs && node video_compress.js input.mkv --output compressed.mp4
Rust	video_compress.rs	clap, glob, walkdir	cargo run -- input.mkv --output compressed.mp4
Go	video_compress.go	нет	go run video_compress.go input.mkv --output compressed.mp4
Java	VideoCompress.java	нет (Java 8+)	javac VideoCompress.java && java VideoCompress input.mkv --output compressed.mp4
C#	video_compress.cs	нет (.NET Core)	dotnet run input.mkv --output compressed.mp4
Ruby	video_compress.rb	нет	ruby video_compress.rb input.mkv --output compressed.mp4
C++	video_compress.cpp	нет (C++17)	g++ -std=c++17 -o video_compress video_compress.cpp && ./video_compress input.mkv --output compressed.mp4
📂 Структура репозитория
text
.
├── README.md
├── python/
│   └── video_compress.py
├── go/
│   └── video_compress.go
├── rust/
│   ├── Cargo.toml
│   └── src/
│       └── main.rs
├── cpp/
│   └── video_compress.cpp
├── java/
│   └── VideoCompress.java
├── csharp/
│   └── video_compress.cs
├── ruby/
│   └── video_compress.rb
└── javascript/
    ├── package.json
    └── video_compress.js
🎮 Использование
bash
# Базовая компрессия (H.264, CRF 23, качество по умолчанию)
video_compress input.mkv

# Указать выходной файл
video_compress input.mkv --output compressed.mp4

# Установить CRF (0-51, меньше = лучше качество, больше = сильнее сжатие)
video_compress input.mkv --crf 28

# Установить битрейт (CBR)
video_compress input.mkv --video-bitrate 1000k

# Изменить разрешение (масштабирование с сохранением пропорций)
video_compress input.mkv --size 1280x720

# Выбрать кодек H.265 (HEVC) для лучшего сжатия
video_compress input.mkv --codec hevc

# Установить частоту кадров 30 FPS
video_compress input.mkv --fps 30

# Пресет скорости (медленнее = лучше сжатие)
video_compress input.mkv --preset slow

# Двухпроходное кодирование (лучшее качество при заданном битрейте)
video_compress input.mkv --two-pass

# Сжать аудио до 96 кбит/с
video_compress input.mkv --audio-bitrate 96k

# Нормализация громкости
video_compress input.mkv --normalize

# Пакетная конвертация всех видео в папке (рекурсивно)
video_compress ./videos/ --recursive --output ./compressed/

# Многопоточность (4 параллельных процесса)
video_compress ./videos/ --recursive --threads 4

# Показать поддерживаемые форматы и кодеки
video_compress --formats
🛠️ Особенности реализаций
Python – subprocess и argparse, простой и надёжный код.

Node.js – child_process и yargs, асинхронная обработка.

Rust – std::process::Command и clap, безопасность и скорость.

Go – os/exec и flag, быстрый запуск.

Java – ProcessBuilder и ручной парсинг аргументов.

C# – System.Diagnostics.Process, современный синтаксис.

Ruby – system и optparse, выразительный код.

C++ – system() и ручной парсинг, классика.

Все версии используют FFmpeg для сжатия, что обеспечивает единообразие и высокое качество видео.

🤝 Вклад
PR и issues приветствуются. Добавляйте поддержку новых кодеков, улучшайте производительность, расширяйте функциональность.

📄 Лицензия
MIT License.

