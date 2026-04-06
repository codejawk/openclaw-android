@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off

set APP_BASE_NAME=%~n0
set APP_HOME=%~dp0

set JAVA_EXE=java.exe
set GRADLE_WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% -classpath "%GRADLE_WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
