# video_compress.rb
require 'find'
require 'optparse'
require 'shellwords'

INPUT_EXTS = ['.mp4', '.mkv', '.avi', '.mov', '.wmv', '.flv', '.webm', '.m4v', '.3gp', '.ogv']
VIDEO_CODECS = { 'h264' => 'libx264', 'hevc' => 'libx265', 'vp9' => 'libvpx-vp9', 'av1' => 'libaom-av1' }
AUDIO_CODECS = ['aac', 'mp3', 'opus', 'ac3']
PRESETS = ['ultrafast', 'superfast', 'veryfast', 'faster', 'fast', 'medium', 'slow', 'slower', 'veryslow']

def check_ffmpeg
  system('ffmpeg -version > /dev/null 2>&1') or begin
    $stderr.puts "Ошибка: ffmpeg не найден. Установите FFmpeg и добавьте в PATH."
    exit 1
  end
end

def build_ffmpeg_cmd(input, output, video_codec, audio_codec, crf, video_bitrate, audio_bitrate,
                     size, fps, preset, normalize, subtitles, pass = nil)
  cmd = ['ffmpeg', '-i', input, '-y']
  cmd += ['-c:v', VIDEO_CODECS[video_codec] || 'libx264']
  cmd += ['-c:a', audio_codec || 'aac']
  if crf
    cmd += ['-crf', crf.to_s]
  elsif video_bitrate
    cmd += ['-b:v', video_bitrate]
  end
  cmd += ['-b:a', audio_bitrate] if audio_bitrate
  cmd += ['-vf', "scale=#{size}:flags=lanczos"] if size
  cmd += ['-r', fps.to_s] if fps
  cmd += ['-preset', preset] if preset && PRESETS.include?(preset)
  cmd += ['-af', 'loudnorm=I=-16:LRA=11:TP=-1.5'] if normalize
  cmd += ['-vf', "subtitles=#{subtitles}"] if subtitles
  if pass
    cmd += ['-pass', pass.to_s]
    cmd += ['-f', 'null', '/dev/null'] if pass == 1
  end
  cmd += [output]
  cmd
end

def convert_file(input, output, video_codec, audio_codec, crf, video_bitrate, audio_bitrate,
                 size, fps, preset, two_pass, normalize, subtitles)
  if two_pass && video_bitrate
    pass1_cmd = build_ffmpeg_cmd(input, '/dev/null', video_codec, audio_codec, crf, video_bitrate,
                                 audio_bitrate, size, fps, preset, normalize, subtitles, 1)
    system(*pass1_cmd) or return false
    pass2_cmd = build_ffmpeg_cmd(input, output, video_codec, audio_codec, crf, video_bitrate,
                                 audio_bitrate, size, fps, preset, normalize, subtitles, 2)
    system(*pass2_cmd)
  else
    cmd = build_ffmpeg_cmd(input, output, video_codec, audio_codec, crf, video_bitrate,
                           audio_bitrate, size, fps, preset, normalize, subtitles)
    system(*cmd)
  end
end

def find_video_files(root, recursive)
  files = []
  if File.file?(root) && INPUT_EXTS.include?(File.extname(root).downcase)
    return [root]
  end
  return files unless File.directory?(root)
  if recursive
    Find.find(root) do |path|
      files << path if File.file?(path) && INPUT_EXTS.include?(File.extname(path).downcase)
    end
  else
    Dir.glob(File.join(root, '*')).each do |path|
      files << path if File.file?(path) && INPUT_EXTS.include?(File.extname(path).downcase)
    end
  end
  files
end

