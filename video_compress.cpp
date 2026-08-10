// video_compress.cpp
#include <iostream>
#include <string>
#include <vector>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <regex>
#include <cstdlib>
#include <thread>
#include <mutex>
#include <queue>

namespace fs = std::filesystem;
using namespace std;

const vector<string> INPUT_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp", ".ogv"};
const map<string, string> VIDEO_CODECS = {{"h264", "libx264"}, {"hevc", "libx265"}, {"vp9", "libvpx-vp9"}, {"av1", "libaom-av1"}};
const vector<string> AUDIO_CODECS = {"aac", "mp3", "opus", "ac3"};
const vector<string> PRESETS = {"ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow"};

bool checkFFmpeg() {
    return system("ffmpeg -version > /dev/null 2>&1") == 0;
}

string buildFFmpegCmd(const string& input, const string& output, const string& video_codec,
                      const string& audio_codec, int crf, const string& video_bitrate, const string& audio_bitrate,
                      const string& size, int fps, const string& preset, bool normalize, const string& subtitles, int pass = 0) {
    stringstream cmd;
    cmd << "ffmpeg -i " << input << " -y";
    auto it = VIDEO_CODECS.find(video_codec);
    string vcodec = (it != VIDEO_CODECS.end()) ? it->second : "libx264";
    cmd << " -c:v " << vcodec;
    cmd << " -c:a " << audio_codec;
    if (crf >= 0) {
        cmd << " -crf " << crf;
    } else if (!video_bitrate.empty()) {
        cmd << " -b:v " << video_bitrate;
    }
    if (!audio_bitrate.empty()) cmd << " -b:a " << audio_bitrate;
    if (!size.empty()) cmd << " -vf scale=" << size << ":flags=lanczos";
    if (fps > 0) cmd << " -r " << fps;
    if (!preset.empty() && find(PRESETS.begin(), PRESETS.end(), preset) != PRESETS.end()) {
        cmd << " -preset " << preset;
    }
    if (normalize) cmd << " -af loudnorm=I=-16:LRA=11:TP=-1.5";
    if (!subtitles.empty()) cmd << " -vf subtitles=" << subtitles;
    if (pass > 0) {
        cmd << " -pass " << pass;
        if (pass == 1) {
            cmd << " -f null /dev/null";
        }
    }
    cmd << " " << output;
    return cmd.str();
}

bool convertFile(const string& input, const string& output, const string& video_codec,
                 const string& audio_codec, int crf, const string& video_bitrate, const string& audio_bitrate,
                 const string& size, int fps, const string& preset, bool two_pass,
                 bool normalize, const string& subtitles) {
    if (two_pass && !video_bitrate.empty()) {
        string pass1_cmd = buildFFmpegCmd(input, "/dev/null", video_codec, audio_codec, crf, video_bitrate,
                                          audio_bitrate, size, fps, preset, normalize, subtitles, 1);
        if (system(pass1_cmd.c_str()) != 0) return false;
        string pass2_cmd = buildFFmpegCmd(input, output, video_codec, audio_codec, crf, video_bitrate,
                                          audio_bitrate, size, fps, preset, normalize, subtitles, 2);
        return system(pass2_cmd.c_str()) == 0;
    } else {
        string cmd = buildFFmpegCmd(input, output, video_codec, audio_codec, crf, video_bitrate,
                                    audio_bitrate, size, fps, preset, normalize, subtitles);
        return system(cmd.c_str()) == 0;
    }
}

vector<string> findVideoFiles(const string& root, bool recursive) {
    vector<string> files;
    fs::path path(root);
    if (fs::is_regular_file(path)) {
        string ext = path.extension().string();
        for (const auto& e : INPUT_EXTS) {
            if (ext == e) { files.push_back(path.string()); break; }
        }
        return files;
    }
    if (!fs::is_directory(path)) return files;
    for (auto& entry : fs::directory_iterator(path)) {
        if (entry.is_regular_file()) {
            string ext = entry.path().extension().string();
            for (const auto& e : INPUT_EXTS) {
                if (ext == e) { files.push_back(entry.path().string()); break; }
            }
        }
        if (recursive && entry.is_directory()) {
            auto sub = findVideoFiles(entry.path().string(), true);
            files.insert(files.end(), sub.begin(), sub.end());
        }
    }
    return files;
}

