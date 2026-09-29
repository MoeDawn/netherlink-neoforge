@echo off
REM Uses the project-bundled Gradle wrapper.
REM
REM This machine cannot reach maven.neoforged.net (TLS handshake aborted), so the
REM build MUST carry the mirror init script -- see tools/neoforge-mirror.init.gradle.
REM On a machine with working direct access, drop the --init-script argument.
REM
REM NOTE: the full path of this directory contains an ampersand, which cmd treats
REM as a command separator. So we cd into the directory first and then call the
REM wrapper by its bare name -- never splice the full path into a call.
REM
REM NOTE: pushd is used instead of cd /d on purpose. %~dp0 ends with a backslash,
REM and inside double quotes cmd reads the trailing \" as an escaped quote, so
REM `cd /d "%~dp0"` silently stays in the wrong directory and the wrapper is not
REM found. pushd handles the trailing separator correctly.
REM
REM NOTE: keep this file pure ASCII. cmd reads .bat as the OEM codepage (GBK here),
REM so UTF-8 Chinese in these lines gets mangled into stray separators.
set "JAVA_HOME=C:\jdk25\jdk-25.0.4.1+1"
set "Path=%JAVA_HOME%\bin;%Path%"
pushd "%~dp0"
call gradlew.bat --init-script tools/neoforge-mirror.init.gradle build %*
popd