options = {}
OptionParser.new do |opts|
  opts.banner = "Использование: ruby video_compress.rb <вход> [опции]"
  opts.on("--output FILE|DIR", "Выходной файл или папка") { |v| options[:output] = v }
  opts.on("--codec CODEC", "Видеокодек (h264, hevc, vp9, av1)") { |v| options[:codec] = v }
  opts.on("--audio-codec CODEC", "Аудиокодек (aac, mp3, opus, ac3)") { |v| options[:audio_codec] = v }
  opts.on("--crf N", Integer, "CRF (0-51)") { |v| options[:crf] = v }
  opts.on("--video-bitrate N", "Битрейт видео") { |v| options[:video_bitrate] = v }
  opts.on("--audio-bitrate N", "Битрейт аудио") { |v| options[:audio_bitrate] = v }
  opts.on("--size WxH", "Разрешение") { |v| options[:size] = v }
  opts.on("--fps N", Integer, "Частота кадров") { |v| options[:fps] = v }
  opts.on("--preset PRESET", "Пресет скорости") { |v| options[:preset] = v }
  opts.on("--two-pass", "Двухпроходное кодирование") { options[:two_pass] = true }
  opts.on("--normalize", "Нормализация") { options[:normalize] = true }
  opts.on("--subtitles FILE", "Файл субтитров") { |v| options[:subtitles] = v }
  opts.on("--recursive", "Рекурсивный обход") { options[:recursive] = true }
  opts.on("--threads N", Integer, "Потоки") { |v| options[:threads] = v }
  opts.on("--overwrite", "Перезаписывать") { options[:overwrite] = true }
  opts.on("--formats", "Показать форматы") { options[:formats] = true }
end.parse!

if options[:formats]
  puts "Входные форматы: #{INPUT_EXTS.join(', ')}"
  puts "Видеокодеки: #{VIDEO_CODECS.keys.join(', ')}"
  puts "Аудиокодеки: #{AUDIO_CODECS.join(', ')}"
  puts "Пресеты: #{PRESETS.join(', ')}"
  exit
end

check_ffmpeg

if options[:crf] && options[:video_bitrate]
  puts "Ошибка: укажите либо --crf, либо --video-bitrate, но не оба."
  exit 1
end

source = ARGV[0]
unless source
  puts "Укажите входной файл или папку."
  exit 1
end

output = options[:output]
video_codec = options[:codec] || 'h264'
audio_codec = options[:audio_codec] || 'aac'
crf = options[:crf]
video_bitrate = options[:video_bitrate]
audio_bitrate = options[:audio_bitrate]
size = options[:size]
fps = options[:fps]
preset = options[:preset] || 'medium'
two_pass = options[:two_pass] || false
normalize = options[:normalize] || false
subtitles = options[:subtitles]
recursive = options[:recursive] || false
threads = options[:threads] || 4
overwrite = options[:overwrite] || false

unless VIDEO_CODECS.key?(video_codec)
  puts "Неизвестный видеокодек: #{video_codec}"
  exit 1
end
unless AUDIO_CODECS.include?(audio_codec)
  puts "Неизвестный аудиокодек: #{audio_codec}"
  exit 1
end

files = find_video_files(source, recursive)
if files.empty?
  puts "Не найдено видеофайлов в #{source}"
  exit 1
end

out_dir = output && File.directory?(output) ? output : (output ? File.dirname(output) : './compressed')
Dir.mkdir(out_dir) unless Dir.exist?(out_dir)
total = files.size
puts "Найдено #{total} видеофайлов."

queue = Queue.new
files.each { |f| queue << f }
success = 0
mutex = Mutex.new
threads.times.map do
  Thread.new do
    while !queue.empty? && (input_file = queue.pop(true) rescue nil)
      rel = Pathname.new(input_file).relative_path_from(Pathname.new(source)).to_s rescue File.basename(input_file)
      out_path = output && !File.directory?(output) ? output : File.join(out_dir, File.basename(rel, '.*') + '.mp4')
      if File.exist?(out_path) && !overwrite
        mutex.synchronize { puts "#{out_path} уже существует, пропуск." }
        next
      end
      FileUtils.mkdir_p(File.dirname(out_path))
      mutex.synchronize { puts "Конвертация #{input_file} -> #{out_path}" }
      if convert_file(input_file, out_path, video_codec, audio_codec, crf, video_bitrate, audio_bitrate,
                      size, fps, preset, two_pass, normalize, subtitles)
        mutex.synchronize { success += 1 }
      else
        mutex.synchronize { puts "Ошибка при конвертации #{input_file}" }
      end
    end
  end
end.each(&:join)

puts "Готово! Успешно: #{success}, Всего: #{total}"
