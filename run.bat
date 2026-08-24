@echo off
rem ============================================================
rem  SafeFood 실행 - 이 파일을 더블클릭하면 앱이 뜹니다.
rem
rem  하는 일: 자바 찾기 -> 실행 파일이 없으면 빌드 -> 앱 실행
rem  다시 빌드하려면  run.bat build  로 실행하세요.
rem
rem  이 창을 닫으면 앱도 함께 종료됩니다.
rem
rem  [파일 인코딩 주의] 이 파일은 CP949(ANSI)로 저장돼 있습니다.
rem  UTF-8로 저장하면 cmd가 한글 줄의 위치를 잘못 잡아, 주석 한가운데를
rem  명령으로 실행하는 오류가 납니다. 편집기에서 인코딩을 바꾸지 마세요.
rem ============================================================
chcp 949 >nul
setlocal enabledelayedexpansion

rem 프로젝트 루트에서 실행해야 config.properties 와 data 폴더를 찾습니다
cd /d "%~dp0"

set "JAR=target\safefood.jar"

echo.
echo   SafeFood
echo   ----------------------------------------------------
echo.

call :find_java
if errorlevel 1 goto :fail

if /i "%~1"=="build" (
    echo   [준비] 실행 파일을 다시 만듭니다.
    if exist "%JAR%" del /q "%JAR%"
)

if not exist "%JAR%" (
    call :build
    if errorlevel 1 goto :fail
)

rem 자바 24부터 뜨는 경고 두 줄은 미리 끕니다. 21에는 없는 옵션이라 버전을 보고 붙입니다
set "JAVA_OPTS=-Dfile.encoding=UTF-8"
if defined JAVA_MAJOR if !JAVA_MAJOR! GEQ 24 set "JAVA_OPTS=!JAVA_OPTS! --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"

echo   [실행] 앱을 시작합니다. 창이 뜰 때까지 잠시 기다려 주세요.
echo.
"%JAVA_EXE%" !JAVA_OPTS! -jar "%JAR%"
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" (
    echo.
    echo   [x] 앱이 오류로 끝났습니다. 종료 코드 %CODE%. 위 메시지를 확인해 주세요.
    goto :fail
)
endlocal
exit /b 0


rem ------------------------------------------------------------
rem  자바 찾기: PATH -> JAVA_HOME -> IntelliJ 가 함께 깔아 주는 자바(JBR)
rem ------------------------------------------------------------
:find_java
set "JAVA_EXE="
for /f "delims=" %%p in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%p"

if not defined JAVA_EXE if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

if not defined JAVA_EXE (
    call :find_under "%ProgramFiles%\JetBrains" java.exe "\jbr\bin"
    if defined FOUND set "JAVA_EXE=!FOUND!"
)
if not defined JAVA_EXE (
    call :find_under "%LOCALAPPDATA%\JetBrains\Toolbox\apps" java.exe "\jbr\bin"
    if defined FOUND set "JAVA_EXE=!FOUND!"
)
if not defined JAVA_EXE (
    call :find_under "%LOCALAPPDATA%\Programs" java.exe "\jbr\bin"
    if defined FOUND set "JAVA_EXE=!FOUND!"
)

if not defined JAVA_EXE (
    echo   [x] 자바를 찾지 못했습니다.
    echo       JDK 21 이상을 설치한 뒤 다시 실행해 주세요. JAVA_HOME 설정도 좋습니다.
    echo       https://adoptium.net
    exit /b 1
)

rem 버전 확인. 자바를 for /f 안에서 직접 부르지 않고 파일로 받는 이유는,
rem 따옴표로 시작하는 명령을 넣으면 경로의 공백에서 깨지기 때문입니다.
set "JAVA_MAJOR="
set "VERFILE=%TEMP%\safefood-java-version.txt"
"!JAVA_EXE!" -version 2>"!VERFILE!"
for /f "usebackq tokens=3" %%v in (`findstr /i "version" "!VERFILE!"`) do (
    if not defined JAVA_MAJOR (
        set "RAW=%%~v"
        for /f "delims=._-+ " %%m in ("!RAW!") do set "JAVA_MAJOR=%%m"
    )
)
del /q "!VERFILE!" 2>nul

if "!JAVA_MAJOR!"=="1" set "JAVA_MAJOR=8"
if defined JAVA_MAJOR (
    if !JAVA_MAJOR! LSS 21 (
        echo   [x] 자바 !JAVA_MAJOR! 를 찾았지만 이 앱은 자바 21 이상이 필요합니다.
        echo       JDK 21 이상을 설치하거나 JAVA_HOME 을 그쪽으로 맞춰 주세요.
        exit /b 1
    )
    echo   [확인] 자바 !JAVA_MAJOR!
) else (
    echo   [확인] 자바 버전을 읽지 못했지만 그대로 진행합니다.
)
exit /b 0


rem ------------------------------------------------------------
rem  실행 파일 빌드: target\safefood.jar 이 없을 때만 돕니다.
rem  처음 한 번은 의존성을 내려받느라 인터넷이 필요합니다.
rem ------------------------------------------------------------
:build
echo   [빌드] 실행 파일이 없어 새로 만듭니다. 처음 한 번은 몇 분 걸립니다.
set "MVN="
for /f "delims=" %%p in ('where mvn 2^>nul') do if not defined MVN set "MVN=%%p"

if not defined MVN if defined MAVEN_HOME (
    if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
)

rem IntelliJ 는 Maven 을 함께 갖고 있습니다. 따로 설치하지 않은 PC를 위한 대비책입니다
if not defined MVN (
    call :find_under "%ProgramFiles%\JetBrains" mvn.cmd "\maven"
    if defined FOUND set "MVN=!FOUND!"
)
if not defined MVN (
    call :find_under "%LOCALAPPDATA%\JetBrains\Toolbox\apps" mvn.cmd "\maven"
    if defined FOUND set "MVN=!FOUND!"
)

if not defined MVN (
    echo   [x] Maven 을 찾지 못해 실행 파일을 만들 수 없습니다.
    echo       IntelliJ 오른쪽 Maven 창에서 Lifecycle 의 package 를 한 번 실행한 뒤
    echo       이 파일을 다시 실행해 주세요.
    exit /b 1
)

call "!MVN!" -DskipTests package
if errorlevel 1 (
    echo   [x] 빌드에 실패했습니다. 위 메시지를 확인해 주세요.
    exit /b 1
)
if not exist "%JAR%" (
    echo   [x] 빌드는 끝났는데 %JAR% 이 없습니다.
    exit /b 1
)
echo   [빌드] 완료 - %JAR%
exit /b 0


rem ------------------------------------------------------------
rem  폴더 아래에서 파일 찾기
rem    %1 = 뒤질 폴더, %2 = 파일 이름, %3 = 경로에 들어가야 하는 조각
rem    찾으면 FOUND 에 담고 못 찾으면 비워 둡니다.
rem ------------------------------------------------------------
:find_under
set "FOUND="
if not exist "%~1" exit /b 0
for /f "delims=" %%p in ('dir /b /s "%~1\%~2" 2^>nul ^| findstr /i /c:"%~3"') do (
    if not defined FOUND set "FOUND=%%p"
)
exit /b 0


:fail
echo.
pause
endlocal
exit /b 1
