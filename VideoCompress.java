// VideoCompress.java
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class VideoCompress {
    private static final Set<String> INPUT_EXTS = new HashSet<>(Arrays.asList(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp", ".ogv"));
    private static final Map<String, String> VIDEO_CODECS = new HashMap<>();
    private static final Set<String> AUDIO_CODECS = new HashSet<>(Arrays.asList("aac", "mp3", "opus", "ac3"));
    private static final Set<String> PRESETS = new HashSet<>(Arrays.asList("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow"));

    static {
        VIDEO_CODECS.put("h264", "libx264");
        VIDEO_CODECS.put("hevc", "libx265");
        VIDEO_CODECS.put("vp9", "libvpx-vp9");
        VIDEO_CODECS.put("av1", "libaom-av1");
    }

    public static void checkFFmpeg() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ffmpeg", "-version"});
            if (p.waitFor() != 0) {
                throw new Exception();
            }
        } catch (Exception e) {
            System.err.println("Ошибка: ffmpeg не найден. Установите FFmpeg и добавьте в PATH.");
            System.exit(1);
        }
    }

    public static String[] buildFFmpegCmd(String input, String output, String videoCodec, String audioCodec,
                                          int crf, String videoBitrate, String audioBitrate, String size, int fps,
                                          String preset, boolean normalize, String subtitles, int pass) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-i");
        cmd.add(input);
        cmd.add("-y");
        cmd.add("-c:v");
        cmd.add(VIDEO_CODECS.getOrDefault(videoCodec, "libx264"));
        cmd.add("-c:a");
        cmd.add(audioCodec != null ? audioCodec : "aac");
        if (crf >= 0) {
            cmd.add("-crf");
            cmd.add(String.valueOf(crf));
        } else if (videoBitrate != null && !videoBitrate.isEmpty()) {
            cmd.add("-b:v");
            cmd.add(videoBitrate);
        }
        if (audioBitrate != null && !audioBitrate.isEmpty()) {
            cmd.add("-b:a");
            cmd.add(audioBitrate);
        }
        if (size != null && !size.isEmpty()) {
            cmd.add("-vf");
            cmd.add("scale=" + size + ":flags=lanczos");
        }
        if (fps > 0) {
            cmd.add("-r");
            cmd.add(String.valueOf(fps));
        }
        if (preset != null && PRESETS.contains(preset)) {
            cmd.add("-preset");
            cmd.add(preset);
        }
        if (normalize) {
            cmd.add("-af");
            cmd.add("loudnorm=I=-16:LRA=11:TP=-1.5");
        }
        if (subtitles != null && !subtitles.isEmpty()) {
            cmd.add("-vf");
            cmd.add("subtitles=" + subtitles);
        }
        if (pass > 0) {
            cmd.add("-pass");
            cmd.add(String.valueOf(pass));
            if (pass == 1) {
                cmd.add("-f");
                cmd.add("null");
                cmd.add("/dev/null");
            }
        }
        cmd.add(output);
        return cmd.toArray(new String[0]);
    }

    public static boolean convertFile(String input, String output, String videoCodec, String audioCodec,
                                      int crf, String videoBitrate, String audioBitrate, String size, int fps,
                                      String preset, boolean twoPass, boolean normalize, String subtitles) {
        if (twoPass && videoBitrate != null && !videoBitrate.isEmpty()) {
            // Первый проход
            String[] pass1Cmd = buildFFmpegCmd(input, "/dev/null", videoCodec, audioCodec, crf, videoBitrate,
                                               audioBitrate, size, fps, preset, normalize, subtitles, 1);
            try {
                Process p1 = Runtime.getRuntime().exec(pass1Cmd);
                if (p1.waitFor() != 0) return false;
            } catch (Exception e) {
                System.err.println("  Ошибка прохода 1: " + e.getMessage());
                return false;
            }
            // Второй проход
            String[] pass2Cmd = buildFFmpegCmd(input, output, videoCodec, audioCodec, crf, videoBitrate,
                                               audioBitrate, size, fps, preset, normalize, subtitles, 2);
            try {
                Process p2 = Runtime.getRuntime().exec(pass2Cmd);
                return p2.waitFor() == 0;
            } catch (Exception e) {
                System.err.println("  Ошибка прохода 2: " + e.getMessage());
                return false;
            }
        } else {
            String[] cmd = buildFFmpegCmd(input, output, videoCodec, audioCodec, crf, videoBitrate,
                                          audioBitrate, size, fps, preset, normalize, subtitles, 0);
            try {
                Process p = Runtime.getRuntime().exec(cmd);
                return p.waitFor() == 0;
            } catch (Exception e) {
                System.err.println("  Ошибка: " + e.getMessage());
                return false;
            }
        }
    }

    public static List<Path> findVideoFiles(String root, boolean recursive) throws IOException {
        List<Path> files = new ArrayList<>();
        Path path = Paths.get(root);
        if (Files.isRegularFile(path)) {
            String ext = getExtension(path.toString()).toLowerCase();
            if (INPUT_EXTS.contains(ext)) {
                files.add(path);
            }
            return files;
        }
        if (!Files.isDirectory(path)) return files;
        if (recursive) {
            Files.walk(path)
                .filter(p -> Files.isRegularFile(p))
                .forEach(p -> {
                    String ext = getExtension(p.toString()).toLowerCase();
                    if (INPUT_EXTS.contains(ext)) files.add(p);
                });
        } else {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path p : stream) {
                    if (Files.isRegularFile(p)) {
                        String ext = getExtension(p.toString()).toLowerCase();
                        if (INPUT_EXTS.contains(ext)) files.add(p);
                    }
                }
            }
        }
        return files;
    }

    private static String getExtension(String path) {
        int i = path.lastIndexOf('.');
        return i > 0 ? path.substring(i) : "";
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Использование: java VideoCompress <вход> [--output FILE|DIR] [--codec h264|hevc|vp9|av1] [--audio-codec aac|mp3|opus|ac3] [--crf N] [--video-bitrate N] [--audio-bitrate N] [--size WxH] [--fps N] [--preset PRESET] [--two-pass] [--normalize] [--subtitles FILE] [--recursive] [--threads N] [--overwrite] [--formats]");
            System.exit(1);
        }
        if (args[0].equals("--formats")) {
            System.out.println("Входные форматы: " + INPUT_EXTS);
            System.out.println("Видеокодеки: " + VIDEO_CODECS.keySet());
            System.out.println("Аудиокодеки: " + AUDIO_CODECS);
            System.out.println("Пресеты: " + PRESETS);
            return;
        }

        checkFFmpeg();

        String source = args[0];
        String output = null;
        String videoCodec = "h264";
        String audioCodec = "aac";
        int crf = -1;
        String videoBitrate = null;
        String audioBitrate = null;
        String size = null;
        int fps = 0;
        String preset = "medium";
        boolean twoPass = false;
        boolean normalize = false;
        String subtitles = null;
        boolean recursive = false;
        int threads = 4;
        boolean overwrite = false;

        for (int i=1; i<args.length; i++) {
            if (args[i].equals("--output") && i+1 < args.length) {
                output = args[++i];
            } else if (args[i].equals("--codec") && i+1 < args.length) {
                videoCodec = args[++i];
            } else if (args[i].equals("--audio-codec") && i+1 < args.length) {
                audioCodec = args[++i];
            } else if (args[i].equals("--crf") && i+1 < args.length) {
                crf = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--video-bitrate") && i+1 < args.length) {
                videoBitrate = args[++i];
            } else if (args[i].equals("--audio-bitrate") && i+1 < args.length) {
                audioBitrate = args[++i];
            } else if (args[i].equals("--size") && i+1 < args.length) {
                size = args[++i];
            } else if (args[i].equals("--fps") && i+1 < args.length) {
                fps = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--preset") && i+1 < args.length) {
                preset = args[++i];
            } else if (args[i].equals("--two-pass")) {
                twoPass = true;
            } else if (args[i].equals("--normalize")) {
                normalize = true;
            } else if (args[i].equals("--subtitles") && i+1 < args.length) {
                subtitles = args[++i];
            } else if (args[i].equals("--recursive")) {
                recursive = true;
            } else if (args[i].equals("--threads") && i+1 < args.length) {
                threads = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--overwrite")) {
                overwrite = true;
            }
        }

        if (crf >= 0 && videoBitrate != null) {
            System.err.println("Ошибка: укажите либо --crf, либо --video-bitrate, но не оба.");
            System.exit(1);
        }
        if (!VIDEO_CODECS.containsKey(videoCodec)) {
            System.err.println("Неизвестный видеокодек: " + videoCodec);
            System.exit(1);
        }
        if (!AUDIO_CODECS.contains(audioCodec)) {
            System.err.println("Неизвестный аудиокодек: " + audioCodec);
            System.exit(1);
        }

        List<Path> files = findVideoFiles(source, recursive);
        if (files.isEmpty()) {
            System.out.println("Не найдено видеофайлов в " + source);
            System.exit(1);
        }

        String outDir = output != null && Files.isDirectory(Paths.get(output)) ? output : (output != null ? Paths.get(output).getParent().toString() : "./compressed");
        Files.createDirectories(Paths.get(outDir));
        int total = files.size();
        System.out.println("Найдено " + total + " видеофайлов.");

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();
        int[] success = {0};

        for (Path inputFile : files) {
            futures.add(executor.submit(() -> {
                Path rel = Paths.get(source).relativize(inputFile);
                if (rel.toString().isEmpty()) rel = inputFile.getFileName();
                String outPath = Paths.get(outDir, rel.toString().replaceFirst("\\.[^.]+$", "") + ".mp4").toString();
                if (output != null && !Files.isDirectory(Paths.get(output))) {
                    outPath = output;
                }
                if (Files.exists(Paths.get(outPath)) && !overwrite) {
                    return outPath + " уже существует, пропуск.";
                }
                Files.createDirectories(Paths.get(outPath).getParent());
                boolean ok = convertFile(inputFile.toString(), outPath, videoCodec, audioCodec, crf, videoBitrate, audioBitrate,
                                         size, fps, preset, twoPass, normalize, subtitles);
                if (ok) {
                    success[0]++;
                    return "Конвертация " + inputFile + " -> " + outPath;
                }
                return "Ошибка при конвертации " + inputFile;
            }));
        }

        int i = 1;
        for (Future<String> f : futures) {
            System.out.println("[" + i++ + "/" + total + "] " + f.get());
        }
        executor.shutdown();
        System.out.printf("Готово! Успешно: %d, Всего: %d\n", success[0], total);
    }
}