int main(int argc, char* argv[]) {
    if (argc < 2) {
        cerr << "Использование: video_compress <вход> [--output FILE|DIR] [--codec h264|hevc|vp9|av1] [--audio-codec aac|mp3|opus|ac3] [--crf N] [--video-bitrate N] [--audio-bitrate N] [--size WxH] [--fps N] [--preset PRESET] [--two-pass] [--normalize] [--subtitles FILE] [--recursive] [--threads N] [--overwrite] [--formats]" << endl;
        return 1;
    }

    if (string(argv[1]) == "--formats") {
        cout << "Входные форматы: ";
        for (auto& e : INPUT_EXTS) cout << e << " ";
        cout << endl;
        cout << "Видеокодеки: ";
        for (auto& kv : VIDEO_CODECS) cout << kv.first << " ";
        cout << endl;
        cout << "Аудиокодеки: ";
        for (auto& e : AUDIO_CODECS) cout << e << " ";
        cout << endl;
        cout << "Пресеты: ";
        for (auto& e : PRESETS) cout << e << " ";
        cout << endl;
        return 0;
    }

    if (!checkFFmpeg()) {
        cerr << "Ошибка: ffmpeg не найден. Установите FFmpeg и добавьте в PATH." << endl;
        return 1;
    }

    string source = argv[1];
    string output = "";
    string video_codec = "h264";
    string audio_codec = "aac";
    int crf = -1;
    string video_bitrate = "";
    string audio_bitrate = "";
    string size = "";
    int fps = 0;
    string preset = "medium";
    bool two_pass = false;
    bool normalize = false;
    string subtitles = "";
    bool recursive = false;
    int threads = 4;
    bool overwrite = false;

    for (int i=2; i<argc; ++i) {
        string arg = argv[i];
        if (arg == "--output" && i+1 < argc) output = argv[++i];
        else if (arg == "--codec" && i+1 < argc) video_codec = argv[++i];
        else if (arg == "--audio-codec" && i+1 < argc) audio_codec = argv[++i];
        else if (arg == "--crf" && i+1 < argc) crf = stoi(argv[++i]);
        else if (arg == "--video-bitrate" && i+1 < argc) video_bitrate = argv[++i];
        else if (arg == "--audio-bitrate" && i+1 < argc) audio_bitrate = argv[++i];
        else if (arg == "--size" && i+1 < argc) size = argv[++i];
        else if (arg == "--fps" && i+1 < argc) fps = stoi(argv[++i]);
        else if (arg == "--preset" && i+1 < argc) preset = argv[++i];
        else if (arg == "--two-pass") two_pass = true;
        else if (arg == "--normalize") normalize = true;
        else if (arg == "--subtitles" && i+1 < argc) subtitles = argv[++i];
        else if (arg == "--recursive") recursive = true;
        else if (arg == "--threads" && i+1 < argc) threads = stoi(argv[++i]);
        else if (arg == "--overwrite") overwrite = true;
    }

    if (crf >= 0 && !video_bitrate.empty()) {
        cerr << "Ошибка: укажите либо --crf, либо --video-bitrate, но не оба." << endl;
        return 1;
    }
    if (VIDEO_CODECS.find(video_codec) == VIDEO_CODECS.end()) {
        cerr << "Неизвестный видеокодек: " << video_codec << endl;
        return 1;
    }
    if (find(AUDIO_CODECS.begin(), AUDIO_CODECS.end(), audio_codec) == AUDIO_CODECS.end()) {
        cerr << "Неизвестный аудиокодек: " << audio_codec << endl;
        return 1;
    }

    auto files = findVideoFiles(source, recursive);
    if (files.empty()) {
        cout << "Не найдено видеофайлов в " << source << endl;
        return 1;
    }

    string outDir = output.empty() ? "./compressed" : (fs::is_directory(output) ? output : fs::path(output).parent_path().string());
    fs::create_directories(outDir);
    int total = files.size();
    cout << "Найдено " << total << " видеофайлов." << endl;

    queue<string> q;
    for (const auto& f : files) q.push(f);
    mutex mtx;
    int success = 0;
    vector<thread> workers;

    for (int t=0; t<threads; ++t) {
        workers.emplace_back([&]() {
            while (true) {
                string inputFile;
                {
                    lock_guard<mutex> lock(mtx);
                    if (q.empty()) break;
                    inputFile = q.front();
                    q.pop();
                }
                fs::path rel = fs::relative(inputFile, source);
                if (rel.empty()) rel = fs::path(inputFile).filename();
                string outPath = (fs::path(outDir) / rel).replace_extension(".mp4").string();
                if (output.empty() && !fs::is_directory(output)) {
                    outPath = output;
                }
                if (fs::exists(outPath) && !overwrite) {
                    lock_guard<mutex> lock(mtx);
                    cout << outPath << " уже существует, пропуск." << endl;
                    continue;
                }
                fs::create_directories(fs::path(outPath).parent_path());
                {
                    lock_guard<mutex> lock(mtx);
                    cout << "Конвертация " << inputFile << " -> " << outPath << endl;
                }
                if (convertFile(inputFile, outPath, video_codec, audio_codec, crf, video_bitrate, audio_bitrate,
                                size, fps, preset, two_pass, normalize, subtitles)) {
                    lock_guard<mutex> lock(mtx);
                    success++;
                } else {
                    lock_guard<mutex> lock(mtx);
                    cout << "Ошибка при конвертации " << inputFile << endl;
                }
            }
        });
    }
    for (auto& w : workers) w.join();
    cout << "Готово! Успешно: " << success << ", Всего: " << total << endl;
    return 0;
}
