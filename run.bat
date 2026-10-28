@echo off
setlocal
cd /d "%~dp0"

if not exist "lib\sqlite-jdbc-3.47.1.0.jar" (
  echo Missing dependency: lib\sqlite-jdbc-3.47.1.0.jar
  echo Put the required dependency JAR files in the project's lib folder. See README.md.
  pause
  exit /b 1
)
if not exist "lib\pdfbox-3.0.4.jar" (
  echo Missing dependency: lib\pdfbox-3.0.4.jar
  echo Put the required dependency JAR files in the project's lib folder. See README.md.
  pause
  exit /b 1
)
if not exist "lib\pdfbox-io-3.0.4.jar" (
  echo Missing dependency: lib\pdfbox-io-3.0.4.jar
  echo Put the required dependency JAR files in the project's lib folder. See README.md.
  pause
  exit /b 1
)
if not exist "lib\fontbox-3.0.4.jar" (
  echo Missing dependency: lib\fontbox-3.0.4.jar
  echo Put the required dependency JAR files in the project's lib folder. See README.md.
  pause
  exit /b 1
)
if not exist "lib\commons-logging-1.3.4.jar" (
  echo Missing dependency: lib\commons-logging-1.3.4.jar
  echo Put the required dependency JAR files in the project's lib folder. See README.md.
  pause
  exit /b 1
)

if not exist out mkdir out
javac -encoding UTF-8 -cp "lib/*" -d out src\main\java\vn\hotel\HotelApp.java
if errorlevel 1 (
  echo Build failed. Check the Java compiler output above.
  pause
  exit /b 1
)
java -cp "out;lib/*" vn.hotel.HotelApp
if errorlevel 1 pause
