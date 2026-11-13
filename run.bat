@echo off
setlocal
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 (
  if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
  where javac >nul 2>nul
  if errorlevel 1 (
    for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do (
      if exist "%%~fD\bin\javac.exe" (
        set "JAVA_HOME=%%~fD"
        set "PATH=%%~fD\bin;%PATH%"
      )
    )
  )
  where javac >nul 2>nul
  if errorlevel 1 (
    echo Java JDK was not found. Install a JDK 8 or newer, then reopen this folder.
    pause
    exit /b 1
  )
)
if not exist "target\classes" mkdir "target\classes"
javac -encoding UTF-8 -d "target\classes" "src\main\java\YouTubeMusicPlayer.java"
if errorlevel 1 (
  echo.
  echo Build failed. Check the error above.
  pause
  exit /b 1
)
java -cp "target\classes" YouTubeMusicPlayer
