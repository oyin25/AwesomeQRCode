@echo off
@rem ------------------------------------------------------------------------------
@rem Gradle startup script for Windows
@rem ------------------------------------------------------------------------------

set DIR=%~dp0
set APP_BASE_NAME=%~n0
set APP_HOME=%DIR%

set JAVA_EXE=java
if defined JAVA_HOME (
    set JAVA_EXE=%JAVA_HOME%\bin\java.exe
)

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

"%JAVA_EXE%" %JAVA_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
