#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p target/classes
javac -encoding UTF-8 -d target/classes src/main/java/YouTubeMusicPlayer.java
exec java -cp target/classes YouTubeMusicPlayer
