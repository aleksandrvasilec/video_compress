// video_compress.js
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');
const { promisify } = require('util');
const yargs = require('yargs');
const { hideBin } = require('yargs/helpers');

const execPromise = promisify(exec);

const SUPPORTED_INPUT = ['.mp4', '.mkv', '.avi', '.mov', '.wmv', '.flv', '.webm', '.m4v', '.3gp', '.ogv'];
const VIDEO_CODECS = { h264: 'libx264', hevc: 'libx265', vp9: 'libvpx-vp9', av1: 'libaom-av1' };
const AUDIO_CODECS = ['aac', 'mp3', 'opus', 'ac3'];
const PRESETS = ['ultrafast', 'superfast', 'veryfast', 'faster', 'fast', 'medium', 'slow', 'slower', 'veryslow'];

function checkFFmpeg() {
    try {
        exec('ffmpeg -version', (err) => {
            if (err) {
                console.error('Ошибка: ffmpeg не найден. Установите FFmpeg и добавьте в PATH.');
                process.exit(1);
            }
        });
    } catch {
        console.error('Ошибка: ffmpeg не найден.');
        process.exit(1);
    }
}

function buildFFmpegCmd(inputFile, outputFile, videoCodec, audioCodec, crf, videoBitrate, audioBitrate,
                        size, fps, preset, twoPass, normalize, subtitles) {
    const cmd = ['ffmpeg', '-i', inputFile, '-y'];
    cmd.push('-c:v', VIDEO_CODECS[videoCodec] || 'libx264');
    cmd.push('-c:a', audioCodec || 'aac');
    if (crf !== undefined) cmd.push('-crf', String(crf));
    else if (videoBitrate) cmd.push('-b:v', videoBitrate);
    if (audioBitrate) cmd.push('-b:a', audioBitrate);
    if (size) cmd.push('-vf', `scale=${size}:flags=lanczos`);
    if (fps) cmd.push('-r', String(fps));
    if (preset && PRESETS.includes(preset)) cmd.push('-preset', preset);
    if (normalize) cmd.push('-af', 'loudnorm=I=-16:LRA=11:TP=-1.5');
    if (subtitles) cmd.push('-vf', `subtitles=${subtitles}`);
    cmd.push(outputFile);
    return cmd;
}

async function convertFile(inputPath, outputPath, videoCodec, audioCodec, crf, videoBitrate, audioBitrate,
                           size, fps, preset, twoPass, normalize, subtitles) {
    // Двухпроходное кодирование (упрощённо)
    if (twoPass && videoBitrate) {
        const pass1Cmd = buildFFmpegCmd(inputPath, '/dev/null', videoCodec, audioCodec, crf, videoBitrate,
                                        audioBitrate, size, fps, preset, false, normalize, subtitles);
        pass1Cmd.push('-pass', '1', '-f', 'null');
        try {
            await execPromise(pass1Cmd.join(' '));
        } catch (err) {
            console.error(`  Ошибка прохода 1: ${err.stderr}`);
            return false;
        }
        const pass2Cmd = buildFFmpegCmd(inputPath, outputPath, videoCodec, audioCodec, crf, videoBitrate,
                                        audioBitrate, size, fps, preset, false, normalize, subtitles);
        pass2Cmd.push('-pass', '2');
        try {
            await execPromise(pass2Cmd.join(' '));
            return true;
        } catch (err) {
            console.error(`  Ошибка прохода 2: ${err.stderr}`);
            return false;
        }
    } else {
        const cmd = buildFFmpegCmd(inputPath, outputPath, videoCodec, audioCodec, crf, videoBitrate,
                                   audioBitrate, size, fps, preset, false, normalize, subtitles);
        try {
            await execPromise(cmd.join(' '));
            return true;
        } catch (err) {
            console.error(`  Ошибка: ${err.stderr}`);
            return false;
        }
    }
}

