// video_compress.go
package main

import (
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
)

var inputExts = map[string]bool{
	".mp4": true, ".mkv": true, ".avi": true, ".mov": true,
	".wmv": true, ".flv": true, ".webm": true, ".m4v": true,
	".3gp": true, ".ogv": true,
}
var videoCodecs = map[string]string{
	"h264": "libx264",
	"hevc": "libx265",
	"vp9":  "libvpx-vp9",
	"av1":  "libaom-av1",
}
var audioCodecs = map[string]bool{
	"aac": true, "mp3": true, "opus": true, "ac3": true,
}
var presets = map[string]bool{
	"ultrafast": true, "superfast": true, "veryfast": true,
	"faster": true, "fast": true, "medium": true,
	"slow": true, "slower": true, "veryslow": true,
}

func checkFFmpeg() {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		fmt.Fprintln(os.Stderr, "Ошибка: ffmpeg не найден. Установите FFmpeg и добавьте в PATH.")
		os.Exit(1)
	}
}

func buildFFmpegCmd(input, output, videoCodec, audioCodec string, crf int, videoBitrate, audioBitrate, size string, fps int, preset string, normalize bool, subtitles string, pass int) []string {
	cmd := []string{"ffmpeg", "-i", input, "-y"}
	cmd = append(cmd, "-c:v", videoCodecs[videoCodec])
	cmd = append(cmd, "-c:a", audioCodec)
	if crf >= 0 {
		cmd = append(cmd, "-crf", fmt.Sprintf("%d", crf))
	} else if videoBitrate != "" {
		cmd = append(cmd, "-b:v", videoBitrate)
	}
	if audioBitrate != "" {
		cmd = append(cmd, "-b:a", audioBitrate)
	}
	if size != "" {
		cmd = append(cmd, "-vf", fmt.Sprintf("scale=%s:flags=lanczos", size))
	}
	if fps > 0 {
		cmd = append(cmd, "-r", fmt.Sprintf("%d", fps))
	}
	if preset != "" && presets[preset] {
		cmd = append(cmd, "-preset", preset)
	}
	if normalize {
		cmd = append(cmd, "-af", "loudnorm=I=-16:LRA=11:TP=-1.5")
	}
	if subtitles != "" {
		cmd = append(cmd, "-vf", fmt.Sprintf("subtitles=%s", subtitles))
	}
	if pass > 0 {
		cmd = append(cmd, "-pass", fmt.Sprintf("%d", pass))
		if pass == 1 {
			cmd = append(cmd, "-f", "null", "/dev/null")
		}
	}
	cmd = append(cmd, output)
	return cmd
}

func convertFile(input, output, videoCodec, audioCodec string, crf int, videoBitrate, audioBitrate, size string, fps int, preset string, twoPass bool, normalize bool, subtitles string) bool {
	if twoPass && videoBitrate != "" {
		// Первый проход
		pass1Cmd := buildFFmpegCmd(input, "/dev/null", videoCodec, audioCodec, crf, videoBitrate, audioBitrate, size, fps, preset, normalize, subtitles, 1)
		cmd1 := exec.Command(pass1Cmd[0], pass1Cmd[1:]...)
		if err := cmd1.Run(); err != nil {
			fmt.Printf("  Ошибка прохода 1: %v\n", err)
			return false
		}
		// Второй проход
		pass2Cmd := buildFFmpegCmd(input, output, videoCodec, audioCodec, crf, videoBitrate, audioBitrate, size, fps, preset, normalize, subtitles, 2)
		cmd2 := exec.Command(pass2Cmd[0], pass2Cmd[1:]...)
		if err := cmd2.Run(); err != nil {
			fmt.Printf("  Ошибка прохода 2: %v\n", err)
			return false
		}
		return true
	} else {
		cmdArgs := buildFFmpegCmd(input, output, videoCodec, audioCodec, crf, videoBitrate, audioBitrate, size, fps, preset, normalize, subtitles, 0)
		cmd := exec.Command(cmdArgs[0], cmdArgs[1:]...)
		if err := cmd.Run(); err != nil {
			fmt.Printf("  Ошибка: %v\n", err)
			return false
		}
		return true
	}
}

func findVideoFiles(root string, recursive bool) []string {
	var files []string
	info, err := os.Stat(root)
	if err != nil {
		return files
	}
	if !info.IsDir() {
		ext := strings.ToLower(filepath.Ext(root))
		if inputExts[ext] {
			files = append(files, root)
		}
		return files
	}
	walkFn := func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return nil
		}
		if info.IsDir() && !recursive && path != root {
			return filepath.SkipDir
		}
		if !info.IsDir() {
			ext := strings.ToLower(filepath.Ext(path))
			if inputExts[ext] {
				files = append(files, path)
			}
		}
		return nil
	}
	filepath.Walk(root, walkFn)
	return files
}

