// video_compress.cs
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

class VideoCompress
{
    private static readonly HashSet<string> InputExts = new HashSet<string>
        { ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp", ".ogv" };
    private static readonly Dictionary<string, string> VideoCodecs = new Dictionary<string, string>
        { {"h264", "libx264"}, {"hevc", "libx265"}, {"vp9", "libvpx-vp9"}, {"av1", "libaom-av1"} };
    private static readonly HashSet<string> AudioCodecs = new HashSet<string> { "aac", "mp3", "opus", "ac3" };
    private static readonly HashSet<string> Presets = new HashSet<string>
        { "ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow" };

    static void CheckFFmpeg()
    {
        try
        {
            var psi = new ProcessStartInfo("ffmpeg", "-version") { RedirectStandardOutput = true };
            using (var p = Process.Start(psi)) { p.WaitForExit(); }
        }
        catch
        {
            Console.Error.WriteLine("Ошибка: ffmpeg не найден. Установите FFmpeg и добавьте в PATH.");
            Environment.Exit(1);
        }
    }

    static string[] BuildFFmpegCmd(string input, string output, string videoCodec, string audioCodec,
                                   int crf, string videoBitrate, string audioBitrate, string size, int fps,
                                   string preset, bool normalize, string subtitles, int pass)
    {
        var cmd = new List<string>();
        cmd.Add("ffmpeg"); cmd.Add("-i"); cmd.Add(input); cmd.Add("-y");
        cmd.Add("-c:v"); cmd.Add(VideoCodecs[videoCodec]);
        cmd.Add("-c:a"); cmd.Add(audioCodec);
        if (crf >= 0) { cmd.Add("-crf"); cmd.Add(crf.ToString()); }
        else if (!string.IsNullOrEmpty(videoBitrate)) { cmd.Add("-b:v"); cmd.Add(videoBitrate); }
        if (!string.IsNullOrEmpty(audioBitrate)) { cmd.Add("-b:a"); cmd.Add(audioBitrate); }
        if (!string.IsNullOrEmpty(size)) { cmd.Add("-vf"); cmd.Add($"scale={size}:flags=lanczos"); }
        if (fps > 0) { cmd.Add("-r"); cmd.Add(fps.ToString()); }
        if (!string.IsNullOrEmpty(preset) && Presets.Contains(preset)) { cmd.Add("-preset"); cmd.Add(preset); }
        if (normalize) { cmd.Add("-af"); cmd.Add("loudnorm=I=-16:LRA=11:TP=-1.5"); }
        if (!string.IsNullOrEmpty(subtitles)) { cmd.Add("-vf"); cmd.Add($"subtitles={subtitles}"); }
        if (pass > 0) {
            cmd.Add("-pass"); cmd.Add(pass.ToString());
            if (pass == 1) { cmd.Add("-f"); cmd.Add("null"); cmd.Add("/dev/null"); }
        }
        cmd.Add(output);
        return cmd.ToArray();
    }

    static bool ConvertFile(string input, string output, string videoCodec, string audioCodec,
                            int crf, string videoBitrate, string audioBitrate, string size, int fps,
                            string preset, bool twoPass, bool normalize, string subtitles)
    {
        if (twoPass && !string.IsNullOrEmpty(videoBitrate))
        {
            var pass1Args = BuildFFmpegCmd(input, "/dev/null", videoCodec, audioCodec, crf, videoBitrate,
                                           audioBitrate, size, fps, preset, normalize, subtitles, 1);
            var p1 = Process.Start("ffmpeg", string.Join(" ", pass1Args.Skip(1)));
            p1.WaitForExit();
            if (p1.ExitCode != 0) return false;
            var pass2Args = BuildFFmpegCmd(input, output, videoCodec, audioCodec, crf, videoBitrate,
                                           audioBitrate, size, fps, preset, normalize, subtitles, 2);
            var p2 = Process.Start("ffmpeg", string.Join(" ", pass2Args.Skip(1)));
            p2.WaitForExit();
            return p2.ExitCode == 0;
        }
        else
        {
            var args = BuildFFmpegCmd(input, output, videoCodec, audioCodec, crf, videoBitrate,
                                      audioBitrate, size, fps, preset, normalize, subtitles, 0);
            var p = Process.Start("ffmpeg", string.Join(" ", args.Skip(1)));
            p.WaitForExit();
            return p.ExitCode == 0;
        }
    }

    static List<string> FindVideoFiles(string root, bool recursive)
    {
        var files = new List<string>();
        if (File.Exists(root))
        {
            var ext = Path.GetExtension(root).ToLower();
            if (InputExts.Contains(ext)) files.Add(root);
            return files;
        }
        if (!Directory.Exists(root)) return files;
        var option = recursive ? SearchOption.AllDirectories : SearchOption.TopDirectoryOnly;
        files.AddRange(Directory.GetFiles(root, "*.*", option)
            .Where(f => InputExts.Contains(Path.GetExtension(f).ToLower())));
        return files;
    }

    static async Task Main(string[] args)
    {
        if (args.Length == 0)
        {
            Console.Error.WriteLine("Использование: dotnet run <вход> [--output FILE|DIR] [--codec h264|hevc|vp9|av1] [--audio-codec aac|mp3|opus|ac3] [--crf N] [--video-bitrate N] [--audio-bitrate N] [--size WxH] [--fps N] [--preset PRESET] [--two-pass] [--normalize] [--subtitles FILE] [--recursive] [--threads N] [--overwrite] [--formats]");
            return;
        }
        if (args[0] == "--formats")
        {
            Console.WriteLine("Входные форматы: " + string.Join(", ", InputExts));
            Console.WriteLine("Видеокодеки: " + string.Join(", ", VideoCodecs.Keys));
            Console.WriteLine("Аудиокодеки: " + string.Join(", ", AudioCodecs));
            Console.WriteLine("Пресеты: " + string.Join(", ", Presets));
            return;
        }

        CheckFFmpeg();

        string source = args[0];
        string output = null;
        string videoCodec = "h264";
        string audioCodec = "aac";
        int crf = -1;
        string videoBitrate = null;
        string audioBitrate = null;
        string size = null;
        int fps = 0;
        string preset = "medium";
        bool twoPass = false;
        bool normalize = false;
        string subtitles = null;
        bool recursive = false;
        int threads = 4;
        bool overwrite = false;

        for (int i=1; i<args.Length; i++)
        {
            switch (args[i])
            {
                case "--output": if (i+1 < args.Length) output = args[++i]; break;
                case "--codec": if (i+1 < args.Length) videoCodec = args[++i]; break;
                case "--audio-codec": if (i+1 < args.Length) audioCodec = args[++i]; break;
                case "--crf": if (i+1 < args.Length) crf = int.Parse(args[++i]); break;
                case "--video-bitrate": if (i+1 < args.Length) videoBitrate = args[++i]; break;
                case "--audio-bitrate": if (i+1 < args.Length) audioBitrate = args[++i]; break;
                case "--size": if (i+1 < args.Length) size = args[++i]; break;
                case "--fps": if (i+1 < args.Length) fps = int.Parse(args[++i]); break;
                case "--preset": if (i+1 < args.Length) preset = args[++i]; break;
                case "--two-pass": twoPass = true; break;
                case "--normalize": normalize = true; break;
                case "--subtitles": if (i+1 < args.Length) subtitles = args[++i]; break;
                case "--recursive": recursive = true; break;
                case "--threads": if (i+1 < args.Length) threads = int.Parse(args[++i]); break;
                case "--overwrite": overwrite = true; break;
            }
        }

        if (crf >= 0 && !string.IsNullOrEmpty(videoBitrate))
        {
            Console.Error.WriteLine("Ошибка: укажите либо --crf, либо --video-bitrate, но не оба.");
            Environment.Exit(1);
        }
        if (!VideoCodecs.ContainsKey(videoCodec))
        {
            Console.Error.WriteLine($"Неизвестный видеокодек: {videoCodec}");
            Environment.Exit(1);
        }
        if (!AudioCodecs.Contains(audioCodec))
        {
            Console.Error.WriteLine($"Неизвестный аудиокодек: {audioCodec}");
            Environment.Exit(1);
        }

        var files = FindVideoFiles(source, recursive);
        if (files.Count == 0)
        {
            Console.WriteLine($"Не найдено видеофайлов в {source}");
            return;
        }

        string outDir = output != null && Directory.Exists(output) ? output : (output != null ? Path.GetDirectoryName(output) : "./compressed");
        Directory.CreateDirectory(outDir);
        int total = files.Count;
        Console.WriteLine($"Найдено {total} видеофайлов.");
        var semaphore = new SemaphoreSlim(threads);
        var tasks = new List<Task>();
        int success = 0;

        foreach (var inputFile in files)
        {
            await semaphore.WaitAsync();
            tasks.Add(Task.Run(() =>
            {
                try
                {
                    var rel = Path.GetRelativePath(source, inputFile);
                    if (rel == ".") rel = Path.GetFileName(inputFile);
                    string outPath = Path.Combine(outDir, Path.ChangeExtension(rel, ".mp4"));
                    if (output != null && !Directory.Exists(output)) outPath = output;
                    if (File.Exists(outPath) && !overwrite)
                    {
                        lock (tasks) Console.WriteLine($"{outPath} уже существует, пропуск.");
                        return;
                    }
                    Directory.CreateDirectory(Path.GetDirectoryName(outPath));
                    if (ConvertFile(inputFile, outPath, videoCodec, audioCodec, crf, videoBitrate, audioBitrate,
                                    size, fps, preset, twoPass, normalize, subtitles))
                    {
                        Interlocked.Increment(ref success);
                        lock (tasks) Console.WriteLine($"Конвертация {inputFile} -> {outPath}");
                    }
                    else
                    {
                        lock (tasks) Console.WriteLine($"Ошибка при конвертации {inputFile}");
                    }
                }
                catch (Exception e)
                {
                    lock (tasks) Console.WriteLine($"  Ошибка: {e.Message}");
                }
                finally { semaphore.Release(); }
            }));
        }
        await Task.WhenAll(tasks);
        Console.WriteLine($"Готово! Успешно: {success}, Всего: {total}");
    }
}