function findVideoFiles(root, recursive) {
    const files = [];
    if (fs.existsSync(root) && fs.statSync(root).isFile()) {
        if (SUPPORTED_INPUT.includes(path.extname(root).toLowerCase())) {
            files.push(root);
        }
        return files;
    }
    if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
        return files;
    }
    const walk = (dir) => {
        const entries = fs.readdirSync(dir, { withFileTypes: true });
        for (const entry of entries) {
            const fullPath = path.join(dir, entry.name);
            if (entry.isDirectory() && recursive) {
                walk(fullPath);
            } else if (entry.isFile()) {
                if (SUPPORTED_INPUT.includes(path.extname(entry.name).toLowerCase())) {
                    files.push(fullPath);
                }
            }
        }
    };
    walk(root);
    return files;
}

async function main() {
    const argv = yargs(hideBin(process.argv))
        .usage('Использование: $0 <вход> [--output FILE|DIR] [--codec h264|hevc|vp9|av1] [--audio-codec aac|mp3|opus|ac3] [--crf N] [--video-bitrate N] [--audio-bitrate N] [--size WxH] [--fps N] [--preset PRESET] [--two-pass] [--normalize] [--subtitles FILE] [--recursive] [--threads N] [--overwrite] [--formats]')
        .demandCommand(1)
        .argv;

    if (argv.formats) {
        console.log('Входные форматы:', SUPPORTED_INPUT.join(', '));
        console.log('Видеокодеки:', Object.keys(VIDEO_CODECS).join(', '));
        console.log('Аудиокодеки:', AUDIO_CODECS.join(', '));
        console.log('Пресеты:', PRESETS.join(', '));
        return;
    }

    checkFFmpeg();

    if (argv.crf !== undefined && argv.videoBitrate) {
        console.error('Ошибка: укажите либо --crf, либо --video-bitrate, но не оба.');
        process.exit(1);
    }

    const source = argv._[0];
    const output = argv.output || null;
    const videoCodec = argv.codec || 'h264';
    const audioCodec = argv.audioCodec || 'aac';
    const crf = argv.crf !== undefined ? argv.crf : null;
    const videoBitrate = argv.videoBitrate || null;
    const audioBitrate = argv.audioBitrate || null;
    const size = argv.size || null;
    const fps = argv.fps || null;
    const preset = argv.preset || 'medium';
    const twoPass = argv.twoPass || false;
    const normalize = argv.normalize || false;
    const subtitles = argv.subtitles || null;
    const recursive = argv.recursive || false;
    const threads = argv.threads || 4;
    const overwrite = argv.overwrite || false;

    const files = findVideoFiles(source, recursive);
    if (files.length === 0) {
        console.log(`Не найдено видеофайлов в ${source}`);
        process.exit(1);
    }

    const outDir = output ? (fs.existsSync(output) && fs.statSync(output).isDirectory() ? output : path.dirname(output)) : './compressed';
    if (!fs.existsSync(outDir)) {
        fs.mkdirSync(outDir, { recursive: true });
    }

    const total = files.length;
    console.log(`Найдено ${total} видеофайлов.`);
    let success = 0;
    const concurrency = Math.min(threads, files.length);
    const queue = [...files];
    const results = [];

    await Promise.all(Array.from({ length: concurrency }, async () => {
        while (queue.length > 0) {
            const inputFile = queue.shift();
            let outFile;
            if (output && !fs.existsSync(output)) {
                outFile = output;
            } else {
                const rel = path.relative(source, inputFile) || path.basename(inputFile);
                outFile = path.join(outDir, path.dirname(rel), path.basename(rel, path.extname(rel)) + '.mp4');
            }
            if (fs.existsSync(outFile) && !overwrite) {
                results.push(`${outFile} уже существует, пропуск.`);
                continue;
            }
            if (!fs.existsSync(path.dirname(outFile))) {
                fs.mkdirSync(path.dirname(outFile), { recursive: true });
            }
            const ok = await convertFile(inputFile, outFile, videoCodec, audioCodec, crf, videoBitrate, audioBitrate,
                                         size, fps, preset, twoPass, normalize, subtitles);
            if (ok) {
                success++;
                results.push(`Конвертация ${inputFile} -> ${outFile}`);
            } else {
                results.push(`Ошибка при конвертации ${inputFile}`);
            }
        }
    }));

    results.forEach((msg, i) => console.log(`[${i+1}/${total}] ${msg}`));
    console.log(`Готово! Успешно: ${success}, Всего: ${total}`);
}

main().catch(console.error);