func main() {
	var output string
	var videoCodec string
	var audioCodec string
	var crf int
	var videoBitrate string
	var audioBitrate string
	var size string
	var fps int
	var preset string
	var twoPass bool
	var normalize bool
	var subtitles string
	var recursive bool
	var threads int
	var overwrite bool
	var formats bool

	flag.StringVar(&output, "output", "", "Выходной файл или папка")
	flag.StringVar(&videoCodec, "codec", "h264", "Видеокодек (h264, hevc, vp9, av1)")
	flag.StringVar(&audioCodec, "audio-codec", "aac", "Аудиокодек (aac, mp3, opus, ac3)")
	flag.IntVar(&crf, "crf", -1, "CRF (0-51)")
	flag.StringVar(&videoBitrate, "video-bitrate", "", "Битрейт видео")
	flag.StringVar(&audioBitrate, "audio-bitrate", "", "Битрейт аудио")
	flag.StringVar(&size, "size", "", "Разрешение (ШxВ)")
	flag.IntVar(&fps, "fps", 0, "Частота кадров")
	flag.StringVar(&preset, "preset", "medium", "Пресет скорости")
	flag.BoolVar(&twoPass, "two-pass", false, "Двухпроходное кодирование")
	flag.BoolVar(&normalize, "normalize", false, "Нормализация")
	flag.StringVar(&subtitles, "subtitles", "", "Файл субтитров")
	flag.BoolVar(&recursive, "recursive", false, "Рекурсивный обход")
	flag.IntVar(&threads, "threads", 4, "Количество потоков")
	flag.BoolVar(&overwrite, "overwrite", false, "Перезаписывать")
	flag.BoolVar(&formats, "formats", false, "Показать форматы")
	flag.Parse()

	if formats {
		fmt.Println("Входные форматы:", keys(inputExts))
		fmt.Println("Видеокодеки:", keys(videoCodecs))
		fmt.Println("Аудиокодеки:", keys(audioCodecs))
		fmt.Println("Пресеты:", keys(presets))
		return
	}

	checkFFmpeg()

	if crf >= 0 && videoBitrate != "" {
		fmt.Println("Ошибка: укажите либо --crf, либо --video-bitrate, но не оба.")
		os.Exit(1)
	}

	args := flag.Args()
	if len(args) == 0 {
		fmt.Println("Укажите входной файл или папку")
		os.Exit(1)
	}
	source := args[0]

	if _, ok := videoCodecs[videoCodec]; !ok {
		fmt.Printf("Неизвестный видеокодек: %s\n", videoCodec)
		os.Exit(1)
	}
	if _, ok := audioCodecs[audioCodec]; !ok {
		fmt.Printf("Неизвестный аудиокодек: %s\n", audioCodec)
		os.Exit(1)
	}

	files := findVideoFiles(source, recursive)
	if len(files) == 0 {
		fmt.Printf("Не найдено видеофайлов в %s\n", source)
		os.Exit(1)
	}

	outDir := output
	if outDir == "" {
		outDir = "./compressed"
	}
	if err := os.MkdirAll(outDir, 0755); err != nil {
		fmt.Printf("Ошибка создания папки: %v\n", err)
		os.Exit(1)
	}

	total := len(files)
	fmt.Printf("Найдено %d видеофайлов.\n", total)
	var wg sync.WaitGroup
	sem := make(chan struct{}, threads)
	success := 0
	var mu sync.Mutex

	for i, inputFile := range files {
		wg.Add(1)
		go func(idx int, inPath string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			var outPath string
			if output != "" && !isDir(output) {
				outPath = output
			} else {
				rel, _ := filepath.Rel(source, inPath)
				if rel == "." {
					rel = filepath.Base(inPath)
				}
				outPath = filepath.Join(outDir, strings.TrimSuffix(rel, filepath.Ext(rel))+".mp4")
			}
			if _, err := os.Stat(outPath); err == nil && !overwrite {
				fmt.Printf("[%d/%d] %s уже существует, пропуск.\n", idx+1, total, outPath)
				return
			}
			if err := os.MkdirAll(filepath.Dir(outPath), 0755); err != nil {
				fmt.Printf("[%d/%d] Ошибка создания папки: %v\n", idx+1, total, err)
				return
			}
			fmt.Printf("[%d/%d] Конвертация %s -> %s\n", idx+1, total, inPath, outPath)
			if convertFile(inPath, outPath, videoCodec, audioCodec, crf, videoBitrate, audioBitrate, size, fps, preset, twoPass, normalize, subtitles) {
				mu.Lock()
				success++
				mu.Unlock()
			}
		}(i, inputFile)
	}
	wg.Wait()
	fmt.Printf("Готово! Успешно: %d, Всего: %d\n", success, total)
}

func keys(m map[string]bool) []string {
	var k []string
	for key := range m {
		k = append(k, key)
	}
	return k
}

func isDir(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}
