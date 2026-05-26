@echo off
REM =========================================================
REM LongMemEval Test Suite for OpenFriend Agent
REM Windows batch launcher
REM
REM Usage:
REM   eval\run_longmemeval.bat
REM   eval\run_longmemeval.bat --samples 20
REM   eval\run_longmemeval.bat --dataset data/longmemeval_oracle.json --judge
REM =========================================================
setlocal enabledelayedexpansion

cd /d %~dp0\..

REM ── Defaults ──
if "%AGENT_URL%"==""       set AGENT_URL=http://localhost:8123/api
if "%DATASET%"==""        set DATASET=data/longmemeval_oracle.json
if "%MAX_SAMPLES%"==""    set MAX_SAMPLES=50
if "%TIMEOUT%"==""         set TIMEOUT=300
if "%SKIP_JUDGE%"==""     set SKIP_JUDGE=--skip-judge
if "%JUDGE_MODEL%"==""    set JUDGE_MODEL=gpt-4o
if "%HF_ENDPOINT%"==""    set HF_ENDPOINT=https://hf-mirror.com

REM ── Parse args ──
:parse
if "%~1"=="" goto :check_prereqs
if "%~1"=="--samples"   (set MAX_SAMPLES=%~2 & shift & shift & goto :parse)
if "%~1"=="--dataset"   (set DATASET=%~2 & shift & shift & goto :parse)
if "%~1"=="--judge"     (set SKIP_JUDGE=& shift & goto :parse)
if "%~1"=="--judge-model" (set JUDGE_MODEL=%~2 & shift & shift & goto :parse)
if "%~1"=="--url"       (set AGENT_URL=%~2 & shift & shift & goto :parse)
if "%~1"=="--help"      goto :help
if "%~1"=="-h"          goto :help
echo Unknown option: %~1
exit /b 1

:help
echo Usage: eval\run_longmemeval.bat [OPTIONS]
echo.
echo Options:
echo   --samples N        Number of samples ^(default: 50^)
echo   --dataset PATH     Path to LongMemEval JSON file
echo   --judge            Enable GPT-4o judge ^(needs OPENAI_API_KEY^)
echo   --judge-model M    Judge model ^(default: gpt-4o^)
echo   --url URL          Agent URL ^(default: http://localhost:8123/api^)
echo.
echo Env vars:
echo   OPENAI_API_KEY     Required if using --judge
echo   HF_ENDPOINT        HuggingFace mirror ^(default: https://hf-mirror.com^)
exit /b 0

:check_prereqs
echo ================================================
echo  LongMemEval Test Suite
echo ================================================
echo Dataset:      %DATASET%
echo Agent URL:    %AGENT_URL%
echo Max samples:  %MAX_SAMPLES%
if "%SKIP_JUDGE%"=="" (
    echo Judge:        enabled ^(%JUDGE_MODEL%^)
) else (
    echo Judge:        disabled
)
echo ================================================
echo.

REM ── Step 1 ──
echo [1/6] Checking prerequisites...
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found
    exit /b 1
)
conda env list 2>nul | findstr longmemeval >nul 2>&1
if errorlevel 1 (
    echo [SETUP] Creating conda environment 'longmemeval'...
    call conda create -n longmemeval python=3.11 -y
    call conda run -n longmemeval pip install httpx openai
)
echo [OK] Prerequisites satisfied
echo.

REM ── Step 2 ──
echo [2/6] Checking dataset...
if not exist "%DATASET%" (
    for %%F in (%DATASET%) do mkdir "%%~dpF" 2>nul
    echo Downloading %DATASET%...
    curl -L -o "%DATASET%" "%HF_ENDPOINT%/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_oracle.json"
    if errorlevel 1 (
        echo [ERROR] Download failed. Try setting HF_ENDPOINT env var.
        exit /b 1
    )
) else (
    echo [OK] Dataset exists: %DATASET%
)
echo.

REM ── Step 3 ──
echo [3/6] Setting up test workspace...
if exist "workspace" (
    if not exist "workspace.bak" (
        echo Backing up workspace\ to workspace.bak\
        move workspace workspace.bak >nul
    ) else (
        echo [WARN] workspace.bak already exists, skipping backup
    )
)
if not exist "workspace\memory\diary" mkdir "workspace\memory\diary"
if not exist "workspace\memory\SOUL.md" (
    (
        echo ## SOUL.md - 你是谁
        echo.
        echo 你不仅仅是一个聊天机器人。
        echo.
        echo ### 核心准则
        echo - ^*真诚地提供帮助^*：追求实效，而非表演。
        echo - ^*独立解决问题^*：在提问之前，先穷尽你的资源。
    ) > workspace\memory\SOUL.md
)
if not exist "workspace\memory\memory.md" (
    (
        echo ## role
        echo - 当前角色：OpenFriend（通用智能伙伴）
        echo.
        echo ## preference
        echo 这里记录用户的长期偏好。
        echo.
        echo ## diary
        echo - 日记地图：待更新
        echo - 读取规则：默认不主动拉取历史日记
    ) > workspace\memory\memory.md
)
echo [OK] Workspace ready
echo.

REM ── Step 4 ──
echo [4/6] Checking agent...
curl -s -o nul -w "%%{http_code}" "%AGENT_URL%/ai/love_app/chat/sync?message=ping&chatId=health" | findstr "200" >nul
if errorlevel 1 (
    echo [ERROR] Agent is not running at %AGENT_URL%
    echo Start agent with: cd %CD% ^&^& mvn spring-boot:run
    echo Then re-run this script.
    exit /b 1
)
echo [OK] Agent connected
echo.

REM ── Step 5 ──
echo [5/6] Running evaluation...
echo.
call conda run -n longmemeval python eval/run_longmemeval.py ^
    --dataset "%DATASET%" ^
    --base-url "%AGENT_URL%" ^
    --max-samples "%MAX_SAMPLES%" ^
    --timeout "%TIMEOUT%" ^
    --output-dir eval/results ^
    --judge-model "%JUDGE_MODEL%" ^
    %SKIP_JUDGE%
echo.

REM ── Step 6 ──
echo [6/6] Restoring workspace...
del workspace\memory\memory.md.bak 2>nul
if exist "workspace.bak" (
    rmdir /s /q workspace 2>nul
    move workspace.bak workspace >nul
    echo [OK] Workspace restored
) else (
    echo [OK] No backup to restore
)
echo.

echo ================================================
echo  Evaluation Complete
echo ================================================
for /f "tokens=*" %%i in ('dir /b /ad /o-d eval\results\longmemeval-* 2^>nul') do (
    echo Results: eval\results\%%i\summary.json
    echo Details: eval\results\%%i\details.csv
    goto :done
)
:done
echo.
echo Workspace has been restored to original state.
