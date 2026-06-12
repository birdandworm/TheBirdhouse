@echo off
echo Building and launching RuneLite with The Birdhouse plugin...
echo.
set PATH=C:\tools\apache-maven-3.9.6\bin;%PATH%
cd /d C:\Birdhouse
mvn exec:java -Dexec.classpathScope=test -Dexec.mainClass="com.thebirdhouse.plugin.BirdhousePluginTest"
pause
