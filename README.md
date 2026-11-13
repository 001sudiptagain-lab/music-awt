# YouTube Music Player (Java)

A Java desktop music player with a dark, YouTube Music-inspired layout. Search YouTube, browse genre and trending-search shortcuts in Explore, and find recently played tracks in the device-local Library. Play a result in the app or open it in YouTube's own player. The interface uses Swing, while the app uses the open-source `yt-dlp` command-line program for search and stream lookup and FFmpeg's `ffplay` program for audio playback.

## You need

- JDK 8 or newer
- `yt-dlp` installed and available on PATH
- FFmpeg installed with `ffplay` available on PATH
- Internet access

On Windows, the project also checks for `tools/yt-dlp.exe` and `tools/ffplay.exe` before using PATH. You can put the two programs in that `tools` folder if you don't want to edit PATH.

Download `yt-dlp.exe` from the [official yt-dlp releases](https://github.com/yt-dlp/yt-dlp/releases). Get FFmpeg from the [official FFmpeg download page](https://ffmpeg.org/download.html); choose a Windows build that includes `ffplay.exe`.

## Run in VS Code or Antigravity on Windows

1. Install a JDK and install VS Code's **Extension Pack for Java** if you use VS Code.
2. Install `yt-dlp` and FFmpeg as described above.
3. Open this `YouTubeAwtMusicPlayer` folder in VS Code or Antigravity.
4. Run `run.bat`, or open `src/main/java/YouTubeMusicPlayer.java` and run its `main` method.
5. Search for a song and click a result to start playback. **Explore** offers genre and trending-search shortcuts; **Library** lists tracks played on this device. Previous and Next move through the current list, and the timeline can seek within tracks that report a duration. **Open on YouTube** opens the selected result in your browser.

If the app says a command was not found, add its folder to PATH and restart VS Code, or place the executable in the project's `tools` folder.

## Run from a terminal

```text
javac -encoding UTF-8 -d target/classes src/main/java/YouTubeMusicPlayer.java
java -cp target/classes YouTubeMusicPlayer
```

## Important

YouTube and third-party extraction tools can change how search and playback work. Search/playback can fail for videos with restrictions, and `yt-dlp` may need updating. Use the app for content you are allowed to access and follow YouTube's terms and applicable copyright rules. The **Open on YouTube** button uses YouTube's own player in your browser.
