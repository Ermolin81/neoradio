@echo off
chcp 65001 > nul
echo ==================================================
echo   NeoRadio: Сборка исполняемого файла Windows
echo ==================================================
call .venv\Scripts\python.exe -m PyInstaller NeoRadio.spec --noconfirm
if exist dist\NeoRadio.exe (
    copy /Y dist\NeoRadio.exe NeoRadio.exe > nul
    rmdir /S /Q build > nul 2>&1
    rmdir /S /Q dist > nul 2>&1
    echo.
    echo ==================================================
    echo Сборка успешно завершена! Файл: P:\online_radio\NeoRadio.exe
    echo ==================================================
) else (
    echo.
    echo [ОШИБКА] Сборка не удалась.
)
pause
