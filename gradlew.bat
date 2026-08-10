@echo off
setlocal
set APP_HOME=%~dp0
if "%APP_HOME:~-1%"=="\" set APP_HOME=%APP_HOME:~0,-1%
if defined JAVA_HOME (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
) else (
  set JAVA_EXE=java.exe
)
"%JAVA_EXE%" -Dcontrolled.wrapper.projectDir="%APP_HOME%" "%APP_HOME%\tools\wrapper-src\org\gradle\wrapper\GradleWrapperMain.java" %*
endlocal
